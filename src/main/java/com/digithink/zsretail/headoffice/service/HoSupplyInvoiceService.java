package com.digithink.zsretail.headoffice.service;

import java.time.LocalDate;
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
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwnSupply;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceCopyDTO;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceDTO;
import com.digithink.zsretail.headoffice.enumeration.DeliveryStatus;
import com.digithink.zsretail.headoffice.enumeration.InvoiceRhythm;
import com.digithink.zsretail.headoffice.model.HoDelivery;
import com.digithink.zsretail.headoffice.model.HoDeliveryLine;
import com.digithink.zsretail.headoffice.model.HoNumberSequence;
import com.digithink.zsretail.headoffice.model.HoSupplyInvoice;
import com.digithink.zsretail.headoffice.model.HoSupplyInvoiceLine;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoDeliveryRepository;
import com.digithink.zsretail.headoffice.repository.HoNumberSequenceRepository;
import com.digithink.zsretail.headoffice.repository.HoSupplyInvoiceRepository;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.CompanyInformation;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.repository.CompanyInformationRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.service.GeneralSetupService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 7B, part 2: the supply invoices of a head office without an ERP. An invoice takes received BLs
 * of one store whose deliveries are invoiced, on the confirmed quantities, at the store's supply price of the invoice
 * date ({@link HoSupplyPriceService}), with each item's VAT; a BL is invoiced once and becomes INVOICED. When the head
 * office setting SUPPLY_INVOICE_TAX_STAMP is true, one TAX_STAMP line at VAT 0 with the amount of
 * TAX_STAMP_VALUE_MILLIMES. Numbered FHO-yyyy-000001 (a sequence per year). Rhythm PER_BL: created when the store's
 * confirmation arrives ({@link #afterReceived}), in its own transaction, so a pricing problem never rejects the
 * confirmation (the BL keeps the reason in invoice_note). The invoice goes to its store only (domain SUPPLY, record
 * INV:&lt;number&gt;). Paid or unpaid; never cancelled. See docs/modules/head-office.md, "Supply invoices".
 */
@Service
@ConditionalOnHeadOfficeOwnSupply
@Log4j2
public class HoSupplyInvoiceService {

	public static final String TAX_STAMP_SETTING = "SUPPLY_INVOICE_TAX_STAMP";
	/** The supply invoice stamp in millimes (1000 by default); the till ticket's TAX_STAMP_VALUE_MILLIMES is not used. */
	public static final String TAX_STAMP_MILLIMES_SETTING = "SUPPLY_INVOICE_TAX_STAMP_MILLIMES";
	static final int DEFAULT_STAMP_MILLIMES = 1000;
	static final String NOTHING_RECEIVED = "Nothing was received on these BLs: nothing to invoice.";
	static final String NUMBER_PREFIX = "FHO-";
	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 200;
	static final String AUTO_USER = "AUTO";

	static final ObjectMapper COPY_MAPPER = new ObjectMapper();

	private final HoSupplyInvoiceRepository invoices;
	private final HoDeliveryRepository deliveries;
	private final StoreRepository stores;
	private final ItemRepository items;
	private final HoNumberSequenceRepository sequences;
	private final HoSupplyPriceService supplyPrices;
	private final CompanyInformationRepository company;
	private final GeneralSetupService setup;
	private final Supplier<CopiesDownFeed> feed;
	private final TransactionOperations writeTransactions;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public HoSupplyInvoiceService(HoSupplyInvoiceRepository invoices, HoDeliveryRepository deliveries,
			StoreRepository stores, ItemRepository items, HoNumberSequenceRepository sequences,
			HoSupplyPriceService supplyPrices, CompanyInformationRepository company, GeneralSetupService setup,
			ObjectProvider<CopiesDownFeed> feed, PlatformTransactionManager transactionManager) {
		this(invoices, deliveries, stores, items, sequences, supplyPrices, company, setup,
				(Supplier<CopiesDownFeed>) feed::getObject, new TransactionTemplate(transactionManager), LocalDateTime::now);
	}

	/** With given collaborators, transactions and clock: used by the tests. */
	public HoSupplyInvoiceService(HoSupplyInvoiceRepository invoices, HoDeliveryRepository deliveries,
			StoreRepository stores, ItemRepository items, HoNumberSequenceRepository sequences,
			HoSupplyPriceService supplyPrices, CompanyInformationRepository company, GeneralSetupService setup,
			Supplier<CopiesDownFeed> feed, TransactionOperations writeTransactions, Supplier<LocalDateTime> clock) {
		this.invoices = invoices;
		this.deliveries = deliveries;
		this.stores = stores;
		this.items = items;
		this.sequences = sequences;
		this.supplyPrices = supplyPrices;
		this.company = company;
		this.setup = setup;
		this.feed = feed;
		this.writeTransactions = writeTransactions;
		this.clock = clock;
	}

	// ─── Creating ────────────────────────────────────────────────

	/** The BLs of the store that can be invoiced: RECEIVED, not invoiced, oldest first, with their invoice_note. */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> toInvoice(Long storeId) {
		Store store = store(storeId);
		List<Map<String, Object>> rows = new ArrayList<>();
		for (HoDelivery delivery : deliveries.findToInvoice(store.getId(), DeliveryStatus.RECEIVED)) {
			if (received(delivery) == 0) {
				continue; // received at 0 on every line: nothing to invoice
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("id", delivery.getId());
			row.put("number", delivery.getNumber());
			row.put("documentDate", delivery.getDocumentDate() == null ? null : delivery.getDocumentDate().toString());
			row.put("receivedAt", delivery.getReceivedAt() == null ? null : delivery.getReceivedAt().toString());
			row.put("quantityReceived", delivery.getLines().stream()
					.mapToInt(l -> l.getQuantityReceived() == null ? 0 : l.getQuantityReceived()).sum());
			row.put("invoiceNote", delivery.getInvoiceNote());
			rows.add(row);
		}
		return rows;
	}

	/**
	 * What the invoice would be, written nowhere: lines, totals, and the items without a supply price (in missingPrices,
	 * and as lines flagged missingPrice, without a price, outside the totals).
	 */
	@Transactional(readOnly = true)
	public SupplyInvoiceDTO preview(SupplyInvoiceDTO request) {
		Store store = store(request == null ? null : request.getStoreId());
		List<HoDelivery> chosen = checkDeliveries(store, request.getDeliveryIds(), false);
		checkSomethingReceived(chosen);
		LocalDate date = invoiceDate(request.getInvoiceDate());
		checkDate(date);
		Draft draft = draft(store, chosen, date);
		SupplyInvoiceDTO view = view(draft.invoice, store, false);
		view.setLines(draft.previewLines);
		view.setMissingPrices(draft.missing);
		return view;
	}

	/**
	 * Creates the invoice, all or nothing (the BL rows locked): 201 the invoice. 400 (IllegalArgument) for a request
	 * without a store or a BL, or a date that cannot be read; 409 (IllegalState) for a store whose deliveries are not
	 * invoiced, a BL of another store, not received or already invoiced, and items without a supply price (listed).
	 */
	@Transactional(rollbackFor = Exception.class)
	public SupplyInvoiceDTO create(SupplyInvoiceDTO request, String user) {
		Store store = store(request == null ? null : request.getStoreId());
		return view(create(store, request.getDeliveryIds(), invoiceDate(request.getInvoiceDate()), request.getNote(),
				user), store, true);
	}

	private HoSupplyInvoice create(Store store, List<Long> deliveryIds, LocalDate invoiceDate, String note,
			String user) {
		List<HoDelivery> chosen = checkDeliveries(store, deliveryIds, true);
		checkSomethingReceived(chosen);
		checkDate(invoiceDate);
		Draft draft = draft(store, chosen, invoiceDate);
		if (!draft.missing.isEmpty()) {
			throw new IllegalStateException("No supply price for the store " + store.getCode() + ": "
					+ String.join(", ", draft.missing) + ".");
		}
		HoSupplyInvoice invoice = draft.invoice;
		invoice.setInvoiceNumber(nextNumber(invoiceDate));
		invoice.setNote(note == null || note.trim().isEmpty() ? null : cut(note.trim(), HoSupplyInvoice.NOTE_LENGTH));
		invoice.setCreatedBy(user == null ? "System" : user);
		HoSupplyInvoice saved = invoices.save(invoice);
		for (HoDelivery delivery : chosen) {
			delivery.setStatus(DeliveryStatus.INVOICED);
			delivery.setInvoiceId(saved.getId());
			delivery.setInvoiceNote(null);
			deliveries.save(delivery);
		}
		feed.get().recordChange(DataDomain.SUPPLY, SupplyInvoiceCopyDTO.recordCode(saved.getInvoiceNumber()),
				StoreTargets.of(Collections.singletonList(store.getId())));
		log.info("Head office supply invoice {} for store {}: {} BLs, total {}", saved.getInvoiceNumber(),
				store.getCode(), chosen.size(), saved.getTotalAmount());
		return saved;
	}

	/**
	 * Rhythm PER_BL: called after a store's confirmation was saved (its own transaction). A store whose deliveries are
	 * invoiced per BL gets the invoice of that BL now; when it cannot be created (an item without a supply price, a
	 * missing percentage), the BL keeps the reason in invoice_note and is invoiced by hand later. Never throws.
	 */
	public void afterReceived(Long storeId, Long deliveryId) {
		try {
			Store store = stores.findById(storeId).orElse(null);
			if (store == null || !Boolean.TRUE.equals(store.getDeliveriesInvoiced())
					|| store.getInvoiceRhythm() == InvoiceRhythm.GROUPED) {
				return;
			}
			if (deliveries.findById(deliveryId).map(HoSupplyInvoiceService::received).orElse(0) == 0) {
				return; // nothing received: no invoice, no invoice_note
			}
			writeTransactions.executeWithoutResult(status -> create(store, Collections.singletonList(deliveryId),
					clock.get().toLocalDate(), null, AUTO_USER));
		} catch (RuntimeException e) {
			String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
			log.warn("Head office supply invoice: BL id {} not invoiced automatically ({})", deliveryId, reason);
			try {
				writeTransactions.executeWithoutResult(status -> deliveries.findForUpdate(deliveryId).ifPresent(d -> {
					d.setInvoiceNote(cut(reason, HoDelivery.NOTE_LENGTH));
					deliveries.save(d);
				}));
			} catch (RuntimeException ignored) {
				// the BL stays in the "to invoice" list without its reason
			}
		}
	}

	private List<HoDelivery> checkDeliveries(Store store, List<Long> deliveryIds, boolean lock) {
		if (!Boolean.TRUE.equals(store.getDeliveriesInvoiced())) {
			throw new IllegalStateException("The deliveries of the store " + store.getCode()
					+ " are not invoiced (setting on the Stores page).");
		}
		if (deliveryIds == null || deliveryIds.isEmpty()) {
			throw new IllegalArgumentException("Choose at least one received BL.");
		}
		List<HoDelivery> chosen = new ArrayList<>();
		for (Long id : new LinkedHashSet<>(deliveryIds)) {
			Optional<HoDelivery> found = lock ? deliveries.findForUpdate(id) : deliveries.findById(id);
			HoDelivery delivery = found.orElseThrow(() -> new IllegalArgumentException("Unknown BL id " + id + "."));
			String name = delivery.getNumber() == null ? "The draft BL " + id : delivery.getNumber();
			if (!store.getId().equals(delivery.getStoreId())) {
				throw new IllegalStateException(name + " is not a BL of the store " + store.getCode() + ".");
			}
			if (delivery.getInvoiceId() != null || delivery.getStatus() == DeliveryStatus.INVOICED) {
				throw new IllegalStateException(name + " is already invoiced.");
			}
			if (delivery.getStatus() != DeliveryStatus.RECEIVED) {
				throw new IllegalStateException(
						name + " is " + delivery.getStatus() + ": only received BLs can be invoiced.");
			}
			chosen.add(delivery);
		}
		return chosen;
	}

	/** The invoice not saved yet (lines, totals, snapshots) and the item codes without a supply price. */
	private Draft draft(Store store, List<HoDelivery> chosen, LocalDate invoiceDate) {
		Map<Long, Item> byId = new LinkedHashMap<>();
		for (HoDelivery delivery : chosen) {
			for (HoDeliveryLine line : delivery.getLines()) {
				if (quantity(line) > 0 && !byId.containsKey(line.getItemId())) {
					Item item = items.findById(line.getItemId()).orElseThrow(() -> new IllegalStateException(
							"The item " + line.getItemCode() + " of " + delivery.getNumber() + " no longer exists."));
					byId.put(item.getId(), item);
				}
			}
		}
		Map<Long, Double> prices = supplyPrices.pricesFor(store, byId.values());
		Set<String> missing = new LinkedHashSet<>();
		List<SupplyInvoiceCopyDTO.Line> previewLines = new ArrayList<>();
		HoSupplyInvoice invoice = new HoSupplyInvoice();
		invoice.setStoreId(store.getId());
		invoice.setInvoiceDate(invoiceDate);
		invoice.setBuyerName(store.getBillingLegalName() != null ? store.getBillingLegalName() : store.getName());
		invoice.setBuyerTaxNumber(store.getBillingTaxNumber());
		invoice.setBuyerAddress(store.getBillingAddress());
		CompanyInformation seller = company.findAll().stream().findFirst().orElse(null);
		if (seller != null) {
			invoice.setSellerName(seller.getCompanyName());
			invoice.setSellerTaxNumber(seller.getMatriculeFiscal());
			invoice.setSellerAddress(address(seller));
		}
		invoice.setPaid(Boolean.FALSE);
		int lineNo = 0;
		for (HoDelivery delivery : chosen) {
			for (HoDeliveryLine line : delivery.getLines()) {
				int quantity = quantity(line);
				if (quantity <= 0) {
					continue;
				}
				Item item = byId.get(line.getItemId());
				Double price = prices.get(item.getId());
				if (price == null) {
					missing.add(item.getItemCode());
					previewLines.add(SupplyInvoiceCopyDTO.Line.missingPrice(delivery.getNumber(), item.getItemCode(),
							item.getName(), quantity));
					continue;
				}
				int vat = item.getDefaultVAT() == null ? 0 : item.getDefaultVAT();
				HoSupplyInvoiceLine priced = line(invoice, ++lineNo, delivery.getNumber(), item, quantity, price, vat);
				invoice.getLines().add(priced);
				previewLines.add(SupplyInvoiceCopyDTO.Line.of(priced));
			}
		}
		if (Boolean.parseBoolean(String.valueOf(setup.findValueByCode(TAX_STAMP_SETTING)).trim())) {
			Item stamp = items.findByItemCode(CatalogueKind.TAX_STAMP_CODE).orElse(null);
			HoSupplyInvoiceLine line = line(invoice, ++lineNo, null, stamp, 1, stampAmount(), 0);
			if (stamp == null) {
				line.setItemCode(CatalogueKind.TAX_STAMP_CODE);
				line.setItemName("Tax stamp");
			}
			invoice.getLines().add(line);
			previewLines.add(SupplyInvoiceCopyDTO.Line.of(line));
		}
		double subtotal = 0;
		double tax = 0;
		double total = 0;
		for (HoSupplyInvoiceLine line : invoice.getLines()) {
			subtotal += line.getLineTotal();
			tax += line.getVatAmount();
			total += line.getLineTotalIncludingVat();
		}
		invoice.setSubtotal(HoSupplyPriceService.round(subtotal));
		invoice.setTaxAmount(HoSupplyPriceService.round(tax));
		invoice.setTotalAmount(HoSupplyPriceService.round(total));
		return new Draft(invoice, new ArrayList<>(missing), previewLines);
	}

	private static HoSupplyInvoiceLine line(HoSupplyInvoice invoice, int lineNo, String deliveryNumber, Item item,
			int quantity, double unitPrice, int vatPercent) {
		HoSupplyInvoiceLine line = new HoSupplyInvoiceLine();
		line.setInvoice(invoice);
		line.setLineNo(lineNo);
		line.setDeliveryNumber(deliveryNumber);
		if (item != null) {
			line.setItemId(item.getId());
			line.setItemCode(item.getItemCode());
			line.setItemName(item.getName());
		}
		line.setQuantity(quantity);
		line.setUnitPrice(unitPrice);
		line.setVatPercent(vatPercent);
		double lineTotal = HoSupplyPriceService.round(unitPrice * quantity);
		double vatAmount = HoSupplyPriceService.round(lineTotal * vatPercent / 100.0);
		line.setLineTotal(lineTotal);
		line.setVatAmount(vatAmount);
		line.setLineTotalIncludingVat(HoSupplyPriceService.round(lineTotal + vatAmount));
		return line;
	}

	/** SUPPLY_INVOICE_TAX_STAMP_MILLIMES (1000 when absent or unreadable), in dinars. */
	private double stampAmount() {
		String value = setup.findValueByCode(TAX_STAMP_MILLIMES_SETTING);
		int millimes = DEFAULT_STAMP_MILLIMES;
		try {
			if (value != null && !value.trim().isEmpty()) {
				millimes = Integer.parseInt(value.trim());
			}
		} catch (NumberFormatException e) {
			log.warn("Invalid {}: {}, using {}", TAX_STAMP_MILLIMES_SETTING, value, DEFAULT_STAMP_MILLIMES);
		}
		return millimes / 1000.0;
	}

	/** The quantity received on the whole BL. */
	static int received(HoDelivery delivery) {
		return delivery.getLines().stream().mapToInt(HoSupplyInvoiceService::quantity).sum();
	}

	/** 409 when every chosen BL was received at 0: an invoice is never the stamp alone. */
	private static void checkSomethingReceived(List<HoDelivery> chosen) {
		if (chosen.stream().mapToInt(HoSupplyInvoiceService::received).sum() == 0) {
			throw new IllegalStateException(NOTHING_RECEIVED);
		}
	}

	/**
	 * 400 (IllegalArgument) for a date in the future, or before the date of the last invoice issued: the numbers follow
	 * the dates.
	 */
	private void checkDate(LocalDate invoiceDate) {
		if (invoiceDate.isAfter(clock.get().toLocalDate())) {
			throw new IllegalArgumentException("The invoice date cannot be in the future.");
		}
		invoices.findFirstByOrderByInvoiceDateDescIdDesc().ifPresent(last -> {
			if (invoiceDate.isBefore(last.getInvoiceDate())) {
				throw new IllegalArgumentException("The invoice date cannot be before the date of the last invoice ("
						+ last.getInvoiceNumber() + " of " + last.getInvoiceDate() + ").");
			}
		});
	}

	private static int quantity(HoDeliveryLine line) {
		return line.getQuantityReceived() == null ? 0 : line.getQuantityReceived();
	}

	private static String address(CompanyInformation seller) {
		List<String> parts = new ArrayList<>();
		for (String part : new String[] { seller.getAddress(), seller.getPostalCode(), seller.getCity(),
				seller.getCountry() }) {
			if (part != null && !part.trim().isEmpty()) {
				parts.add(part.trim());
			}
		}
		return parts.isEmpty() ? null : cut(String.join(", ", parts), 500);
	}

	private String nextNumber(LocalDate invoiceDate) {
		String code = NUMBER_PREFIX + invoiceDate.getYear();
		long value;
		if (sequences.increment(code) == 0) {
			HoNumberSequence sequence = new HoNumberSequence();
			sequence.setCode(code);
			sequence.setLastValue(1);
			sequences.save(sequence);
			value = 1;
		} else {
			value = sequences.lastValue(code).get(0);
		}
		return code + "-" + String.format("%06d", value);
	}

	private static final class Draft {
		final HoSupplyInvoice invoice;
		final List<String> missing;
		/** In BL order: the invoice lines and, in their place, the lines of the items without a supply price. */
		final List<SupplyInvoiceCopyDTO.Line> previewLines;

		Draft(HoSupplyInvoice invoice, List<String> missing, List<SupplyInvoiceCopyDTO.Line> previewLines) {
			this.invoice = invoice;
			this.missing = missing;
			this.previewLines = previewLines;
		}
	}

	// ─── Reads and payment ───────────────────────────────────────

	/** The invoices page, newest first: storeId (null: every store), paid (null: both), invoice dates yyyy-MM-dd. */
	@Transactional(readOnly = true)
	public Map<String, Object> list(Long storeId, Boolean paid, String dateFrom, String dateTo, Integer page,
			Integer size) {
		int number = page == null ? 0 : page;
		if (number < 0) {
			throw new IllegalArgumentException("page must be 0 or more");
		}
		int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, size));
		Page<HoSupplyInvoice> rows = invoices.findPage(storeId == null ? 0L : storeId, paid == null ? 1L : 0L,
				paid == null ? Boolean.FALSE : paid, date("dateFrom", dateFrom, LocalDate.of(1900, 1, 1)),
				date("dateTo", dateTo, LocalDate.of(9999, 12, 31)), PageRequest.of(number, pageSize));
		Map<Long, Store> byId = new HashMap<>();
		stores.findAll().forEach(s -> byId.put(s.getId(), s));
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("content", rows.getContent().stream().map(i -> view(i, byId.get(i.getStoreId()), false))
				.collect(Collectors.toList()));
		answer.put("totalElements", rows.getTotalElements());
		answer.put("totalPages", rows.getTotalPages());
		answer.put("number", rows.getNumber());
		answer.put("size", rows.getSize());
		return answer;
	}

	@Transactional(readOnly = true)
	public Optional<SupplyInvoiceDTO> get(Long id) {
		return invoices.findById(id).map(i -> view(i, stores.findById(i.getStoreId()).orElse(null), true));
	}

	/** Paid or unpaid, with a date (today when paid without one) and a note. Empty when unknown. */
	@Transactional(rollbackFor = Exception.class)
	public Optional<SupplyInvoiceDTO> setPaid(Long id, Boolean paid, String paidDate, String note) {
		if (paid == null) {
			throw new IllegalArgumentException("paid is required (true or false).");
		}
		Optional<HoSupplyInvoice> found = invoices.findForUpdate(id);
		if (!found.isPresent()) {
			return Optional.empty();
		}
		HoSupplyInvoice invoice = found.get();
		invoice.setPaid(paid);
		invoice.setPaidDate(paid ? date("paidDate", paidDate, clock.get().toLocalDate()) : null);
		invoice.setPaidNote(note == null || note.trim().isEmpty() ? null : cut(note.trim(), HoSupplyInvoice.NOTE_LENGTH));
		HoSupplyInvoice saved = invoices.save(invoice);
		return Optional.of(view(saved, stores.findById(saved.getStoreId()).orElse(null), true));
	}

	/**
	 * What each store owes: [{storeId, storeCode, storeName, invoiceCount, total, paid, unpaid, unpaidCount}] for every
	 * store with an invoice, by store code; amounts including VAT.
	 */
	@Transactional(readOnly = true)
	public List<Map<String, Object>> balances() {
		Map<Long, double[]> sums = new HashMap<>(); // total, paid, unpaid
		Map<Long, long[]> counts = new HashMap<>(); // invoices, unpaid invoices
		for (Object[] row : invoices.balances()) {
			Long storeId = ((Number) row[0]).longValue();
			boolean paid = Boolean.TRUE.equals(row[1]);
			long count = ((Number) row[2]).longValue();
			double total = row[3] == null ? 0 : ((Number) row[3]).doubleValue();
			double[] amounts = sums.computeIfAbsent(storeId, id -> new double[3]);
			long[] numbers = counts.computeIfAbsent(storeId, id -> new long[2]);
			amounts[0] += total;
			amounts[paid ? 1 : 2] += total;
			numbers[0] += count;
			if (!paid) {
				numbers[1] += count;
			}
		}
		List<Map<String, Object>> result = new ArrayList<>();
		for (Store store : stores.findAll()) {
			double[] amounts = sums.get(store.getId());
			if (amounts == null) {
				continue;
			}
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("storeId", store.getId());
			row.put("storeCode", store.getCode());
			row.put("storeName", store.getName());
			row.put("invoiceCount", counts.get(store.getId())[0]);
			row.put("total", HoSupplyPriceService.round(amounts[0]));
			row.put("paid", HoSupplyPriceService.round(amounts[1]));
			row.put("unpaid", HoSupplyPriceService.round(amounts[2]));
			row.put("unpaidCount", counts.get(store.getId())[1]);
			result.add(row);
		}
		result.sort((a, b) -> ((String) a.get("storeCode")).compareTo((String) b.get("storeCode")));
		return result;
	}

	/** The number of an invoice; null when unknown (shown on the BLs). */
	public String numberOf(Long invoiceId) {
		return invoices.findById(invoiceId).map(HoSupplyInvoice::getInvoiceNumber).orElse(null);
	}

	// ─── Copies down (records INV: of the domain SUPPLY) ─────────

	/** The invoices of this store among the codes (INV:&lt;number&gt;); another store's invoice is never answered. */
	public Map<String, JsonNode> load(Store store, List<String> codes) {
		List<String> numbers = codes.stream().map(SupplyInvoiceCopyDTO::numberOf).filter(n -> n != null)
				.collect(Collectors.toList());
		Map<String, JsonNode> copies = new HashMap<>();
		if (!numbers.isEmpty()) {
			for (HoSupplyInvoice invoice : invoices.findOfStore(store.getId(), numbers)) {
				copies.put(SupplyInvoiceCopyDTO.recordCode(invoice.getInvoiceNumber()),
						COPY_MAPPER.valueToTree(SupplyInvoiceCopyDTO.of(invoice)));
			}
		}
		return copies;
	}

	public Map<String, StoreTargets> currentTargets() {
		Map<String, StoreTargets> targets = new LinkedHashMap<>();
		for (Object[] row : invoices.findTargets()) {
			targets.put(SupplyInvoiceCopyDTO.recordCode((String) row[0]),
					StoreTargets.of(Collections.singletonList(((Number) row[1]).longValue())));
		}
		return targets;
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private Store store(Long storeId) {
		if (storeId == null) {
			throw new IllegalArgumentException("Choose the store of the invoice.");
		}
		return stores.findById(storeId)
				.orElseThrow(() -> new IllegalArgumentException("Unknown store id " + storeId + "."));
	}

	private LocalDate invoiceDate(String value) {
		return date("invoiceDate", value, clock.get().toLocalDate());
	}

	private static LocalDate date(String name, String value, LocalDate absent) {
		if (value == null || value.trim().isEmpty()) {
			return absent;
		}
		try {
			return LocalDate.parse(value.trim());
		} catch (DateTimeParseException e) {
			throw new IllegalArgumentException(name + " must be a date as yyyy-MM-dd.");
		}
	}

	private SupplyInvoiceDTO view(HoSupplyInvoice invoice, Store store, boolean withLines) {
		SupplyInvoiceDTO view = new SupplyInvoiceDTO();
		SupplyInvoiceCopyDTO copy = SupplyInvoiceCopyDTO.of(invoice);
		view.setId(invoice.getId());
		view.setInvoiceNumber(invoice.getInvoiceNumber());
		view.setStoreId(invoice.getStoreId());
		view.setStoreCode(store == null ? null : store.getCode());
		view.setStoreName(store == null ? null : store.getName());
		view.setInvoiceDate(copy.getInvoiceDate());
		view.setSubtotal(invoice.getSubtotal());
		view.setTaxAmount(invoice.getTaxAmount());
		view.setTotalAmount(invoice.getTotalAmount());
		view.setBuyerName(invoice.getBuyerName());
		view.setBuyerTaxNumber(invoice.getBuyerTaxNumber());
		view.setBuyerAddress(invoice.getBuyerAddress());
		view.setSellerName(invoice.getSellerName());
		view.setSellerTaxNumber(invoice.getSellerTaxNumber());
		view.setSellerAddress(invoice.getSellerAddress());
		view.setNote(invoice.getNote());
		view.setPaid(invoice.getPaid());
		view.setPaidDate(invoice.getPaidDate());
		view.setPaidNote(invoice.getPaidNote());
		view.setDeliveryNumbers(copy.getDeliveryNumbers());
		view.setLines(withLines ? copy.getLines() : null);
		return view;
	}

	static String cut(String text, int length) {
		return text == null || text.length() <= length ? text : text.substring(0, length);
	}
}
