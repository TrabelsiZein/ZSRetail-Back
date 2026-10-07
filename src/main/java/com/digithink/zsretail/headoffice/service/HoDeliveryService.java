package com.digithink.zsretail.headoffice.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.config.ConditionalOnHeadOfficeWithoutErp;
import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryCopyDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryInputDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.enumeration.DeliveryStatus;
import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.headoffice.model.HoDeliveryLine;
import com.digithink.zsretail.headoffice.model.HoNumberSequence;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoDeliveryRepository;
import com.digithink.zsretail.headoffice.repository.HoNumberSequenceRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.service.StockMovementService;
import com.digithink.zsretail.service.StockService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 7A.2: the BLs (delivery notes) of a head office without an ERP, and the SUPPLY domain of the
 * copies down. A draft BL (one store, items of the head office, quantities) is edited and deleted freely. Validating it,
 * all or nothing in one transaction: the BL row is locked, the stock of every line is checked (unless
 * ALLOW_NEGATIVE_STOCK=true), the BL gets its number (BL-000001), becomes SENT, each line leaves the head office stock
 * with one DELIVERY_OUT movement, and the BL is recorded for its store only (domain SUPPLY, record BL:&lt;number&gt;).
 * No item save: no CATALOGUE change. See docs/modules/head-office.md, "BLs".
 * <p>
 * A head office without stock (headoffice.stock.enabled=false): validating checks no stock, moves no stock and writes no
 * movement; a line gives no head office stock (null). Everything else is the same.
 */
@Service
@ConditionalOnHeadOfficeWithoutErp
@Log4j2
public class HoDeliveryService implements DownDomainProvider {

	static final String SEQUENCE_CODE = "BL";
	static final String NUMBER_FORMAT = "BL-%06d";
	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 200;
	static final LocalDate NO_DATE_FROM = LocalDate.of(1900, 1, 1);
	static final LocalDate NO_DATE_TO = LocalDate.of(9999, 12, 31);

	static final ObjectMapper COPY_MAPPER = new ObjectMapper();

	private final HoDeliveryRepository deliveries;
	private final StoreRepository stores;
	private final ItemRepository items;
	private final HoNumberSequenceRepository sequences;
	private final StockService stock;
	private final StockMovementService movements;
	private final Supplier<CopiesDownFeed> feed;
	private final TransactionOperations writeTransactions;
	private final Supplier<LocalDateTime> clock;

	/** Step 7B: the invoices (records INV: of the same domain, the per-BL invoice); null in the 7A tests. */
	private final Supplier<HoSupplyInvoiceService> invoices;

	/** False on a head office with headoffice.stock.enabled=false: a BL checks and moves no stock. */
	private final boolean keepsStock;

	@Autowired
	public HoDeliveryService(HoDeliveryRepository deliveries, StoreRepository stores, ItemRepository items,
			HoNumberSequenceRepository sequences, StockService stock, StockMovementService movements,
			ObjectProvider<CopiesDownFeed> feed, ObjectProvider<HoSupplyInvoiceService> invoices,
			PlatformTransactionManager transactionManager, ApplicationModeService mode) {
		this(deliveries, stores, items, sequences, stock, movements, (Supplier<CopiesDownFeed>) feed::getObject,
				new TransactionTemplate(transactionManager), LocalDateTime::now,
				(Supplier<HoSupplyInvoiceService>) invoices::getIfAvailable, !mode.isHeadOfficeWithoutStock());
	}

	/** With given collaborators, transactions and clock, without invoices: used by the tests. */
	public HoDeliveryService(HoDeliveryRepository deliveries, StoreRepository stores, ItemRepository items,
			HoNumberSequenceRepository sequences, StockService stock, StockMovementService movements,
			Supplier<CopiesDownFeed> feed, TransactionOperations writeTransactions, Supplier<LocalDateTime> clock) {
		this(deliveries, stores, items, sequences, stock, movements, feed, writeTransactions, clock, () -> null);
	}

	/** With given collaborators, transactions, clock and invoices, the head office keeping its stock: used by the tests. */
	public HoDeliveryService(HoDeliveryRepository deliveries, StoreRepository stores, ItemRepository items,
			HoNumberSequenceRepository sequences, StockService stock, StockMovementService movements,
			Supplier<CopiesDownFeed> feed, TransactionOperations writeTransactions, Supplier<LocalDateTime> clock,
			Supplier<HoSupplyInvoiceService> invoices) {
		this(deliveries, stores, items, sequences, stock, movements, feed, writeTransactions, clock, invoices, true);
	}

	/** With given collaborators, transactions, clock, invoices, and whether the head office keeps its stock. */
	public HoDeliveryService(HoDeliveryRepository deliveries, StoreRepository stores, ItemRepository items,
			HoNumberSequenceRepository sequences, StockService stock, StockMovementService movements,
			Supplier<CopiesDownFeed> feed, TransactionOperations writeTransactions, Supplier<LocalDateTime> clock,
			Supplier<HoSupplyInvoiceService> invoices, boolean keepsStock) {
		this.keepsStock = keepsStock;
		this.invoices = invoices;
		this.deliveries = deliveries;
		this.stores = stores;
		this.items = items;
		this.sequences = sequences;
		this.stock = stock;
		this.movements = movements;
		this.feed = feed;
		this.writeTransactions = writeTransactions;
		this.clock = clock;
	}

	/** At the start: the BL sequence row exists, so two first validations never both create it. */
	@EventListener(ApplicationReadyEvent.class)
	public void initialise() {
		try {
			writeTransactions.executeWithoutResult(status -> {
				if (sequences.lastValue(SEQUENCE_CODE).isEmpty()) {
					sequences.save(newSequence(0));
				}
			});
		} catch (RuntimeException e) {
			log.warn("Head office BLs: the BL sequence could not be created at the start ({}); it is created with the"
					+ " first BL", e.getMessage());
		}
	}

	// ─── Reads ───────────────────────────────────────────────────

	/**
	 * The BLs page: {content, totalElements, totalPages, number, size}, newest first. storeId null or 0: every store;
	 * status blank or "all": every status; search on the number and the note; dates yyyy-MM-dd on the document date;
	 * difference: only the BLs received with a difference. 400 (IllegalArgument) for a status, a date or a page that
	 * cannot be read.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> list(Long storeId, String status, String search, String dateFrom, String dateTo,
			Boolean difference, Integer page, Integer size) {
		DeliveryStatus wanted = parseStatus(status);
		int pageNumber = page == null ? 0 : page;
		if (pageNumber < 0) {
			throw new IllegalArgumentException("page must be 0 or more");
		}
		int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, size));
		String like = search == null || search.trim().isEmpty() ? null : "%" + search.trim().toLowerCase() + "%";
		Page<HoDelivery> result = deliveries.findPage(storeId == null ? 0L : storeId, wanted == null ? 1L : 0L,
				wanted == null ? DeliveryStatus.DRAFT : wanted, like, parseDate("dateFrom", dateFrom, NO_DATE_FROM),
				parseDate("dateTo", dateTo, NO_DATE_TO), Boolean.TRUE.equals(difference) ? 1L : 0L,
				PageRequest.of(pageNumber, pageSize));
		Map<Long, Store> byId = storesById();
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content",
				result.getContent().stream().map(d -> view(d, byId.get(d.getStoreId()), false, false)).collect(Collectors.toList()));
		answer.put("totalElements", result.getTotalElements());
		answer.put("totalPages", result.getTotalPages());
		answer.put("number", result.getNumber());
		answer.put("size", result.getSize());
		return answer;
	}

	/** One BL with its lines and the head office stock of each item; empty when unknown. */
	@Transactional(readOnly = true)
	public Optional<DeliveryDTO> get(Long id) {
		return deliveries.findById(id).map(d -> view(d, stores.findById(d.getStoreId()).orElse(null), true, true));
	}

	// ─── Drafts ──────────────────────────────────────────────────

	/** A new draft. 400 (IllegalArgument) for invalid data; 409 (IllegalState) for a store that does not receive BLs. */
	@Transactional(rollbackFor = Exception.class)
	public DeliveryDTO create(DeliveryInputDTO input) {
		HoDelivery delivery = new HoDelivery();
		delivery.setStatus(DeliveryStatus.DRAFT);
		fill(delivery, input);
		HoDelivery saved = deliveries.save(delivery);
		return view(saved, stores.findById(saved.getStoreId()).orElse(null), true, true);
	}

	/** The draft replaced by the input (store, date, note, lines). Empty when unknown; 409 when it is not a draft. */
	@Transactional(rollbackFor = Exception.class)
	public Optional<DeliveryDTO> update(Long id, DeliveryInputDTO input) {
		Optional<HoDelivery> found = deliveries.findForUpdate(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		HoDelivery delivery = found.get();
		requireDraft(delivery, "changed");
		// The old lines are deleted first: the new ones reuse their line numbers (uk_ho_delivery_line) and Hibernate inserts
		// before it deletes within one flush (L2 of step 7A: every edit of a draft answered 500)
		delivery.getLines().clear();
		deliveries.flush();
		fill(delivery, input);
		HoDelivery saved = deliveries.save(delivery);
		return Optional.of(view(saved, stores.findById(saved.getStoreId()).orElse(null), true, true));
	}

	/** False when unknown; 409 when it is not a draft. */
	@Transactional(rollbackFor = Exception.class)
	public boolean delete(Long id) {
		Optional<HoDelivery> found = deliveries.findForUpdate(id);
		if (!found.isPresent()) {
			return false;
		}
		requireDraft(found.get(), "deleted");
		deliveries.delete(found.get());
		return true;
	}

	// ─── Validation ──────────────────────────────────────────────

	/**
	 * Draft to SENT, all or nothing: see the class comment. Empty when unknown. 409 (IllegalState) when it is not a draft,
	 * when the store does not receive BLs, or when the stock is not sufficient (each short item listed: "B001: 20 in
	 * stock, 50 on the BL"); 400 when a line's item is no longer deliverable.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Optional<DeliveryDTO> validate(Long id, String user) {
		Optional<HoDelivery> found = deliveries.findForUpdate(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		HoDelivery delivery = found.get();
		requireDraft(delivery, "validated");
		Store store = checkStore(delivery.getStoreId());
		// Without stock (headoffice.stock.enabled=false): no check, ALLOW_NEGATIVE_STOCK is not read
		boolean checkStock = keepsStock && !stock.isNegativeStockAllowed();
		List<String> shortages = new ArrayList<>();
		for (HoDeliveryLine line : delivery.getLines()) {
			Item item = items.findById(line.getItemId()).orElseThrow(() -> new IllegalArgumentException(
					"Line " + line.getLineNo() + ": the item " + line.getItemCode() + " no longer exists."));
			checkDeliverable(line.getLineNo(), item);
			int inStock = item.getStockQuantity() == null ? 0 : item.getStockQuantity();
			if (checkStock && inStock < line.getQuantitySent()) {
				shortages.add(shortage(item.getItemCode(), inStock, line.getQuantitySent()));
			}
		}
		if (!shortages.isEmpty()) {
			throw new IllegalStateException(shortageMessage(shortages));
		}
		String number = nextNumber();
		delivery.setNumber(number);
		delivery.setStatus(DeliveryStatus.SENT);
		delivery.setSentAt(clock.get());
		delivery.setSentBy(cut(user, HoDelivery.USER_LENGTH));
		HoDelivery saved = deliveries.save(delivery);
		if (keepsStock) { // without stock: no decrease, no movement
			for (HoDeliveryLine line : saved.getLines()) {
				// Atomic: a stock taken meanwhile by another validation is caught here, and everything rolls back
				if (!stock.decrementForDelivery(line.getItemId(), line.getQuantitySent())) {
					int now = items.findById(line.getItemId())
							.map(i -> i.getStockQuantity() == null ? 0 : i.getStockQuantity()).orElse(0);
					throw new IllegalStateException(shortageMessage(
							Collections.singletonList(shortage(line.getItemCode(), now, line.getQuantitySent()))));
				}
				movements.recordDeliveryOut(line.getItemId(), line.getQuantitySent(), saved.getId(), number);
			}
		}
		feed.get().recordChange(DataDomain.SUPPLY, DeliveryCopyDTO.recordCode(number),
				StoreTargets.of(Collections.singletonList(saved.getStoreId())));
		log.info("Head office BL {} sent to store {}: {} lines", number, store.getCode(), saved.getLines().size());
		return Optional.of(view(saved, store, true, false)); // the stock was changed by native updates: GET /{id} reads it
	}

	private static String shortage(String itemCode, int inStock, int onBl) {
		return itemCode + ": " + inStock + " in stock, " + onBl + " on the BL";
	}

	private static String shortageMessage(List<String> shortages) {
		return "Stock not sufficient at the head office: " + String.join("; ", shortages) + ".";
	}

	private String nextNumber() {
		long value;
		if (sequences.increment(SEQUENCE_CODE) == 0) {
			sequences.save(newSequence(1));
			value = 1;
		} else {
			value = sequences.lastValue(SEQUENCE_CODE).get(0);
		}
		return String.format(NUMBER_FORMAT, value);
	}

	private static HoNumberSequence newSequence(long lastValue) {
		HoNumberSequence sequence = new HoNumberSequence();
		sequence.setCode(SEQUENCE_CODE);
		sequence.setLastValue(lastValue);
		return sequence;
	}

	// ─── Confirmations up (task 7A.4) ────────────────────────────

	/**
	 * A store's confirmations (POST /ho/supply/confirmations), each in its own transaction, one result per BL in batch
	 * order. A SENT BL of this store becomes RECEIVED with the confirmed quantities (the difference with the quantities
	 * sent is kept; the head office stock is not changed: the goods left at the validation). A BL already received with
	 * the same quantities is accepted again and nothing changes (a confirmation sent again after a lost answer); with
	 * other quantities it is rejected. A BL of another store, unknown or still a draft is rejected as unknown.
	 */
	public List<SalesCopyResultDTO> receiveConfirmations(Store store, List<DeliveryConfirmationDTO> confirmations) {
		List<SalesCopyResultDTO> results = new ArrayList<>();
		if (confirmations == null) {
			return results;
		}
		int accepted = 0;
		List<Long> newlyReceived = new ArrayList<>();
		for (DeliveryConfirmationDTO confirmation : confirmations) {
			String number = confirmation == null ? null : confirmation.getNumber();
			SalesCopyResultDTO result;
			int before = newlyReceived.size();
			try {
				result = writeTransactions.execute(status -> receiveOne(store, confirmation, newlyReceived));
			} catch (RuntimeException e) {
				newlyReceived.subList(before, newlyReceived.size()).clear(); // not committed: no invoice
				result = SalesCopyResultDTO.rejected(number, cut(causeOf(e), 500));
			}
			if (result.isAccepted()) {
				accepted++;
			} else {
				log.warn("Head office BLs: confirmation of {} from store '{}' rejected: {}", number, store.getCode(),
						result.getMessage());
			}
			results.add(result);
		}
		log.info("Head office BLs: {} confirmations received from store '{}' ({} accepted, {} rejected)",
				confirmations.size(), store.getCode(), accepted, confirmations.size() - accepted);
		HoSupplyInvoiceService invoiceService = invoices.get();
		if (invoiceService != null) { // step 7B: rhythm PER_BL, each in its own transaction after the confirmation
			for (Long id : newlyReceived) {
				invoiceService.afterReceived(store.getId(), id);
			}
		}
		return results;
	}

	private SalesCopyResultDTO receiveOne(Store store, DeliveryConfirmationDTO confirmation, List<Long> newlyReceived) {
		if (confirmation == null || confirmation.getNumber() == null || confirmation.getNumber().trim().isEmpty()) {
			return SalesCopyResultDTO.rejected(null, "number is required");
		}
		String number = confirmation.getNumber().trim();
		Optional<HoDelivery> found = deliveries.findForUpdateByStoreAndNumber(store.getId(), number);
		if (!found.isPresent() || found.get().getStatus() == DeliveryStatus.DRAFT) {
			return SalesCopyResultDTO.rejected(number, "unknown BL " + number + " for this store");
		}
		HoDelivery delivery = found.get();
		Map<Integer, Integer> received = new HashMap<>();
		for (DeliveryConfirmationDTO.Line line : confirmation.getLines() == null
				? Collections.<DeliveryConfirmationDTO.Line>emptyList()
				: confirmation.getLines()) {
			HoDeliveryLine sent = line == null || line.getLineNo() == null ? null
					: delivery.getLines().stream().filter(l -> l.getLineNo().equals(line.getLineNo())).findFirst()
							.orElse(null);
			if (sent == null || (line.getItemCode() != null && !line.getItemCode().equals(sent.getItemCode()))) {
				return SalesCopyResultDTO.rejected(number,
						"line " + (line == null ? null : line.getLineNo()) + " does not match the BL");
			}
			if (line.getQuantityReceived() == null || line.getQuantityReceived() < 0) {
				return SalesCopyResultDTO.rejected(number,
						"line " + line.getLineNo() + ": quantityReceived must be 0 or more");
			}
			if (received.put(line.getLineNo(), line.getQuantityReceived()) != null) {
				return SalesCopyResultDTO.rejected(number, "line " + line.getLineNo() + " is given twice");
			}
		}
		if (received.size() != delivery.getLines().size()) {
			return SalesCopyResultDTO.rejected(number, "every line of the BL is required");
		}
		if (delivery.getStatus() != DeliveryStatus.SENT) {
			boolean same = delivery.getLines().stream()
					.allMatch(l -> received.get(l.getLineNo()).equals(l.getQuantityReceived()));
			return same ? SalesCopyResultDTO.accepted(number)
					: SalesCopyResultDTO.rejected(number, "already received with other quantities");
		}
		for (HoDeliveryLine line : delivery.getLines()) {
			line.setQuantityReceived(received.get(line.getLineNo()));
		}
		delivery.setStatus(DeliveryStatus.RECEIVED);
		delivery.setReceivedAt(parseDateTime(confirmation.getReceivedAt()));
		delivery.setReceivedBy(cut(confirmation.getReceivedBy(), HoDelivery.USER_LENGTH));
		delivery.setStoreNote(cut(confirmation.getNote(), HoDelivery.NOTE_LENGTH));
		delivery.setConfirmationReceivedAt(clock.get());
		deliveries.save(delivery);
		newlyReceived.add(delivery.getId());
		return SalesCopyResultDTO.accepted(number);
	}

	private static LocalDateTime parseDateTime(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		try {
			return LocalDateTime.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException("receivedAt must be a date and time as yyyy-MM-ddTHH:mm:ss");
		}
	}

	/** The most specific cause of a failure, for the result message. */
	private static String causeOf(RuntimeException e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause instanceof IllegalArgumentException ? cause.getMessage()
				: cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}

	// ─── Copies down (domain SUPPLY) ─────────────────────────────

	@Override
	public DataDomain getDomain() {
		return DataDomain.SUPPLY;
	}

	/** The numbered BLs of this store among the codes; a BL of another store is never answered (removed). */
	@Override
	public Map<String, JsonNode> load(Store store, List<String> codes) {
		List<String> numbers = codes.stream().map(DeliveryCopyDTO::numberOf).filter(n -> n != null)
				.collect(Collectors.toList());
		Map<String, JsonNode> copies = new HashMap<>();
		if (!numbers.isEmpty()) {
			for (HoDelivery delivery : deliveries.findSent(store.getId(), numbers, DeliveryStatus.DRAFT)) {
				copies.put(DeliveryCopyDTO.recordCode(delivery.getNumber()),
						COPY_MAPPER.valueToTree(DeliveryCopyDTO.of(delivery)));
			}
		}
		HoSupplyInvoiceService invoiceService = invoices.get();
		if (invoiceService != null) {
			copies.putAll(invoiceService.load(store, codes)); // step 7B: records INV:
		}
		return copies;
	}

	@Override
	public Map<String, StoreTargets> currentTargets() {
		Map<String, StoreTargets> targets = new LinkedHashMap<>();
		for (Object[] row : deliveries.findSentTargets(DeliveryStatus.DRAFT)) {
			targets.put(DeliveryCopyDTO.recordCode((String) row[0]),
					StoreTargets.of(Collections.singletonList(((Number) row[1]).longValue())));
		}
		HoSupplyInvoiceService invoiceService = invoices.get();
		if (invoiceService != null) {
			targets.putAll(invoiceService.currentTargets()); // step 7B
		}
		return targets;
	}

	// ─── Rules ───────────────────────────────────────────────────

	private void fill(HoDelivery delivery, DeliveryInputDTO input) {
		if (input == null) {
			throw new IllegalArgumentException("The BL is missing.");
		}
		Store store = checkStore(input.getStoreId());
		delivery.setStoreId(store.getId());
		delivery.setDocumentDate(parseDate("documentDate", input.getDocumentDate(), clock.get().toLocalDate()));
		String note = input.getNote() == null || input.getNote().trim().isEmpty() ? null : input.getNote().trim();
		if (note != null && note.length() > HoDelivery.NOTE_LENGTH) {
			throw new IllegalArgumentException("The note is longer than " + HoDelivery.NOTE_LENGTH + " characters.");
		}
		delivery.setNote(note);
		List<DeliveryInputDTO.Line> lines = input.getLines() == null ? Collections.emptyList() : input.getLines();
		if (lines.isEmpty()) {
			throw new IllegalArgumentException("A BL needs at least one line.");
		}
		List<HoDeliveryLine> built = new ArrayList<>();
		Map<Long, Integer> lineOfItem = new HashMap<>();
		int lineNo = 0;
		for (DeliveryInputDTO.Line input1 : lines) {
			lineNo++;
			Item item = itemOf(lineNo, input1);
			checkDeliverable(lineNo, item);
			Integer earlier = lineOfItem.putIfAbsent(item.getId(), lineNo);
			if (earlier != null) {
				throw new IllegalArgumentException("Line " + lineNo + ": the item " + item.getItemCode()
						+ " is already on line " + earlier + ".");
			}
			if (input1.getQuantity() == null || input1.getQuantity() <= 0) {
				throw new IllegalArgumentException("Line " + lineNo + ": the quantity must be a whole number above 0.");
			}
			HoDeliveryLine line = new HoDeliveryLine();
			line.setDelivery(delivery);
			line.setLineNo(lineNo);
			line.setItemId(item.getId());
			line.setItemCode(item.getItemCode());
			line.setItemName(item.getName());
			line.setQuantitySent(input1.getQuantity());
			built.add(line);
		}
		delivery.getLines().clear();
		delivery.getLines().addAll(built);
	}

	private Item itemOf(int lineNo, DeliveryInputDTO.Line line) {
		if (line.getItemId() != null) {
			return items.findById(line.getItemId()).orElseThrow(
					() -> new IllegalArgumentException("Line " + lineNo + ": unknown item id " + line.getItemId() + "."));
		}
		String code = line.getItemCode() == null ? "" : line.getItemCode().trim();
		if (code.isEmpty()) {
			throw new IllegalArgumentException("Line " + lineNo + ": choose an item.");
		}
		return items.findByItemCode(code)
				.orElseThrow(() -> new IllegalArgumentException("Line " + lineNo + ": unknown item " + code + "."));
	}

	/** Active, a product or a pack (services and discounts have no stock), never the tax stamp. */
	private static void checkDeliverable(int lineNo, Item item) {
		if (CatalogueKind.TAX_STAMP_CODE.equals(item.getItemCode())) {
			throw new IllegalArgumentException("Line " + lineNo + ": the tax stamp cannot be delivered.");
		}
		if (Boolean.FALSE.equals(item.getActive())) {
			throw new IllegalArgumentException("Line " + lineNo + ": the item " + item.getItemCode() + " is inactive.");
		}
		ItemType type = item.getType() == null ? ItemType.PRODUCT : item.getType();
		if (type != ItemType.PRODUCT && type != ItemType.PACKAGE) {
			throw new IllegalArgumentException("Line " + lineNo + ": the item " + item.getItemCode() + " is a "
					+ type.name().toLowerCase() + ": it has no stock.");
		}
	}

	/**
	 * The store of a BL: known and active (400 otherwise); 409 when it reported that its goods do not come from the
	 * head office (ownership.supply other than HEAD_OFFICE). A store that has not reported yet is accepted.
	 */
	private Store checkStore(Long storeId) {
		if (storeId == null) {
			throw new IllegalArgumentException("Choose the store of the BL.");
		}
		Store store = stores.findById(storeId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown store id " + storeId + "."));
		if (Boolean.FALSE.equals(store.getActive())) {
			throw new IllegalArgumentException("The store " + store.getCode() + " is inactive.");
		}
		String supply = store.getOwnerSupply();
		if (supply != null && !DataOwner.HEAD_OFFICE.name().equals(supply)) {
			throw new IllegalStateException("The store " + store.getCode()
					+ " does not receive goods from the head office (ownership.supply=" + supply + " in its settings).");
		}
		return store;
	}

	private static void requireDraft(HoDelivery delivery, String action) {
		if (delivery.getStatus() != DeliveryStatus.DRAFT) {
			throw new IllegalStateException("Only a draft BL can be " + action + ": " + delivery.getNumber() + " is "
					+ delivery.getStatus() + ".");
		}
	}

	private static DeliveryStatus parseStatus(String status) {
		if (status == null || status.trim().isEmpty() || "all".equalsIgnoreCase(status.trim())) {
			return null;
		}
		try {
			return DeliveryStatus.valueOf(status.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid status '" + status + "': allowed values are all, "
					+ java.util.Arrays.toString(DeliveryStatus.values()));
		}
	}

	private static LocalDate parseDate(String name, String value, LocalDate absent) {
		if (value == null || value.trim().isEmpty()) {
			return absent;
		}
		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(name + " must be a date as yyyy-MM-dd.");
		}
	}

	private Map<Long, Store> storesById() {
		Map<Long, Store> byId = new HashMap<>();
		for (Store store : stores.findAll()) {
			byId.put(store.getId(), store);
		}
		return byId;
	}

	private DeliveryDTO view(HoDelivery delivery, Store store, boolean withLines, boolean withStock) {
		DeliveryDTO view = new DeliveryDTO();
		view.setId(delivery.getId());
		view.setNumber(delivery.getNumber());
		view.setStoreId(delivery.getStoreId());
		view.setStoreCode(store == null ? null : store.getCode());
		view.setStoreName(store == null ? null : store.getName());
		view.setStatus(delivery.getStatus().name());
		view.setDocumentDate(delivery.getDocumentDate());
		view.setSentAt(delivery.getSentAt());
		view.setSentBy(delivery.getSentBy());
		view.setReceivedAt(delivery.getReceivedAt());
		view.setReceivedBy(delivery.getReceivedBy());
		view.setConfirmationReceivedAt(delivery.getConfirmationReceivedAt());
		view.setNote(delivery.getNote());
		view.setStoreNote(delivery.getStoreNote());
		view.setInvoiceId(delivery.getInvoiceId());
		view.setInvoiceNote(delivery.getInvoiceNote());
		HoSupplyInvoiceService invoiceService = invoices.get();
		if (delivery.getInvoiceId() != null && invoiceService != null) {
			view.setInvoiceNumber(invoiceService.numberOf(delivery.getInvoiceId()));
		}
		int sent = 0;
		Integer received = null;
		boolean difference = false;
		List<DeliveryDTO.Line> lines = new ArrayList<>();
		for (HoDeliveryLine line : delivery.getLines()) {
			sent += line.getQuantitySent();
			if (line.getQuantityReceived() != null) {
				received = (received == null ? 0 : received) + line.getQuantityReceived();
				difference |= !line.getQuantityReceived().equals(line.getQuantitySent());
			}
			if (withLines) {
				DeliveryDTO.Line row = new DeliveryDTO.Line();
				row.setLineNo(line.getLineNo());
				row.setItemId(line.getItemId());
				row.setItemCode(line.getItemCode());
				row.setItemName(line.getItemName());
				row.setQuantitySent(line.getQuantitySent());
				row.setQuantityReceived(line.getQuantityReceived());
				row.setDifference(line.getQuantityReceived() == null ? null
						: line.getQuantityReceived() - line.getQuantitySent());
				row.setHeadOfficeStock(!withStock || !keepsStock ? null : items.findById(line.getItemId())
						.map(i -> i.getStockQuantity() == null ? 0 : i.getStockQuantity()).orElse(null));
				lines.add(row);
			}
		}
		view.setLineCount(delivery.getLines().size());
		view.setQuantitySent(sent);
		view.setQuantityReceived(received);
		view.setDifference(difference);
		view.setLines(withLines ? lines : null);
		return view;
	}

	static String cut(String text, int length) {
		return text == null || text.length() <= length ? text : text.substring(0, length);
	}
}
