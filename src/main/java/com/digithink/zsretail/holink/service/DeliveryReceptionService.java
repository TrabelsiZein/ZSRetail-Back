package com.digithink.zsretail.holink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import com.digithink.zsretail.headoffice.dto.ErpInvoiceCopyDTO;
import com.digithink.zsretail.holink.dto.ReceivedDeliveryDTO;
import com.digithink.zsretail.holink.dto.ReceptionInputDTO;
import com.digithink.zsretail.holink.enumeration.ReceivedDeliveryStatus;
import com.digithink.zsretail.holink.enumeration.ReceivedDocumentKind;
import com.digithink.zsretail.holink.enumeration.ReceivedLineType;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.holink.repository.ReceivedDeliveryRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.service.StockMovementService;
import com.digithink.zsretail.service.StockService;
import com.digithink.zsretail.utils.Quantities;
import com.digithink.zsretail.service.QuantityPolicy;

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
	static final int COST_SCALE = 5;

	private final ReceivedDeliveryRepository deliveries;
	private final ItemRepository items;
	private final StockService stock;
	private final StockMovementService movements;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;

	/** Invoices from the ERP, step (c): the purchase invoice of a received ERP invoice; null in the BL-only tests. */
	private final SupplyInvoiceWriter invoiceWriter;

	/** 2.2.1: ALLOW_DECIMAL_QUANTITY; null in the tests that do not set it (decimals then refused, as off). */
	private QuantityPolicy quantityPolicy;

	@Autowired
	public void setQuantityPolicy(QuantityPolicy quantityPolicy) {
		this.quantityPolicy = quantityPolicy;
	}

	@Autowired
	public DeliveryReceptionService(ReceivedDeliveryRepository deliveries, ItemRepository items, StockService stock,
			StockMovementService movements, PlatformTransactionManager transactionManager,
			SupplyInvoiceWriter invoiceWriter) {
		this(deliveries, items, stock, movements, timed(transactionManager), LocalDateTime::now, invoiceWriter);
	}

	/** With given transactions and clock, without the purchase invoices of ERP invoices: used by the tests. */
	public DeliveryReceptionService(ReceivedDeliveryRepository deliveries, ItemRepository items, StockService stock,
			StockMovementService movements, TransactionOperations transactions, Supplier<LocalDateTime> clock) {
		this(deliveries, items, stock, movements, transactions, clock, null);
	}

	/** With given transactions, clock and purchase invoice writer: used by the tests. */
	public DeliveryReceptionService(ReceivedDeliveryRepository deliveries, ItemRepository items, StockService stock,
			StockMovementService movements, TransactionOperations transactions, Supplier<LocalDateTime> clock,
			SupplyInvoiceWriter invoiceWriter) {
		this.deliveries = deliveries;
		this.items = items;
		this.stock = stock;
		this.movements = movements;
		this.transactions = transactions;
		this.clock = clock;
		this.invoiceWriter = invoiceWriter;
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
			line.setQuantitySent(keptQuantity(lineCopy.getLineNo(), lineCopy.getQuantitySent()));
			line.setItemId(headOfficeItem(lineCopy.getItemCode()).map(Item::getId).orElse(null));
			delivery.getLines().add(line);
		}
		ReceivedDelivery saved = deliveries.save(delivery);
		log.info("Head office link: BL {} received, {} lines to receive", saved.getNumber(), saved.getLines().size());
		return new Outcome(true, missingCodes(saved));
	}

	/**
	 * Invoices from the ERP, step (c): an invoice of the ERP received from the head office (record ERPINV:). A new number
	 * is saved TO_RECEIVE, kind ERP_INVOICE, with the seller, the customer and the ERP's totals: each ITEM line resolved to
	 * this store's head office item or waiting, exactly like a BL line (quantity sent = quantity invoiced); each OTHER line
	 * kept with its amount, no item, no quantity. A number already here is left as it is. Called inside the handler's
	 * transaction.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Outcome saveReceived(ErpInvoiceCopyDTO copy) {
		Optional<ReceivedDelivery> existing = deliveries.findByNumber(copy.getInvoiceNumber());
		if (existing.isPresent()) {
			return new Outcome(false, missingCodes(existing.get()));
		}
		ReceivedDelivery delivery = new ReceivedDelivery();
		delivery.setNumber(copy.getInvoiceNumber());
		delivery.setDocumentKind(ReceivedDocumentKind.ERP_INVOICE);
		delivery.setDocumentDate(copy.getDocumentDate() == null ? null : LocalDate.parse(copy.getDocumentDate()));
		delivery.setSentAt(copy.getSentAt() == null ? null : LocalDateTime.parse(copy.getSentAt()));
		delivery.setSellerName(cut(copy.getSellerName(), ReceivedDelivery.NAME_LENGTH));
		delivery.setCustomerName(cut(copy.getCustomerName(), ReceivedDelivery.NAME_LENGTH));
		delivery.setTotalExclVat(copy.getTotalExclVat());
		delivery.setTotalVat(copy.getTotalVat());
		delivery.setTotalInclVat(copy.getTotalInclVat());
		delivery.setStatus(ReceivedDeliveryStatus.TO_RECEIVE);
		for (ErpInvoiceCopyDTO.Line lineCopy : copy.getLines()) {
			boolean other = ReceivedLineType.OTHER.name().equals(lineCopy.getLineType());
			if (lineCopy.getLineNo() == null || (!other && (lineCopy.getItemCode() == null
					|| lineCopy.getItemCode().trim().isEmpty() || lineCopy.getQuantity() == null))) {
				throw new IllegalArgumentException("line without number, item code or quantity");
			}
			ReceivedDeliveryLine line = new ReceivedDeliveryLine();
			line.setDelivery(delivery);
			line.setLineNo(lineCopy.getLineNo());
			line.setLineType(other ? ReceivedLineType.OTHER : ReceivedLineType.ITEM);
			line.setItemCode(other ? "" : lineCopy.getItemCode().trim());
			line.setItemName(lineCopy.getDescription());
			line.setQuantitySent(other ? BigDecimal.ZERO : keptQuantity(lineCopy.getLineNo(), lineCopy.getQuantity()));
			line.setItemId(other ? null : headOfficeItem(line.getItemCode()).map(Item::getId).orElse(null));
			line.setUnitPrice(lineCopy.getUnitPrice());
			line.setLineDiscountPercent(lineCopy.getLineDiscountPercent());
			line.setLineAmount(lineCopy.getLineAmount());
			line.setUnitCost(other ? null : lineCopy.getUnitCost());
			delivery.getLines().add(line);
		}
		ReceivedDelivery saved = deliveries.save(delivery);
		log.info("Head office link: ERP invoice {} received, {} lines to receive", saved.getNumber(),
				saved.getLines().size());
		return new Outcome(true, missingCodes(saved));
	}

	/**
	 * 2.2.1: a quantity sent by the head office as it came, up to 3 decimals; more is refused (the copy is then an error,
	 * retried), never rounded by the column.
	 */
	private static BigDecimal keptQuantity(Integer lineNo, BigDecimal quantity) {
		if (Quantities.decimals(quantity) > Quantities.SCALE) {
			throw new IllegalArgumentException("line " + lineNo + ": quantity " + quantity.toPlainString() + " has more than "
					+ Quantities.SCALE + " decimals");
		}
		return Quantities.normalize(quantity);
	}

	/** The record code of a received document: BL:&lt;number&gt; or ERPINV:&lt;number&gt;. */
	static String recordCode(ReceivedDelivery delivery) {
		return delivery.isErpInvoice() ? ErpInvoiceCopyDTO.recordCode(delivery.getNumber())
				: DeliveryCopyDTO.recordCode(delivery.getNumber());
	}

	/**
	 * Every cycle (also when the head office is unreachable): the lines of the documents to receive whose item has arrived
	 * get it; the confirmed lines that wait for their item get their stock once it is here (and, on an ERP invoice, their
	 * cost, and their item on the purchase invoice), each document in its own transaction. Answers, per document that
	 * still waits (by its record code, BL:&lt;number&gt; or ERPINV:&lt;number&gt;), the item codes still missing.
	 */
	public Map<String, List<String>> applyWaitingStock() {
		List<Long> toResolve = transactions.execute(
				status -> deliveries.findIdsWithMissingItem(ReceivedDeliveryStatus.TO_RECEIVE, ReceivedLineType.OTHER));
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
					if (delivery.isErpInvoice() && invoiceWriter != null) {
						invoiceWriter.attachItems(delivery); // the purchase invoice lines get the items that arrived
					}
				}
				List<String> still = delivery.getLines().stream().filter(l -> Boolean.FALSE.equals(l.getStockApplied()))
						.map(ReceivedDeliveryLine::getItemCode).collect(Collectors.toList());
				if (!still.isEmpty()) {
					waiting.put(recordCode(delivery), still);
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
		Map<Integer, BigDecimal> quantities = quantities(delivery, input);
		checkDecimalsAllowed(delivery, quantities);
		String note = input == null || input.getNote() == null || input.getNote().trim().isEmpty() ? null
				: input.getNote().trim();
		if (note != null && note.length() > ReceivedDelivery.NOTE_LENGTH) {
			throw new IllegalArgumentException("The note is longer than " + ReceivedDelivery.NOTE_LENGTH + " characters.");
		}
		resolveItems(delivery);
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			if (!line.isItemLine()) { // an OTHER line of an ERP invoice: no quantity, nothing to put in the stock
				line.setQuantityReceived(null);
				line.setStockApplied(Boolean.TRUE);
				continue;
			}
			BigDecimal quantity = quantities.get(line.getLineNo());
			line.setQuantityReceived(quantity == null ? line.getQuantitySent() : quantity);
			line.setStockApplied(Boolean.FALSE);
		}
		delivery.setStatus(ReceivedDeliveryStatus.RECEIVED); // first: the costs of an ERP invoice go in with the stock
		applyStock(delivery);
		delivery.setReceivedAt(clock.get());
		delivery.setReceivedBy(cut(user, ReceivedDelivery.USER_LENGTH));
		delivery.setStoreNote(note);
		delivery.setPushStatus(SalesCopyStatus.PENDING);
		delivery.setAttempts(0);
		delivery.setLastError(null);
		if (delivery.isErpInvoice() && invoiceWriter != null) {
			// Invoices from the ERP: the purchase invoice in the same transaction, as the ERP invoiced it (never the
			// quantities received)
			invoiceWriter.saveFromErpInvoice(delivery);
			delivery.setInvoiceNumber(delivery.getNumber());
		}
		ReceivedDelivery saved = deliveries.save(delivery);
		log.info("Head office link: BL {} received by {}: {} of {} items", saved.getNumber(), user,
				total(saved, true), total(saved, false));
		return Optional.of(view(saved, true, false)); // the stock was changed by native updates: GET /{id} reads it
	}

	/**
	 * 2.2.1: a document with a decimal quantity (sent, or typed as received) is received only when the store allows
	 * decimal quantities (General Setup, Allow decimal quantities); otherwise 409 with a message naming the setting, and
	 * nothing changes. A whole document never reads the setting: as in 2.2.0.
	 */
	private void checkDecimalsAllowed(ReceivedDelivery delivery, Map<Integer, BigDecimal> typed) {
		String first = null;
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			BigDecimal typedQuantity = typed.get(line.getLineNo());
			if (line.isItemLine() && !Quantities.isWhole(line.getQuantitySent())) {
				first = "line " + line.getLineNo() + " was sent with the quantity " + Quantities.plain(line.getQuantitySent());
			} else if (!Quantities.isWhole(typedQuantity)) {
				first = "line " + line.getLineNo() + " was typed with the quantity " + Quantities.plain(typedQuantity);
			}
			if (first != null) {
				break;
			}
		}
		if (first != null && (quantityPolicy == null || !quantityPolicy.decimalAllowed())) {
			throw new IllegalStateException(delivery.getNumber() + " cannot be received: " + first
					+ ", and decimal quantities are not allowed in this store (General Setup, Allow decimal quantities).");
		}
	}

	private static Map<Integer, BigDecimal> quantities(ReceivedDelivery delivery, ReceptionInputDTO input) {
		Map<Integer, BigDecimal> quantities = new HashMap<>();
		List<Integer> known = delivery.getLines().stream().map(ReceivedDeliveryLine::getLineNo).collect(Collectors.toList());
		if (input == null || input.getLines() == null) {
			return quantities;
		}
		List<Integer> given = new ArrayList<>();
		List<Integer> others = delivery.getLines().stream().filter(l -> !l.isItemLine())
				.map(ReceivedDeliveryLine::getLineNo).collect(Collectors.toList());
		for (ReceptionInputDTO.Line line : input.getLines()) {
			if (line == null || line.getLineNo() == null || !known.contains(line.getLineNo())) {
				throw new IllegalArgumentException("Unknown line " + (line == null ? null : line.getLineNo()) + " on "
						+ delivery.getNumber() + ".");
			}
			if (others.contains(line.getLineNo()) && line.getQuantityReceived() != null) {
				throw new IllegalArgumentException("Line " + line.getLineNo() + " has no item: it takes no quantity.");
			}
			if (given.contains(line.getLineNo())) {
				throw new IllegalArgumentException("Line " + line.getLineNo() + " is given twice.");
			}
			given.add(line.getLineNo());
			if (line.getQuantityReceived() != null) {
				if (line.getQuantityReceived().signum() < 0) {
					throw new IllegalArgumentException(
							"Line " + line.getLineNo() + ": the quantity received must be a whole number, 0 or more.");
				}
				if (Quantities.decimals(line.getQuantityReceived()) > Quantities.SCALE) { // 2.2.1: never rounded
					throw new IllegalArgumentException("Line " + line.getLineNo() + ": the quantity received "
							+ line.getQuantityReceived().toPlainString() + " has more than " + Quantities.SCALE + " decimals.");
				}
				quantities.put(line.getLineNo(), Quantities.normalize(line.getQuantityReceived()));
			}
		}
		return quantities;
	}

	/** Item lines without an item get it when it is here now (an OTHER line never has one). True when one changed. */
	private boolean resolveItems(ReceivedDelivery delivery) {
		boolean changed = false;
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			if (line.getItemId() == null && line.isItemLine()) {
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
			BigDecimal quantity = line.getQuantityReceived() == null ? BigDecimal.ZERO : line.getQuantityReceived();
			if ((quantity.signum() > 0 || costPending(delivery, line)) && line.getItemId() == null) {
				continue; // waits for its item (an ERP invoice line received at 0 still waits for it: its costs go in then)
			}
			if (quantity.signum() > 0) {
				stock.incrementForDelivery(line.getItemId(), quantity);
				movements.recordDeliveryIn(line.getItemId(), quantity, delivery.getId(), delivery.getNumber());
			}
			line.setStockApplied(Boolean.TRUE);
			changed = true;
		}
		return applyCost(delivery) || changed;
	}

	/**
	 * Invoices from the ERP (rule of the NAV team, 2026-10-09): the costs of each item of the received invoice, written once
	 * per item, from its paid lines (amount above 0), whatever the quantity received (even 0: the prices are per unit):
	 * last direct cost = the line's unit price (before the line discount and the VAT); last direct net cost = cost price =
	 * its net unit cost (line amount / quantity invoiced = unit price x (1 - line discount), before the VAT). The same item
	 * on several paid lines: the highest line number wins. A line at amount 0 (a tester, a gift) changes no cost; the
	 * header discount is never read; the selling price (unit price of the item) is never touched. A line waits until its
	 * item is here; a BL has no cost. True when one changed.
	 */
	private boolean applyCost(ReceivedDelivery delivery) {
		if (!delivery.isErpInvoice()) {
			return false;
		}
		Map<Long, List<ReceivedDeliveryLine>> paidByItem = new LinkedHashMap<>();
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			if (costPending(delivery, line) && line.getItemId() != null) {
				paidByItem.computeIfAbsent(line.getItemId(), id -> new ArrayList<>()).add(line);
			}
		}
		boolean changed = false;
		for (Map.Entry<Long, List<ReceivedDeliveryLine>> item : paidByItem.entrySet()) {
			ReceivedDeliveryLine last = item.getValue().stream()
					.max(java.util.Comparator.comparing(ReceivedDeliveryLine::getLineNo)).get();
			double net = round(last.getUnitCost());
			double gross = last.getUnitPrice() == null ? net : round(last.getUnitPrice());
			items.updateCost(item.getKey(), gross, net, SupplyInvoiceWriter.WRITER);
			item.getValue().forEach(l -> l.setCostApplied(Boolean.TRUE));
			changed = true;
		}
		return changed;
	}

	/**
	 * An item line of an ERP invoice whose costs are still to write: a paid line (amount above 0, net unit cost known), not
	 * done yet, once the invoice is received.
	 */
	private static boolean costPending(ReceivedDelivery delivery, ReceivedDeliveryLine line) {
		return delivery.isErpInvoice() && delivery.getStatus() == ReceivedDeliveryStatus.RECEIVED && line.isItemLine()
				&& line.getUnitCost() != null && line.getLineAmount() != null && line.getLineAmount() > 0
				&& !Boolean.TRUE.equals(line.getCostApplied());
	}

	private static double round(double value) {
		return BigDecimal.valueOf(value).setScale(COST_SCALE, RoundingMode.HALF_UP).doubleValue();
	}

	/** This store's item with that code, only when it is a head office item (origin HEAD_OFFICE). */
	private Optional<Item> headOfficeItem(String itemCode) {
		return items.findByItemCode(itemCode).filter(item -> item.getOrigin() == RecordOrigin.HEAD_OFFICE);
	}

	private static List<String> missingCodes(ReceivedDelivery delivery) {
		return delivery.getLines().stream().filter(l -> l.getItemId() == null && l.isItemLine())
				.map(ReceivedDeliveryLine::getItemCode).collect(Collectors.toList());
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
		view.setDocumentKind(delivery.kindOrBl().name());
		view.setSellerName(delivery.getSellerName());
		view.setCustomerName(delivery.getCustomerName());
		view.setTotalExclVat(delivery.getTotalExclVat());
		view.setTotalVat(delivery.getTotalVat());
		view.setTotalInclVat(delivery.getTotalInclVat());
		BigDecimal received = null;
		boolean difference = false;
		int missing = 0;
		int waiting = 0;
		List<ReceivedDeliveryDTO.Line> lines = new ArrayList<>();
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			if (line.getQuantityReceived() != null) {
				received = (received == null ? BigDecimal.ZERO : received).add(line.getQuantityReceived());
				difference |= line.getQuantityReceived().compareTo(line.getQuantitySent()) != 0;
			}
			missing += line.getItemId() == null && line.isItemLine() ? 1 : 0;
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
						: Quantities.normalize(line.getQuantityReceived().subtract(line.getQuantitySent())));
				row.setStockApplied(line.getStockApplied());
				Optional<Item> item = !withStock || line.getItemId() == null ? Optional.empty()
						: items.findById(line.getItemId());
				row.setStoreStock(item.map(i -> i.getStockQuantity() == null ? BigDecimal.ZERO : i.getStockQuantity()).orElse(null));
				row.setLineType((line.isItemLine() ? ReceivedLineType.ITEM : ReceivedLineType.OTHER).name());
				row.setUnitPrice(line.getUnitPrice());
				row.setLineDiscountPercent(line.getLineDiscountPercent());
				row.setLineAmount(line.getLineAmount());
				row.setUnitCost(line.getUnitCost());
				row.setCostApplied(line.getCostApplied());
				if (delivery.isErpInvoice()) {
					row.setSellingPrice(item.map(DeliveryReceptionService::sellingPrice).orElse(null));
					BigDecimal counted = line.getQuantityReceived() != null ? line.getQuantityReceived() : line.getQuantitySent();
					row.setLineTotal(line.getUnitCost() == null || counted == null ? null
							: BigDecimal.valueOf(line.getUnitCost()).multiply(counted)
									.setScale(3, RoundingMode.HALF_UP).doubleValue());
				}
				lines.add(row);
			}
		}
		view.setLineCount(delivery.getLines().size());
		view.setQuantitySent(total(delivery, false));
		view.setQuantityReceived(Quantities.normalize(received));
		view.setDifference(difference);
		view.setMissingItems(missing);
		view.setStockWaiting(waiting);
		view.setLines(withLines ? lines : null);
		return view;
	}

	/** The store's own price of the item with its VAT (unit price x (1 + VAT / 100)), 3 decimals; null without a price. */
	static Double sellingPrice(Item item) {
		if (item.getUnitPrice() == null) {
			return null;
		}
		BigDecimal rate = BigDecimal.ONE
				.add(BigDecimal.valueOf(item.getDefaultVAT() == null ? 0 : item.getDefaultVAT()).movePointLeft(2));
		return BigDecimal.valueOf(item.getUnitPrice()).multiply(rate).setScale(3, RoundingMode.HALF_UP).doubleValue();
	}

	private static BigDecimal total(ReceivedDelivery delivery, boolean received) {
		BigDecimal total = BigDecimal.ZERO;
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			BigDecimal quantity = received ? line.getQuantityReceived() : line.getQuantitySent();
			total = total.add(quantity == null ? BigDecimal.ZERO : quantity);
		}
		return Quantities.normalize(total);
	}

	static String cut(String text, int length) {
		return text == null || text.length() <= length ? text : text.substring(0, length);
	}
}
