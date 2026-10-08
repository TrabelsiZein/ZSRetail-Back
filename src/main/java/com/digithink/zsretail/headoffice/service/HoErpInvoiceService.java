package com.digithink.zsretail.headoffice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErpSupply;
import com.digithink.zsretail.erp.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.service.ErpSynchronizationManager;
import com.digithink.zsretail.erp.spi.ErpSupplyInvoiceImport;
import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.ErpInvoiceCopyDTO;
import com.digithink.zsretail.headoffice.dto.ErpInvoiceDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceLineType;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceMapping;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceStatus;
import com.digithink.zsretail.headoffice.model.HoErpInvoice;
import com.digithink.zsretail.headoffice.model.HoErpInvoiceLine;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoErpInvoiceRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.repository.ItemRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

/**
 * Invoices from the ERP, step (b): on a head office with headoffice.supply.source=ERP, the ERP's invoices to the
 * franchise stores replace the head office's BLs and invoices. One run (the ERP job IMPORT_SUPPLY_INVOICES):
 * <ol>
 * <li>{@link #readNew}: the invoices after the head office's highest number of each year prefix (the connector starts a
 * year without one at its start number), each saved in its own transaction without a store. A number already here is
 * skipped (a run now beside the scheduler). The item codes not in the head office catalogue are listed in the warnings;
 * an invoice with a quantity not whole or prices including the VAT is held. The first unexpected failure stops the saving:
 * the next run reads again after the highest number saved, so no invoice is passed over.</li>
 * <li>{@link #assignStores}: each invoice without a store and not held gets the store whose ERP customer number is its
 * customer, active and receiving from the head office (ownership.supply null or HEAD_OFFICE); otherwise its
 * mapping_status says why, and the next run (or {@link #matchStoresNow}) tries again. Nothing is tied to saving a store.</li>
 * </ol>
 * Step (c): an assigned invoice goes to its store only (copies down, domain SUPPLY, record ERPINV:&lt;number&gt;,
 * {@link ErpInvoiceCopyDTO}): recorded when its store is found ({@link #afterAssigned}), answered by {@link #load}, and
 * the invoices assigned before are recorded by the startup backfill ({@link #currentTargets}). The store's confirmations
 * come back through {@link #receiveConfirmations}. See docs/modules/head-office.md, "Invoices from the ERP".
 */
@Service
@ConditionalOnHeadOfficeErpSupply
@Log4j2
public class HoErpInvoiceService implements DownDomainProvider, SupplyConfirmationReceiver, ErpSupplyInvoiceImport {

	static final ObjectMapper COPY_MAPPER = new ObjectMapper();

	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 200;
	static final int COST_SCALE = 5;
	static final int DESCRIPTION_LENGTH = 255;
	static final String WARNING_SEPARATOR = "\n";
	public static final String SELLER_NAME_KEY = "erp.navpospages.invoices.seller-name";
	public static final String DEFAULT_SELLER_NAME = "Head office";
	/** Step (c): an invoice goes down once its store is found, and stays readable after the confirmation. */
	static final List<ErpInvoiceStatus> COPIED_STATUSES = java.util.Arrays.asList(ErpInvoiceStatus.SENT,
			ErpInvoiceStatus.RECEIVED);

	private final HoErpInvoiceRepository invoices;
	private final StoreRepository stores;
	private final ItemRepository items;
	/** The ERP read: the invoices after the highest number of each year prefix (communications log included). */
	private final Function<Map<String, String>, List<ErpSupplyInvoiceDTO>> reader;
	private final TransactionOperations writeTransactions;
	private final Supplier<LocalDateTime> clock;

	/** Step (c): the copies down, where an assigned invoice is recorded for its store; null in the step (b) tests. */
	private final Supplier<CopiesDownFeed> feed;

	/** Step (c): the seller named on the stores' purchase invoices (erp.navpospages.invoices.seller-name). */
	private final String sellerName;

	/** The summary of the last run, for the page and run now; null before the first run since the start. */
	private volatile Map<String, Object> lastRun;

	@Autowired
	public HoErpInvoiceService(HoErpInvoiceRepository invoices, StoreRepository stores, ItemRepository items,
			ErpSynchronizationManager erp, PlatformTransactionManager transactionManager,
			ObjectProvider<CopiesDownFeed> feed,
			@Value("${" + SELLER_NAME_KEY + ":" + DEFAULT_SELLER_NAME + "}") String sellerName) {
		this(invoices, stores, items, erp::pullSupplyInvoices, new TransactionTemplate(transactionManager),
				LocalDateTime::now, (Supplier<CopiesDownFeed>) feed::getObject, sellerName);
	}

	/** With a given ERP read, transactions and clock, without the copies down (step b): used by the tests. */
	public HoErpInvoiceService(HoErpInvoiceRepository invoices, StoreRepository stores, ItemRepository items,
			Function<Map<String, String>, List<ErpSupplyInvoiceDTO>> reader, TransactionOperations writeTransactions,
			Supplier<LocalDateTime> clock) {
		this(invoices, stores, items, reader, writeTransactions, clock, null, DEFAULT_SELLER_NAME);
	}

	/** With a given ERP read, transactions, clock, copies down and seller name: used by the tests. */
	public HoErpInvoiceService(HoErpInvoiceRepository invoices, StoreRepository stores, ItemRepository items,
			Function<Map<String, String>, List<ErpSupplyInvoiceDTO>> reader, TransactionOperations writeTransactions,
			Supplier<LocalDateTime> clock, Supplier<CopiesDownFeed> feed, String sellerName) {
		this.invoices = invoices;
		this.stores = stores;
		this.items = items;
		this.reader = reader;
		this.writeTransactions = writeTransactions;
		this.clock = clock;
		this.feed = feed;
		this.sellerName = sellerName == null || sellerName.trim().isEmpty() ? DEFAULT_SELLER_NAME : sellerName.trim();
	}

	// ─── One run (the ERP job) ───────────────────────────────────

	@Override
	public void importSupplyInvoices() {
		run();
	}

	/**
	 * Step 1 then step 2. The summary: read, saved, skipped, held, assigned, unassigned (by reason); kept for the page.
	 * Step 2 runs even when step 1 failed (the ERP not answering). After step 2, the failure of step 1 is thrown again,
	 * or an IllegalStateException when an invoice could not be saved: the job ends in error and the invoice is read again
	 * at the next run.
	 */
	public Map<String, Object> run() {
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("at", clock.get().toString());
		String failure = null;
		RuntimeException readFailure = null;
		try {
			failure = readNew(summary);
		} catch (RuntimeException e) {
			readFailure = e;
			summary.put("readFailed", causeOf(e));
		}
		summary.putAll(assignStores());
		if (failure != null) {
			summary.put("failed", failure);
		}
		lastRun = Collections.unmodifiableMap(summary);
		log.info("Head office invoices from the ERP: {}", summary);
		if (readFailure != null) {
			throw readFailure;
		}
		if (failure != null) {
			throw new IllegalStateException("Invoices from the ERP: " + failure);
		}
		return lastRun;
	}

	/** The summary of the last run since the start; null before it. */
	public Map<String, Object> getLastRun() {
		return lastRun;
	}

	/** [year prefix -> highest number here]: where each year's read goes on. */
	Map<String, String> highestByYear() {
		Map<String, String> highest = new HashMap<>();
		for (Object[] row : invoices.findHighestByYear()) {
			highest.put((String) row[0], (String) row[1]);
		}
		return highest;
	}

	/** Step 1 into the summary; the reason of the first unexpected failure, or null. */
	private String readNew(Map<String, Object> summary) {
		List<ErpSupplyInvoiceDTO> read = reader.apply(highestByYear());
		read = read == null ? new ArrayList<>() : read;
		int saved = 0;
		int skipped = 0;
		int held = 0;
		String failure = null;
		for (ErpSupplyInvoiceDTO invoice : read) {
			String number = invoice == null || invoice.getNumber() == null ? "" : invoice.getNumber().trim();
			if (number.isEmpty()) {
				skipped++;
				continue;
			}
			Boolean outcome;
			try {
				outcome = writeTransactions.execute(status -> saveOne(invoice, number));
			} catch (DataIntegrityViolationException e) {
				if (invoices.existsByBcNumber(number)) {
					skipped++; // saved meanwhile by another run
					continue;
				}
				failure = number + " not saved (" + causeOf(e) + ")";
				break;
			} catch (RuntimeException e) {
				failure = number + " not saved (" + causeOf(e) + ")";
				break;
			}
			if (outcome == null) {
				skipped++;
			} else {
				saved++;
				held += outcome ? 1 : 0;
			}
		}
		summary.put("read", read.size());
		summary.put("saved", saved);
		summary.put("skipped", skipped);
		summary.put("held", held);
		return failure;
	}

	/** Null when the number is already here; otherwise saved, true when held. */
	private Boolean saveOne(ErpSupplyInvoiceDTO source, String number) {
		if (invoices.existsByBcNumber(number)) {
			return null;
		}
		HoErpInvoice invoice = build(source, number);
		invoices.saveAndFlush(invoice); // a number saved meanwhile fails here (uk_ho_erp_invoice_number)
		return invoice.getHeld();
	}

	/** The invoice as read: lines, costs, items of the head office, warnings and hold reasons. */
	HoErpInvoice build(ErpSupplyInvoiceDTO source, String number) {
		HoErpInvoice invoice = new HoErpInvoice();
		invoice.setBcNumber(number);
		invoice.setYearPrefix(source.getYearPrefix());
		invoice.setDocumentDate(source.getDocumentDate());
		invoice.setPostingDate(source.getPostingDate());
		invoice.setCustomerNo(cut(blankToNull(source.getCustomerNo()), HoErpInvoice.CUSTOMER_NO_LENGTH));
		invoice.setCustomerName(cut(blankToNull(source.getCustomerName()), HoErpInvoice.CUSTOMER_NAME_LENGTH));
		invoice.setTotalExclVat(amount(source.getTotalExclVat()));
		invoice.setTotalVat(amount(source.getTotalVat()));
		invoice.setTotalInclVat(amount(source.getTotalInclVat()));
		invoice.setStatus(ErpInvoiceStatus.READ);
		invoice.setReadAt(clock.get());

		List<ErpSupplyInvoiceDTO.Line> sourceLines = source.getLines() == null ? new ArrayList<>() : source.getLines();
		Set<String> codes = new LinkedHashSet<>();
		for (ErpSupplyInvoiceDTO.Line line : sourceLines) {
			if (line.getType() == ErpSupplyInvoiceDTO.LineType.ITEM && line.getItemCode() != null) {
				codes.add(line.getItemCode().trim());
			}
		}
		Map<String, Long> itemIds = new HashMap<>();
		if (!codes.isEmpty()) {
			for (Item item : items.findByItemCodeIn(codes)) {
				itemIds.put(item.getItemCode(), item.getId());
			}
		}
		List<String> holdReasons = new ArrayList<>();
		Set<String> notInCatalogue = new LinkedHashSet<>();
		for (ErpSupplyInvoiceDTO.Line source1 : sourceLines) {
			boolean item = source1.getType() == ErpSupplyInvoiceDTO.LineType.ITEM;
			HoErpInvoiceLine line = new HoErpInvoiceLine();
			line.setInvoice(invoice);
			line.setLineNo(source1.getLineNo());
			line.setLineType(item ? ErpInvoiceLineType.ITEM : ErpInvoiceLineType.OTHER);
			String code = item && source1.getItemCode() != null ? source1.getItemCode().trim() : null;
			line.setItemCode(cut(code, HoErpInvoiceLine.ITEM_CODE_LENGTH));
			line.setItemId(code == null ? null : itemIds.get(code));
			if (item && line.getItemId() == null) {
				notInCatalogue.add(code);
			}
			line.setDescription(cut(source1.getDescription(), DESCRIPTION_LENGTH));
			Integer quantity = whole(source1.getQuantity());
			if (item && source1.getQuantity() != null && quantity == null) {
				holdReasons.add("line " + source1.getLineNo() + ": quantity " + source1.getQuantity().toPlainString()
						+ " of item " + code + " is not a whole number");
			}
			line.setQuantity(quantity);
			line.setUnitOfMeasure(cut(source1.getUnitOfMeasure(), HoErpInvoiceLine.UNIT_LENGTH));
			line.setUnitPrice(amount(source1.getUnitPrice()));
			line.setLineDiscountPercent(amount(source1.getLineDiscountPercent()));
			line.setLineAmount(amount(source1.getLineAmount()));
			line.setUnitCost(item ? unitCost(source1.getLineAmount(), quantity) : null);
			invoice.getLines().add(line);
		}
		if (Boolean.TRUE.equals(source.getPricesIncludingVat())) {
			holdReasons.add("the prices include the VAT");
		}
		invoice.setHeld(!holdReasons.isEmpty());
		invoice.setHoldReason(holdReasons.isEmpty() ? null : cut(String.join("; ", holdReasons), HoErpInvoice.NOTE_LENGTH));
		List<String> warnings = new ArrayList<>(source.getWarnings() == null ? new ArrayList<>() : source.getWarnings());
		if (!notInCatalogue.isEmpty()) {
			warnings.add("items not in the catalogue: " + String.join(", ", notInCatalogue));
		}
		invoice.setWarnings(warnings.isEmpty() ? null
				: cut(String.join(WARNING_SEPARATOR, warnings), HoErpInvoice.WARNINGS_LENGTH));
		return invoice;
	}

	/** Line_Amount / Quantity, 5 decimals; null at amount 0 or without a whole quantity above 0. */
	static Double unitCost(BigDecimal lineAmount, Integer quantity) {
		if (lineAmount == null || lineAmount.signum() == 0 || quantity == null || quantity == 0) {
			return null;
		}
		return lineAmount.divide(BigDecimal.valueOf(quantity), COST_SCALE, RoundingMode.HALF_UP).doubleValue();
	}

	/** The value when whole (1.000 is), null otherwise or when absent. */
	static Integer whole(BigDecimal value) {
		if (value == null) {
			return null;
		}
		try {
			return value.stripTrailingZeros().intValueExact();
		} catch (ArithmeticException e) {
			return null;
		}
	}

	private static Double amount(BigDecimal value) {
		return value == null ? null : value.doubleValue();
	}

	// ─── Step 2: the store of each invoice ───────────────────────

	/** Step 2 alone (the page's "match the stores now"). */
	public Map<String, Object> matchStoresNow() {
		Map<String, Object> summary = assignStores();
		log.info("Head office invoices from the ERP: stores matched now: {}", summary);
		return summary;
	}

	/**
	 * Every invoice without a store and not held, each in its own transaction: assigned, or left with the reason. The
	 * summary: assigned, unassigned, and the unassigned by reason.
	 */
	public Map<String, Object> assignStores() {
		int assigned = 0;
		int unassigned = 0;
		Map<String, Integer> reasons = new LinkedHashMap<>();
		for (Long id : invoices.findIdsToAssign()) {
			ErpInvoiceMapping mapping = writeTransactions.execute(status -> assignOne(id));
			if (mapping == ErpInvoiceMapping.ASSIGNED) {
				assigned++;
			} else if (mapping != null) {
				unassigned++;
				reasons.merge(mapping.name(), 1, Integer::sum);
			}
		}
		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("assigned", assigned);
		summary.put("unassigned", unassigned);
		if (!reasons.isEmpty()) {
			summary.put("unassignedBy", reasons);
		}
		return summary;
	}

	/** The mapping written, or null when the invoice no longer waits for a store (held, assigned meanwhile). */
	private ErpInvoiceMapping assignOne(Long id) {
		Optional<HoErpInvoice> found = invoices.findForUpdate(id);
		if (!found.isPresent() || found.get().getStoreId() != null || Boolean.TRUE.equals(found.get().getHeld())) {
			return null;
		}
		HoErpInvoice invoice = found.get();
		String customer = invoice.getCustomerNo() == null ? "" : invoice.getCustomerNo().trim();
		Store store = customer.isEmpty() ? null : stores.findByErpCustomerNoIgnoreCase(customer).orElse(null);
		ErpInvoiceMapping mapping;
		if (customer.isEmpty()) {
			mapping = ErpInvoiceMapping.NO_CUSTOMER;
		} else if (store == null) {
			mapping = ErpInvoiceMapping.NO_STORE;
		} else if (Boolean.FALSE.equals(store.getActive())) {
			mapping = ErpInvoiceMapping.STORE_INACTIVE;
		} else if (store.getOwnerSupply() != null && !DataOwner.HEAD_OFFICE.name().equals(store.getOwnerSupply())) {
			mapping = ErpInvoiceMapping.STORE_NOT_SUPPLIED;
		} else {
			mapping = ErpInvoiceMapping.ASSIGNED;
		}
		invoice.setMappingStatus(mapping);
		if (mapping == ErpInvoiceMapping.ASSIGNED) {
			invoice.setStoreId(store.getId());
			invoice.setStatus(ErpInvoiceStatus.SENT);
			invoice.setSentAt(clock.get());
			afterAssigned(invoice, store);
		}
		invoices.save(invoice);
		return mapping;
	}

	/**
	 * Step (c), inside the assignment's transaction: the invoice is recorded for its store only (domain SUPPLY, record
	 * ERPINV:&lt;number&gt;), so the store pulls it. Without the copies down (the step b tests) nothing is recorded.
	 */
	void afterAssigned(HoErpInvoice invoice, Store store) {
		CopiesDownFeed copies = feed == null ? null : feed.get();
		if (copies != null) {
			copies.recordChange(DataDomain.SUPPLY, ErpInvoiceCopyDTO.recordCode(invoice.getBcNumber()),
					StoreTargets.of(Collections.singletonList(store.getId())));
		}
	}

	// ─── Confirmations up ────────────────────────────────────────

	/**
	 * A store's confirmations (POST /ho/supply/confirmations), each in its own transaction, one result per invoice in batch
	 * order, by the ERP number. A SENT invoice of this store becomes RECEIVED with the quantity received of every item
	 * line (difference: a line received in another quantity than invoiced). The same quantities again are accepted and
	 * change nothing (an answer lost); other quantities on a received invoice are rejected. An invoice of another store,
	 * unknown or without a store yet is rejected as unknown.
	 */
	@Override
	public List<SalesCopyResultDTO> receiveConfirmations(Store store, List<DeliveryConfirmationDTO> confirmations) {
		List<SalesCopyResultDTO> results = new ArrayList<>();
		if (confirmations == null) {
			return results;
		}
		int accepted = 0;
		for (DeliveryConfirmationDTO confirmation : confirmations) {
			String number = confirmation == null ? null : confirmation.getNumber();
			SalesCopyResultDTO result;
			try {
				result = writeTransactions.execute(status -> receiveOne(store, confirmation));
			} catch (RuntimeException e) {
				result = SalesCopyResultDTO.rejected(number, cut(causeOf(e), 500));
			}
			if (result.isAccepted()) {
				accepted++;
			} else {
				log.warn("Head office invoices from the ERP: confirmation of {} from store '{}' rejected: {}", number,
						store.getCode(), result.getMessage());
			}
			results.add(result);
		}
		log.info("Head office invoices from the ERP: {} confirmations received from store '{}' ({} accepted, {} rejected)",
				confirmations.size(), store.getCode(), accepted, confirmations.size() - accepted);
		return results;
	}

	private SalesCopyResultDTO receiveOne(Store store, DeliveryConfirmationDTO confirmation) {
		if (confirmation == null || confirmation.getNumber() == null || confirmation.getNumber().trim().isEmpty()) {
			return SalesCopyResultDTO.rejected(null, "number is required");
		}
		String number = confirmation.getNumber().trim();
		Optional<HoErpInvoice> found = invoices.findForUpdateByStoreAndNumber(store.getId(), number);
		if (!found.isPresent() || found.get().getStatus() == ErpInvoiceStatus.READ) {
			return SalesCopyResultDTO.rejected(number, "unknown invoice " + number + " for this store");
		}
		HoErpInvoice invoice = found.get();
		List<HoErpInvoiceLine> itemLines = invoice.getLines().stream()
				.filter(l -> l.getLineType() == ErpInvoiceLineType.ITEM).collect(Collectors.toList());
		Map<Integer, Integer> received = new HashMap<>();
		for (DeliveryConfirmationDTO.Line line : confirmation.getLines() == null
				? Collections.<DeliveryConfirmationDTO.Line>emptyList()
				: confirmation.getLines()) {
			HoErpInvoiceLine invoiced = line == null || line.getLineNo() == null ? null
					: itemLines.stream().filter(l -> l.getLineNo().equals(line.getLineNo())).findFirst().orElse(null);
			if (invoiced == null || (line.getItemCode() != null && !line.getItemCode().equals(invoiced.getItemCode()))) {
				return SalesCopyResultDTO.rejected(number,
						"line " + (line == null ? null : line.getLineNo()) + " does not match an item line of the invoice");
			}
			if (line.getQuantityReceived() == null || line.getQuantityReceived() < 0) {
				return SalesCopyResultDTO.rejected(number,
						"line " + line.getLineNo() + ": quantityReceived must be 0 or more");
			}
			if (received.put(line.getLineNo(), line.getQuantityReceived()) != null) {
				return SalesCopyResultDTO.rejected(number, "line " + line.getLineNo() + " is given twice");
			}
		}
		if (received.size() != itemLines.size()) {
			return SalesCopyResultDTO.rejected(number, "every item line of the invoice is required");
		}
		if (invoice.getStatus() == ErpInvoiceStatus.RECEIVED) {
			boolean same = itemLines.stream().allMatch(l -> received.get(l.getLineNo()).equals(l.getQuantityReceived()));
			return same ? SalesCopyResultDTO.accepted(number)
					: SalesCopyResultDTO.rejected(number, "already received with other quantities");
		}
		boolean difference = false;
		for (HoErpInvoiceLine line : itemLines) {
			Integer quantity = received.get(line.getLineNo());
			line.setQuantityReceived(quantity);
			difference |= !quantity.equals(line.getQuantity());
		}
		invoice.setStatus(ErpInvoiceStatus.RECEIVED);
		invoice.setDifference(difference);
		invoice.setReceivedAt(parseDateTime(confirmation.getReceivedAt()));
		invoice.setReceivedBy(cut(confirmation.getReceivedBy(), HoErpInvoice.USER_LENGTH));
		invoice.setStoreNote(cut(confirmation.getNote(), HoErpInvoice.NOTE_LENGTH));
		invoice.setConfirmationReceivedAt(clock.get());
		invoices.save(invoice);
		return SalesCopyResultDTO.accepted(number);
	}

	// ─── Copies down (domain SUPPLY), step (c) ───────────────────

	@Override
	public DataDomain getDomain() {
		return DataDomain.SUPPLY;
	}

	/**
	 * The invoices of this store among the codes ERPINV:&lt;number&gt;, SENT or RECEIVED, never a held one or one of another
	 * store (answered as removed). Other codes (BL:, INV: left by a head office that made its own BLs before) are answered
	 * as removed too: the store only drops its tracking row.
	 */
	@Override
	public Map<String, JsonNode> load(Store store, List<String> codes) {
		List<String> numbers = codes.stream().map(ErpInvoiceCopyDTO::numberOf).filter(n -> n != null)
				.collect(Collectors.toList());
		Map<String, JsonNode> copies = new HashMap<>();
		if (!numbers.isEmpty()) {
			for (HoErpInvoice invoice : invoices.findForStore(store.getId(), numbers, COPIED_STATUSES)) {
				copies.put(ErpInvoiceCopyDTO.recordCode(invoice.getBcNumber()),
						COPY_MAPPER.valueToTree(ErpInvoiceCopyDTO.of(invoice, sellerName)));
			}
		}
		return copies;
	}

	/**
	 * Every invoice given to a store and not held, to its store: the startup backfill records those without a change row
	 * (the invoices assigned before step c reach their store at the first start of this build).
	 */
	@Override
	public Map<String, StoreTargets> currentTargets() {
		Map<String, StoreTargets> targets = new LinkedHashMap<>();
		for (Object[] row : invoices.findAssignedTargets()) {
			targets.put(ErpInvoiceCopyDTO.recordCode((String) row[0]),
					StoreTargets.of(Collections.singletonList(((Number) row[1]).longValue())));
		}
		return targets;
	}

	// ─── Reads (the page of step d) ──────────────────────────────

	/**
	 * The page {content, totalElements, totalPages, number, size}, newest number first. storeId null or 0: every store;
	 * status and mapping blank or "all": every one; held null: both; withWarnings / withDifference true: only the
	 * invoices with warnings / received with a difference, null or false: all; search on the number and the customer.
	 * 400 (IllegalArgument) for a status, a mapping or a page that cannot be read.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> list(Long storeId, String status, String mapping, Boolean held, Boolean withWarnings,
			Boolean withDifference, String search, Integer page, Integer size) {
		ErpInvoiceStatus wantedStatus = parse(ErpInvoiceStatus.class, "status", status);
		ErpInvoiceMapping wantedMapping = parse(ErpInvoiceMapping.class, "mapping", mapping);
		int pageNumber = page == null ? 0 : page;
		if (pageNumber < 0) {
			throw new IllegalArgumentException("page must be 0 or more");
		}
		int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, size));
		String like = search == null || search.trim().isEmpty() ? null : "%" + search.trim().toLowerCase() + "%";
		Page<HoErpInvoice> result = invoices.findPage(storeId == null ? 0L : storeId, wantedStatus == null ? 1L : 0L,
				wantedStatus == null ? ErpInvoiceStatus.READ : wantedStatus, wantedMapping == null ? 1L : 0L,
				wantedMapping == null ? ErpInvoiceMapping.ASSIGNED : wantedMapping,
				held == null ? 0L : held ? 1L : 2L, Boolean.TRUE.equals(withWarnings) ? 1L : 0L,
				Boolean.TRUE.equals(withDifference) ? 1L : 0L, like, PageRequest.of(pageNumber, pageSize));
		Map<Long, Store> byId = storesById();
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content", result.getContent().stream().map(i -> view(i, byId.get(i.getStoreId()), false))
				.collect(Collectors.toList()));
		answer.put("totalElements", result.getTotalElements());
		answer.put("totalPages", result.getTotalPages());
		answer.put("number", result.getNumber());
		answer.put("size", result.getSize());
		answer.put("lastRun", lastRun);
		return answer;
	}

	/** One invoice with its lines; empty when unknown. */
	@Transactional(readOnly = true)
	public Optional<ErpInvoiceDTO> get(Long id) {
		return invoices.findById(id).map(i -> view(i, i.getStoreId() == null ? null
				: stores.findById(i.getStoreId()).orElse(null), true));
	}

	private Map<Long, Store> storesById() {
		Map<Long, Store> byId = new HashMap<>();
		for (Store store : stores.findAll()) {
			byId.put(store.getId(), store);
		}
		return byId;
	}

	ErpInvoiceDTO view(HoErpInvoice invoice, Store store, boolean withLines) {
		ErpInvoiceDTO view = new ErpInvoiceDTO();
		view.setId(invoice.getId());
		view.setNumber(invoice.getBcNumber());
		view.setYearPrefix(invoice.getYearPrefix());
		view.setDocumentDate(invoice.getDocumentDate());
		view.setPostingDate(invoice.getPostingDate());
		view.setCustomerNo(invoice.getCustomerNo());
		view.setCustomerName(invoice.getCustomerName());
		view.setStoreId(invoice.getStoreId());
		view.setStoreCode(store == null ? null : store.getCode());
		view.setStoreName(store == null ? null : store.getName());
		view.setTotalExclVat(invoice.getTotalExclVat());
		view.setTotalVat(invoice.getTotalVat());
		view.setTotalInclVat(invoice.getTotalInclVat());
		view.setStatus(invoice.getStatus().name());
		view.setMappingStatus(invoice.getMappingStatus() == null ? null : invoice.getMappingStatus().name());
		view.setMappingMessage(mappingMessage(invoice.getMappingStatus(), invoice.getCustomerNo()));
		view.setHeld(Boolean.TRUE.equals(invoice.getHeld()));
		view.setHoldReason(invoice.getHoldReason());
		if (invoice.getWarnings() != null && !invoice.getWarnings().isEmpty()) {
			for (String warning : invoice.getWarnings().split(WARNING_SEPARATOR)) {
				view.getWarnings().add(warning);
			}
		}
		view.setReadAt(invoice.getReadAt());
		view.setSentAt(invoice.getSentAt());
		view.setReceivedAt(invoice.getReceivedAt());
		view.setReceivedBy(invoice.getReceivedBy());
		view.setStoreNote(invoice.getStoreNote());
		view.setConfirmationReceivedAt(invoice.getConfirmationReceivedAt());
		view.setDifference(invoice.getDifference());
		int notHere = 0;
		List<ErpInvoiceDTO.Line> lines = new ArrayList<>();
		for (HoErpInvoiceLine line : invoice.getLines()) {
			boolean item = line.getLineType() == ErpInvoiceLineType.ITEM;
			notHere += item && line.getItemId() == null ? 1 : 0;
			if (withLines) {
				ErpInvoiceDTO.Line row = new ErpInvoiceDTO.Line();
				row.setLineNo(line.getLineNo());
				row.setType(line.getLineType().name());
				row.setItemCode(line.getItemCode());
				row.setItemId(line.getItemId());
				row.setItemHere(item && line.getItemId() != null);
				row.setDescription(line.getDescription());
				row.setQuantity(line.getQuantity());
				row.setUnitOfMeasure(line.getUnitOfMeasure());
				row.setUnitPrice(line.getUnitPrice());
				row.setLineDiscountPercent(line.getLineDiscountPercent());
				row.setLineAmount(line.getLineAmount());
				row.setUnitCost(line.getUnitCost());
				row.setQuantityReceived(line.getQuantityReceived());
				row.setDifference(line.getQuantityReceived() == null || line.getQuantity() == null ? null
						: line.getQuantityReceived() - line.getQuantity());
				lines.add(row);
			}
		}
		view.setItemsNotInCatalogue(notHere);
		view.setLineCount(invoice.getLines().size());
		view.setLines(withLines ? lines : null);
		return view;
	}

	/** The mapping in words with the customer; null when assigned or never searched. */
	static String mappingMessage(ErpInvoiceMapping mapping, String customer) {
		if (mapping == null) {
			return null;
		}
		switch (mapping) {
		case NO_CUSTOMER:
			return "no customer on the invoice";
		case NO_STORE:
			return "no store for customer " + customer;
		case STORE_INACTIVE:
			return "the store of customer " + customer + " is inactive";
		case STORE_NOT_SUPPLIED:
			return "the store of customer " + customer + " does not receive from the head office";
		default:
			return null;
		}
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private static <E extends Enum<E>> E parse(Class<E> type, String name, String value) {
		if (value == null || value.trim().isEmpty() || "all".equalsIgnoreCase(value.trim())) {
			return null;
		}
		try {
			return Enum.valueOf(type, value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			throw new IllegalArgumentException("Invalid " + name + " '" + value + "': allowed values are all, "
					+ java.util.Arrays.toString(type.getEnumConstants()));
		}
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

	/** The most specific cause of a failure. */
	private static String causeOf(RuntimeException e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause instanceof IllegalArgumentException ? cause.getMessage()
				: cause.getClass().getSimpleName() + ": " + cause.getMessage();
	}

	private static String blankToNull(String value) {
		return value == null || value.trim().isEmpty() ? null : value.trim();
	}

	static String cut(String text, int length) {
		return text == null || text.length() <= length ? text : text.substring(0, length);
	}
}
