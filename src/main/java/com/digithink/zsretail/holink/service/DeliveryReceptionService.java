package com.digithink.zsretail.holink.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSupply;
import com.digithink.zsretail.headoffice.dto.DeliveryCopyDTO;
import com.digithink.zsretail.holink.dto.ReceivedDeliveryDTO;
import com.digithink.zsretail.holink.dto.ReceptionInputDTO;
import com.digithink.zsretail.holink.enumeration.ReceivedDeliveryStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.holink.repository.ReceivedDeliveryRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.service.StockMovementService;
import com.digithink.zsretail.service.StockService;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 7A.3: the BLs at a store whose goods come from the head office. A BL received by the copies
 * down is saved TO_RECEIVE ({@link #saveReceived}); the store confirms the quantity of each line (default: the quantity
 * sent) and its stock goes up at once with one DELIVERY_IN movement per line, once (the row is locked and a received BL
 * refuses a second confirmation), also when the head office is unreachable. A line whose item is not in this store
 * (a head office item, origin HEAD_OFFICE) keeps its quantity and waits: the next cycles apply its stock once the
 * catalogue brings the item ({@link #applyWaitingStock}). The confirmation is then sent up by SupplyPushService.
 */
@Service
@ConditionalOnHeadOfficeSupply
@Log4j2
public class DeliveryReceptionService {

	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 200;
	static final int TIMEOUT_SECONDS = 15;

	private final ReceivedDeliveryRepository deliveries;
	private final ItemRepository items;
	private final StockService stock;
	private final StockMovementService movements;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public DeliveryReceptionService(ReceivedDeliveryRepository deliveries, ItemRepository items, StockService stock,
			StockMovementService movements, PlatformTransactionManager transactionManager) {
		this(deliveries, items, stock, movements, timed(transactionManager), LocalDateTime::now);
	}

	/** With given transactions and clock: used by the tests. */
	public DeliveryReceptionService(ReceivedDeliveryRepository deliveries, ItemRepository items, StockService stock,
			StockMovementService movements, TransactionOperations transactions, Supplier<LocalDateTime> clock) {
		this.deliveries = deliveries;
		this.items = items;
		this.stock = stock;
		this.movements = movements;
		this.transactions = transactions;
		this.clock = clock;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	// ─── From the copies down ────────────────────────────────────

	/** What saving a received copy did: written or not, and the item codes not in this store. */
	public static final class Outcome {
		private final boolean written;
		private final List<String> missingItems;

		Outcome(boolean written, List<String> missingItems) {
			this.written = written;
			this.missingItems = missingItems;
		}

		public boolean isWritten() {
			return written;
		}

		public List<String> getMissingItems() {
			return missingItems;
		}
	}

	/**
	 * A BL received from the head office. A new number is saved TO_RECEIVE with its lines (each resolved to this store's
	 * head office item, or waiting). A number already here is left as it is: a BL does not change once sent, and a
	 * received one belongs to the store's count. Called inside the handler's transaction.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Outcome saveReceived(DeliveryCopyDTO copy) {
		Optional<ReceivedDelivery> existing = deliveries.findByNumber(copy.getNumber());
		if (existing.isPresent()) {
			return new Outcome(false, missingCodes(existing.get()));
		}
		ReceivedDelivery delivery = new ReceivedDelivery();
		delivery.setNumber(copy.getNumber());
		delivery.setDocumentDate(copy.getDocumentDate() == null ? null : LocalDate.parse(copy.getDocumentDate()));
		delivery.setSentAt(copy.getSentAt() == null ? null : LocalDateTime.parse(copy.getSentAt()));
		delivery.setNote(cut(copy.getNote(), ReceivedDelivery.NOTE_LENGTH));
		delivery.setStatus(ReceivedDeliveryStatus.TO_RECEIVE);
		for (DeliveryCopyDTO.DeliveryLineCopyDTO lineCopy : copy.getLines()) {
			if (lineCopy.getLineNo() == null || lineCopy.getItemCode() == null || lineCopy.getQuantitySent() == null) {
				throw new IllegalArgumentException("line without number, item code or quantity");
			}
			ReceivedDeliveryLine line = new ReceivedDeliveryLine();
			line.setDelivery(delivery);
			line.setLineNo(lineCopy.getLineNo());
			line.setItemCode(lineCopy.getItemCode());
			line.setItemName(lineCopy.getItemName());
			line.setQuantitySent(lineCopy.getQuantitySent());
			line.setItemId(headOfficeItem(lineCopy.getItemCode()).map(Item::getId).orElse(null));
			delivery.getLines().add(line);
		}
		ReceivedDelivery saved = deliveries.save(delivery);
		log.info("Head office link: BL {} received, {} lines to receive", saved.getNumber(), saved.getLines().size());
		return new Outcome(true, missingCodes(saved));
	}

	/**
	 * Every cycle (also when the head office is unreachable): the lines of the BLs to receive whose item has arrived get
	 * it; the confirmed lines that wait for their item get their stock once it is here, each BL in its own transaction.
	 * Answers, per BL that still waits, the item codes still missing.
	 */
	public Map<String, List<String>> applyWaitingStock() {
		List<Long> toResolve = transactions
				.execute(status -> deliveries.findIdsWithMissingItem(ReceivedDeliveryStatus.TO_RECEIVE));
		for (Long id : toResolve == null ? new ArrayList<Long>() : toResolve) {
			transactions.executeWithoutResult(status -> deliveries.findForUpdate(id).ifPresent(delivery -> {
				if (delivery.getStatus() == ReceivedDeliveryStatus.TO_RECEIVE && resolveItems(delivery)) {
					deliveries.save(delivery);
				}
			}));
		}
		Map<String, List<String>> waiting = new LinkedHashMap<>();
		List<Long> toApply = transactions.execute(status -> deliveries.findIdsWithStock(Boolean.FALSE));
		for (Long id : toApply == null ? new ArrayList<Long>() : toApply) {
			transactions.executeWithoutResult(status -> deliveries.findForUpdate(id).ifPresent(delivery -> {
				if (applyStock(delivery)) {
					deliveries.save(delivery);
				}
				List<String> still = delivery.getLines().stream().filter(l -> Boolean.FALSE.equals(l.getStockApplied()))
						.map(ReceivedDeliveryLine::getItemCode).collect(Collectors.toList());
				if (!still.isEmpty()) {
					waiting.put(delivery.getNumber(), still);
				}
			}));
		}
		return waiting;
	}

	// ─── The store confirms ──────────────────────────────────────

	/**
	 * The store confirms the quantities it received: empty when unknown; 409 (IllegalState) when the BL is already
	 * received (nothing changes); 400 (IllegalArgument) for an unknown line, a line given twice, a quantity below 0, a
	 * note too long. A line absent from the body, or without a quantity, is received as sent; a quantity above the
	 * quantity sent is accepted (the store counted it).
	 */
	@Transactional(rollbackFor = Exception.class)
	public Optional<ReceivedDeliveryDTO> receive(Long id, ReceptionInputDTO input, String user) {
		Optional<ReceivedDelivery> found = deliveries.findForUpdate(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		ReceivedDelivery delivery = found.get();
		if (delivery.getStatus() == ReceivedDeliveryStatus.RECEIVED) {
			throw new IllegalStateException("This BL has already been received: " + delivery.getNumber() + ".");
		}
		Map<Integer, Integer> quantities = quantities(delivery, input);
		String note = input == null || input.getNote() == null || input.getNote().trim().isEmpty() ? null
				: input.getNote().trim();
		if (note != null && note.length() > ReceivedDelivery.NOTE_LENGTH) {
			throw new IllegalArgumentException("The note is longer than " + ReceivedDelivery.NOTE_LENGTH + " characters.");
		}
		resolveItems(delivery);
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			Integer quantity = quantities.get(line.getLineNo());
			line.setQuantityReceived(quantity == null ? line.getQuantitySent() : quantity);
			line.setStockApplied(Boolean.FALSE);
		}
		applyStock(delivery);
		delivery.setStatus(ReceivedDeliveryStatus.RECEIVED);
		delivery.setReceivedAt(clock.get());
		delivery.setReceivedBy(cut(user, ReceivedDelivery.USER_LENGTH));
		delivery.setStoreNote(note);
		delivery.setPushStatus(SalesCopyStatus.PENDING);
		delivery.setAttempts(0);
		delivery.setLastError(null);
		ReceivedDelivery saved = deliveries.save(delivery);
		log.info("Head office link: BL {} received by {}: {} of {} items", saved.getNumber(), user,
				total(saved, true), total(saved, false));
		return Optional.of(view(saved, true, false)); // the stock was changed by native updates: GET /{id} reads it
	}

	private static Map<Integer, Integer> quantities(ReceivedDelivery delivery, ReceptionInputDTO input) {
		Map<Integer, Integer> quantities = new HashMap<>();
		List<Integer> known = delivery.getLines().stream().map(ReceivedDeliveryLine::getLineNo).collect(Collectors.toList());
		if (input == null || input.getLines() == null) {
			return quantities;
		}
		List<Integer> given = new ArrayList<>();
		for (ReceptionInputDTO.Line line : input.getLines()) {
			if (line == null || line.getLineNo() == null || !known.contains(line.getLineNo())) {
				throw new IllegalArgumentException("Unknown line " + (line == null ? null : line.getLineNo()) + " on "
						+ delivery.getNumber() + ".");
			}
			if (given.contains(line.getLineNo())) {
				throw new IllegalArgumentException("Line " + line.getLineNo() + " is given twice.");
			}
			given.add(line.getLineNo());
			if (line.getQuantityReceived() != null) {
				if (line.getQuantityReceived() < 0) {
					throw new IllegalArgumentException(
							"Line " + line.getLineNo() + ": the quantity received must be a whole number, 0 or more.");
				}
				quantities.put(line.getLineNo(), line.getQuantityReceived());
			}
		}
		return quantities;
	}

	/** Lines without an item get it when it is here now. True when one changed. */
	private boolean resolveItems(ReceivedDelivery delivery) {
		boolean changed = false;
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			if (line.getItemId() == null) {
				Optional<Item> item = headOfficeItem(line.getItemCode());
				if (item.isPresent()) {
					line.setItemId(item.get().getId());
					changed = true;
				}
			}
		}
		return changed;
	}

	/**
	 * The confirmed lines whose stock is not in yet: with their item here, the quantity goes in with one DELIVERY_IN
	 * movement and the line is marked applied (0 needs nothing). True when one changed.
	 */
	private boolean applyStock(ReceivedDelivery delivery) {
		boolean changed = resolveItems(delivery);
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			if (!Boolean.FALSE.equals(line.getStockApplied())) {
				continue;
			}
			int quantity = line.getQuantityReceived() == null ? 0 : line.getQuantityReceived();
			if (quantity > 0 && line.getItemId() == null) {
				continue; // waits for its item
			}
			if (quantity > 0) {
				stock.incrementForDelivery(line.getItemId(), quantity);
				movements.recordDeliveryIn(line.getItemId(), quantity, delivery.getId(), delivery.getNumber());
			}
			line.setStockApplied(Boolean.TRUE);
			changed = true;
		}
		return changed;
	}

	/** This store's item with that code, only when it is a head office item (origin HEAD_OFFICE). */
	private Optional<Item> headOfficeItem(String itemCode) {
		return items.findByItemCode(itemCode).filter(item -> item.getOrigin() == RecordOrigin.HEAD_OFFICE);
	}

	private static List<String> missingCodes(ReceivedDelivery delivery) {
		return delivery.getLines().stream().filter(l -> l.getItemId() == null).map(ReceivedDeliveryLine::getItemCode)
				.collect(Collectors.toList());
	}

	// ─── Reads ───────────────────────────────────────────────────

	/** The page of the reception screen: {content, totalElements, totalPages, number, size}, newest first. */
	@Transactional(readOnly = true)
	public Map<String, Object> list(String status, Integer page, Integer size) {
		ReceivedDeliveryStatus wanted = null;
		if (status != null && !status.trim().isEmpty() && !"all".equalsIgnoreCase(status.trim())) {
			try {
				wanted = ReceivedDeliveryStatus.valueOf(status.trim().toUpperCase());
			} catch (IllegalArgumentException e) {
				throw new IllegalArgumentException("Invalid status '" + status + "': allowed values are all, "
						+ Arrays.toString(ReceivedDeliveryStatus.values()));
			}
		}
		int pageNumber = page == null ? 0 : page;
		if (pageNumber < 0) {
			throw new IllegalArgumentException("page must be 0 or more");
		}
		int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, size));
		Page<ReceivedDelivery> result = deliveries.findPage(wanted == null ? 1L : 0L,
				wanted == null ? ReceivedDeliveryStatus.TO_RECEIVE : wanted, PageRequest.of(pageNumber, pageSize));
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content", result.getContent().stream().map(d -> view(d, false, false)).collect(Collectors.toList()));
		answer.put("totalElements", result.getTotalElements());
		answer.put("totalPages", result.getTotalPages());
		answer.put("number", result.getNumber());
		answer.put("size", result.getSize());
		return answer;
	}

	@Transactional(readOnly = true)
	public Optional<ReceivedDeliveryDTO> get(Long id) {
		return deliveries.findById(id).map(d -> view(d, true, true));
	}

	/**
	 * For the link page: {"toReceive": n, "received": n, "confirmations": {"PENDING": n, "SENT": n, "ERROR": n},
	 * "stockWaiting": lines}.
	 */
	public Map<String, Object> counts() {
		Map<String, Object> counts = new LinkedHashMap<>();
		Map<ReceivedDeliveryStatus, Long> byStatus = new EnumMap<>(ReceivedDeliveryStatus.class);
		for (ReceivedDeliveryStatus status : ReceivedDeliveryStatus.values()) {
			byStatus.put(status, 0L);
		}
		for (Object[] row : deliveries.countByStatus()) {
			byStatus.put((ReceivedDeliveryStatus) row[0], ((Number) row[1]).longValue());
		}
		Map<String, Long> confirmations = new LinkedHashMap<>();
		for (SalesCopyStatus status : SalesCopyStatus.values()) {
			confirmations.put(status.name(), 0L);
		}
		for (Object[] row : deliveries.countByPushStatus()) {
			confirmations.put(((SalesCopyStatus) row[0]).name(), ((Number) row[1]).longValue());
		}
		counts.put("toReceive", byStatus.get(ReceivedDeliveryStatus.TO_RECEIVE));
		counts.put("received", byStatus.get(ReceivedDeliveryStatus.RECEIVED));
		counts.put("confirmations", confirmations);
		counts.put("stockWaiting", deliveries.countLinesWithStock(Boolean.FALSE));
		return counts;
	}

	private ReceivedDeliveryDTO view(ReceivedDelivery delivery, boolean withLines, boolean withStock) {
		ReceivedDeliveryDTO view = new ReceivedDeliveryDTO();
		view.setId(delivery.getId());
		view.setNumber(delivery.getNumber());
		view.setDocumentDate(delivery.getDocumentDate());
		view.setSentAt(delivery.getSentAt());
		view.setNote(delivery.getNote());
		view.setStatus(delivery.getStatus().name());
		view.setReceivedAt(delivery.getReceivedAt());
		view.setReceivedBy(delivery.getReceivedBy());
		view.setStoreNote(delivery.getStoreNote());
		view.setInvoiceNumber(delivery.getInvoiceNumber());
		view.setPushStatus(delivery.getPushStatus() == null ? null : delivery.getPushStatus().name());
		view.setLastError(delivery.getLastError());
		Integer received = null;
		boolean difference = false;
		int missing = 0;
		int waiting = 0;
		List<ReceivedDeliveryDTO.Line> lines = new ArrayList<>();
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			if (line.getQuantityReceived() != null) {
				received = (received == null ? 0 : received) + line.getQuantityReceived();
				difference |= !line.getQuantityReceived().equals(line.getQuantitySent());
			}
			missing += line.getItemId() == null ? 1 : 0;
			waiting += Boolean.FALSE.equals(line.getStockApplied()) ? 1 : 0;
			if (withLines) {
				ReceivedDeliveryDTO.Line row = new ReceivedDeliveryDTO.Line();
				row.setLineNo(line.getLineNo());
				row.setItemCode(line.getItemCode());
				row.setItemName(line.getItemName());
				row.setItemHere(line.getItemId() != null);
				row.setQuantitySent(line.getQuantitySent());
				row.setQuantityReceived(line.getQuantityReceived());
				row.setDifference(line.getQuantityReceived() == null ? null
						: line.getQuantityReceived() - line.getQuantitySent());
				row.setStockApplied(line.getStockApplied());
				row.setStoreStock(!withStock || line.getItemId() == null ? null
						: items.findById(line.getItemId()).map(i -> i.getStockQuantity() == null ? 0 : i.getStockQuantity())
								.orElse(null));
				lines.add(row);
			}
		}
		view.setLineCount(delivery.getLines().size());
		view.setQuantitySent(total(delivery, false));
		view.setQuantityReceived(received);
		view.setDifference(difference);
		view.setMissingItems(missing);
		view.setStockWaiting(waiting);
		view.setLines(withLines ? lines : null);
		return view;
	}

	private static int total(ReceivedDelivery delivery, boolean received) {
		int total = 0;
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			Integer quantity = received ? line.getQuantityReceived() : line.getQuantitySent();
			total += quantity == null ? 0 : quantity;
		}
		return total;
	}

	static String cut(String text, int length) {
		return text == null || text.length() <= length ? text : text.substring(0, length);
	}
}
