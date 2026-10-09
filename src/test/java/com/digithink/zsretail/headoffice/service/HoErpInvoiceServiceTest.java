package com.digithink.zsretail.headoffice.service;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.erp.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.DeliveryConfirmationDTO;
import com.digithink.zsretail.headoffice.dto.ErpInvoiceDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceLineType;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceMapping;
import com.digithink.zsretail.headoffice.enumeration.ErpInvoiceStatus;
import com.digithink.zsretail.headoffice.model.HoErpInvoice;
import com.digithink.zsretail.headoffice.model.HoErpInvoiceLine;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoErpInvoiceRepository;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.support.InMemoryCatalogue;

/**
 * Invoices from the ERP, step (b): HoErpInvoiceService over an in-memory ho_erp_invoice (the rules of its queries and of
 * uk_ho_erp_invoice_number), the head office items and stores of {@link InMemoryCatalogue}, and a fake ERP read. Two
 * runs, the highest number per year, the start of a year, the stores (found, none, inactive, not supplied, no customer),
 * held invoices, warnings, a number saved meanwhile, a failure that stops the saving, the confirmations, the page.
 */
class HoErpInvoiceServiceTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 9, 10, 0);

	private InMemoryCatalogue ho;
	private Tables tables;
	private FakeErp erp;
	private HoErpInvoiceService service;
	private Store b;
	private Store c;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		tables = new Tables();
		erp = new FakeErp();
		service = new HoErpInvoiceService(tables.repository(), ho.storeRepository(), ho.itemRepository(), erp,
				TransactionOperations.withoutTransaction(), () -> NOW);
		ho.item("6190000000017", 10.0, null);
		ho.item("6190000000024", 0.0, null);
		b = ho.store("B");
		b.setErpCustomerNo("C-0001");
		b.setOwnerSupply("HEAD_OFFICE");
		c = ho.store("C");
	}

	// ─── Stock points (release 2.2, step 3b) ─────────────────────

	@Test
	@DisplayName("Store with a point: each ERP item without a row in its point is named in a warning; the invoice is sent as before")
	void itemsOutsideTheStockPoint() {
		service.setStockPointItems(ho.stockPointItemRepository());
		Item inPoint = ho.itemByCode("6190000000017").get();
		Item outside = ho.itemByCode("6190000000024").get();
		inPoint.setErpExternalId(inPoint.getItemCode());
		outside.setErpExternalId(outside.getItemCode());
		ho.item("HAND-1", 3.0, null); // made at the head office: every store gets it, never named
		com.digithink.zsretail.headoffice.model.HoStockPoint point = new com.digithink.zsretail.headoffice.model.HoStockPoint();
		point.setCode("FRANCHISE");
		point.setName("Franchise");
		point.setSortOrder(1);
		point.setId(ho.nextId());
		ho.stockPoints.put(point.getId(), point);
		ho.stockPointItem(point, inPoint, 10.0);
		b.setStockPointId(point.getId());
		erp.invoices.add(invoice("FVV26000000101", "C-0001", item(20000, "6190000000017", "6", "36.000"),
				item(30000, "6190000000024", "1", "0"), item(35000, "HAND-1", "1", "0"), item(36000, "NEW-1", "1", "0"),
				other(40000, "5.000")));
		service.run();

		HoErpInvoice forB = saved("FVV26000000101");
		assertEquals(ErpInvoiceStatus.SENT, forB.getStatus(), "a warning only: sent as before");
		assertEquals(b.getId(), forB.getStoreId());
		assertEquals("items not in the catalogue: NEW-1" + HoErpInvoiceService.WARNING_SEPARATOR
				+ HoErpInvoiceService.NOT_IN_STOCK_POINT + "6190000000024", forB.getWarnings());

		b.setStockPointId(null); // the store's point changed later: the warning is not recalculated
		service.matchStoresNow();
		assertEquals(HoErpInvoiceService.NOT_IN_STOCK_POINT + "6190000000024",
				forB.getWarnings().split(HoErpInvoiceService.WARNING_SEPARATOR)[1]);
	}

	@Test
	@DisplayName("Store without a point (a point with no rows): every ERP item line named; an item made at the head office never")
	void storeWithoutStockPoint() {
		service.setStockPointItems(ho.stockPointItemRepository());
		ho.itemByCode("6190000000024").get().setErpExternalId("6190000000024"); // 017 stays hand-made
		twoInvoices();
		service.run();
		HoErpInvoice forB = saved("FVV26000000101");
		assertEquals(ErpInvoiceStatus.SENT, forB.getStatus(), "a warning only: sent as before");
		assertEquals(HoErpInvoiceService.NOT_IN_STOCK_POINT + "6190000000024", forB.getWarnings());
	}

	@Test
	@DisplayName("Without the stock point rows (no ERP catalogue on this head office): no new warning, as before")
	void noStockPointsOnThisHeadOffice() {
		ho.itemByCode("6190000000024").get().setErpExternalId("6190000000024");
		twoInvoices();
		service.run();
		assertNull(saved("FVV26000000101").getWarnings());
	}

	// ─── The ERP and the invoices it gives ───────────────────────

	/** The ERP read: the invoices given for each run; the highest numbers it was asked after, run by run. */
	static final class FakeErp implements java.util.function.Function<Map<String, String>, List<ErpSupplyInvoiceDTO>> {
		final List<ErpSupplyInvoiceDTO> invoices = new ArrayList<>();
		final List<Map<String, String>> asked = new ArrayList<>();
		RuntimeException failure;

		@Override
		public List<ErpSupplyInvoiceDTO> apply(Map<String, String> highestByYear) {
			asked.add(new HashMap<>(highestByYear));
			if (failure != null) {
				throw failure;
			}
			// As the connector: each year after its highest number, by number
			return invoices.stream().filter(i -> {
				String highest = highestByYear.get(i.getYearPrefix());
				return highest == null || i.getNumber().compareTo(highest) > 0;
			}).sorted(Comparator.comparing(ErpSupplyInvoiceDTO::getNumber)).collect(Collectors.toList());
		}
	}

	private static ErpSupplyInvoiceDTO invoice(String number, String customer, ErpSupplyInvoiceDTO.Line... lines) {
		ErpSupplyInvoiceDTO invoice = new ErpSupplyInvoiceDTO();
		invoice.setNumber(number);
		invoice.setYearPrefix(number.substring(0, 5));
		invoice.setCustomerNo(customer);
		invoice.setCustomerName("Name of " + customer);
		invoice.setDocumentDate(LocalDate.of(2026, 3, 2));
		invoice.setPostingDate(LocalDate.of(2026, 3, 3));
		invoice.setTotalExclVat(new BigDecimal("41.000"));
		invoice.setTotalVat(new BigDecimal("7.790"));
		invoice.setTotalInclVat(new BigDecimal("48.790"));
		invoice.setLines(new ArrayList<>(Arrays.asList(lines)));
		return invoice;
	}

	private static ErpSupplyInvoiceDTO.Line item(int lineNo, String code, String quantity, String amount) {
		ErpSupplyInvoiceDTO.Line line = new ErpSupplyInvoiceDTO.Line();
		line.setLineNo(lineNo);
		line.setType(ErpSupplyInvoiceDTO.LineType.ITEM);
		line.setItemCode(code);
		line.setDescription("Item " + code);
		line.setQuantity(new BigDecimal(quantity));
		line.setUnitOfMeasure("UN");
		line.setUnitPrice(new BigDecimal("10"));
		line.setLineDiscountPercent(new BigDecimal("40"));
		line.setLineAmount(new BigDecimal(amount));
		return line;
	}

	private static ErpSupplyInvoiceDTO.Line other(int lineNo, String amount) {
		ErpSupplyInvoiceDTO.Line line = new ErpSupplyInvoiceDTO.Line();
		line.setLineNo(lineNo);
		line.setType(ErpSupplyInvoiceDTO.LineType.OTHER);
		line.setDescription("Transport");
		line.setQuantity(BigDecimal.ONE);
		line.setLineAmount(new BigDecimal(amount));
		return line;
	}

	/** FVV26000000101 for B (C-0001): 6 x item 017 at 36, a tester at 0, transport 5. FVV26000000102 for C-0009. */
	private void twoInvoices() {
		erp.invoices.add(invoice("FVV26000000101", "C-0001", item(20000, "6190000000017", "6", "36.000"),
				item(30000, "6190000000024", "1", "0"), other(40000, "5.000")));
		erp.invoices.add(invoice("FVV26000000102", "C-0009", item(10000, "6190000000017", "2", "12.000")));
	}

	private HoErpInvoice saved(String number) {
		return tables.rows.values().stream().filter(i -> i.getBcNumber().equals(number)).findFirst().orElse(null);
	}

	// ─── Step 1 and step 2 ───────────────────────────────────────

	@Test
	@DisplayName("A run: every new invoice saved without a store first, then B's given to B (SENT), the other left: no store")
	void runSavesThenAssigns() {
		twoInvoices();
		Map<String, Object> summary = service.run();

		assertEquals(2, summary.get("read"));
		assertEquals(2, summary.get("saved"));
		assertEquals(0, summary.get("skipped"));
		assertEquals(0, summary.get("held"));
		assertEquals(1, summary.get("assigned"));
		assertEquals(1, summary.get("unassigned"));
		assertEquals(Collections.singletonMap("NO_STORE", 1), summary.get("unassignedBy"));
		assertEquals(summary, service.getLastRun());

		HoErpInvoice forB = saved("FVV26000000101");
		assertEquals(b.getId(), forB.getStoreId());
		assertEquals(ErpInvoiceStatus.SENT, forB.getStatus());
		assertEquals(ErpInvoiceMapping.ASSIGNED, forB.getMappingStatus());
		assertEquals(NOW, forB.getSentAt());
		assertEquals(NOW, forB.getReadAt());
		assertEquals("FVV26", forB.getYearPrefix());
		assertEquals("C-0001", forB.getCustomerNo());
		assertEquals(41.0, forB.getTotalExclVat());
		assertEquals(7.79, forB.getTotalVat());
		assertEquals(48.79, forB.getTotalInclVat());
		assertFalse(forB.getHeld());
		assertNull(forB.getWarnings());

		HoErpInvoiceLine paid = forB.getLines().get(0);
		assertEquals(ErpInvoiceLineType.ITEM, paid.getLineType());
		assertEquals(Integer.valueOf(6), paid.getQuantity());
		assertEquals(36.0, paid.getLineAmount());
		assertEquals(6.0, paid.getUnitCost(), "Line_Amount / Quantity");
		assertEquals(ho.itemByCode("6190000000017").get().getId(), paid.getItemId());
		assertNull(forB.getLines().get(1).getUnitCost(), "a line at amount 0 has no cost");
		HoErpInvoiceLine transport = forB.getLines().get(2);
		assertEquals(ErpInvoiceLineType.OTHER, transport.getLineType());
		assertNull(transport.getItemCode());
		assertNull(transport.getUnitCost());

		HoErpInvoice noStore = saved("FVV26000000102");
		assertNull(noStore.getStoreId());
		assertEquals(ErpInvoiceStatus.READ, noStore.getStatus());
		assertEquals(ErpInvoiceMapping.NO_STORE, noStore.getMappingStatus());
		assertNull(noStore.getSentAt());
	}

	@Test
	@DisplayName("Two runs: the second asks after the highest number of each year and saves nothing again; the first asks after none")
	void twoRunsNoDuplicates() {
		twoInvoices();
		erp.invoices.add(invoice("FVV25000000900", "C-0001", item(10000, "6190000000017", "1", "6")));
		service.run();
		assertEquals(Collections.emptyMap(), erp.asked.get(0), "nothing here yet: the connector starts each year");

		Map<String, Object> second = service.run();
		Map<String, String> expected = new HashMap<>();
		expected.put("FVV25", "FVV25000000900");
		expected.put("FVV26", "FVV26000000102");
		assertEquals(expected, erp.asked.get(1));
		assertEquals(0, second.get("read"));
		assertEquals(0, second.get("saved"));
		assertEquals(3, tables.rows.size());

		// The ERP answers an invoice again anyway (an overlap): skipped, never twice
		erp.asked.clear();
		ErpSupplyInvoiceDTO again = invoice("FVV26000000101", "C-0001", item(10000, "6190000000017", "1", "6"));
		HoErpInvoiceService replaying = new HoErpInvoiceService(tables.repository(), ho.storeRepository(),
				ho.itemRepository(), highest -> Collections.singletonList(again), TransactionOperations.withoutTransaction(),
				() -> NOW);
		Map<String, Object> third = replaying.run();
		assertEquals(1, third.get("skipped"));
		assertEquals(3, tables.rows.size());
	}

	@Test
	@DisplayName("The stores: inactive, not supplied by the head office, no customer, unknown; supply null accepted; fixed later by match now")
	void stores() {
		c.setErpCustomerNo("C-0002");
		c.setActive(false);
		Store d = ho.store("D");
		d.setErpCustomerNo("C-0003");
		d.setOwnerSupply("LOCAL");
		Store e = ho.store("E");
		e.setErpCustomerNo("c-0004"); // any case; never reported its ownership
		erp.invoices.add(invoice("FVV26000000001", "C-0002", item(10000, "6190000000017", "1", "6")));
		erp.invoices.add(invoice("FVV26000000002", "C-0003", item(10000, "6190000000017", "1", "6")));
		erp.invoices.add(invoice("FVV26000000003", null, item(10000, "6190000000017", "1", "6")));
		erp.invoices.add(invoice("FVV26000000004", "C-0004", item(10000, "6190000000017", "1", "6")));
		erp.invoices.add(invoice("FVV26000000005", "C-0005", item(10000, "6190000000017", "1", "6")));
		Map<String, Object> summary = service.run();

		assertEquals(ErpInvoiceMapping.STORE_INACTIVE, saved("FVV26000000001").getMappingStatus());
		assertEquals(ErpInvoiceMapping.STORE_NOT_SUPPLIED, saved("FVV26000000002").getMappingStatus());
		assertEquals(ErpInvoiceMapping.NO_CUSTOMER, saved("FVV26000000003").getMappingStatus());
		assertEquals(e.getId(), saved("FVV26000000004").getStoreId());
		assertEquals(ErpInvoiceMapping.NO_STORE, saved("FVV26000000005").getMappingStatus());
		assertEquals(1, summary.get("assigned"));
		assertEquals(4, summary.get("unassigned"));

		c.setActive(true);
		d.setOwnerSupply("HEAD_OFFICE");
		Store f = ho.store("F");
		f.setErpCustomerNo("C-0005");
		Map<String, Object> matched = service.matchStoresNow();
		assertEquals(3, matched.get("assigned"));
		assertEquals(1, matched.get("unassigned"), "no customer: still waiting");
		assertEquals(c.getId(), saved("FVV26000000001").getStoreId());
		assertEquals(d.getId(), saved("FVV26000000002").getStoreId());
		assertEquals(f.getId(), saved("FVV26000000005").getStoreId());
		assertEquals(e.getId(), saved("FVV26000000004").getStoreId(), "an assigned invoice keeps its store");
	}

	@Test
	@DisplayName("Held: a quantity not whole or prices including VAT; saved with the reason, never given to its store")
	void held() {
		ErpSupplyInvoiceDTO fraction = invoice("FVV26000000001", "C-0001", item(20000, "6190000000017", "1.5", "9"));
		ErpSupplyInvoiceDTO withVat = invoice("FVV26000000002", "C-0001", item(10000, "6190000000017", "2.000", "12"));
		withVat.setPricesIncludingVat(true);
		erp.invoices.add(fraction);
		erp.invoices.add(withVat);
		Map<String, Object> summary = service.run();

		assertEquals(2, summary.get("held"));
		assertEquals(0, summary.get("assigned"));
		HoErpInvoice first = saved("FVV26000000001");
		assertTrue(first.getHeld());
		assertEquals("line 20000: quantity 1.5 of item 6190000000017 is not a whole number", first.getHoldReason());
		assertNull(first.getLines().get(0).getQuantity());
		assertNull(first.getLines().get(0).getUnitCost());
		assertNull(first.getStoreId());
		assertNull(first.getMappingStatus(), "never searched");
		HoErpInvoice second = saved("FVV26000000002");
		assertEquals("the prices include the VAT", second.getHoldReason());
		assertEquals(Integer.valueOf(2), second.getLines().get(0).getQuantity(), "2.000 is whole");
		assertEquals(0, service.matchStoresNow().get("assigned"));
	}

	@Test
	@DisplayName("Warnings: the connector's kept, the items not in the catalogue listed; the invoice is saved and assigned anyway")
	void warnings() {
		ErpSupplyInvoiceDTO invoice = invoice("FVV26000000001", "C-0001", item(10000, "6190000000017", "1", "6"),
				item(20000, "NEW-1", "2", "8"), item(30000, "NEW-2", "1", "4"));
		invoice.setWarnings(new ArrayList<>(Arrays.asList("Client_Franchise is false",
				"the lines add up to 18.000, Total_Amount_Excl_VAT is 41.000")));
		erp.invoices.add(invoice);
		service.run();
		HoErpInvoice row = saved("FVV26000000001");
		assertEquals("Client_Franchise is false\nthe lines add up to 18.000, Total_Amount_Excl_VAT is 41.000\n"
				+ "items not in the catalogue: NEW-1, NEW-2", row.getWarnings());
		assertNull(row.getLines().get(1).getItemId());
		assertEquals(b.getId(), row.getStoreId());
		ErpInvoiceDTO view = service.get(row.getId()).get();
		assertEquals(3, view.getWarnings().size());
		assertEquals(2, view.getItemsNotInCatalogue());
		assertFalse(view.getLines().get(1).isItemHere());
		assertTrue(view.getLines().get(0).isItemHere());
	}

	@Test
	@DisplayName("A number saved meanwhile by another run is skipped; an unexpected failure stops the saving, step 2 still runs")
	void duplicatesAndFailure() {
		twoInvoices();
		tables.savedMeanwhile = "FVV26000000101";
		Map<String, Object> summary = service.run();
		assertEquals(1, summary.get("skipped"));
		assertEquals(1, summary.get("saved"));
		assertEquals(2, tables.rows.size());

		tables.rows.clear();
		tables.failOn = "FVV26000000102";
		erp.invoices.add(invoice("FVV26000000103", "C-0001", item(10000, "6190000000017", "1", "6")));
		IllegalStateException failed = assertThrows(IllegalStateException.class, () -> service.run());
		assertTrue(failed.getMessage().startsWith("Invoices from the ERP: FVV26000000102 not saved"), failed.getMessage());
		assertNotNull(saved("FVV26000000101"));
		assertNull(saved("FVV26000000103"), "not saved after the failure: read again at the next run");
		assertEquals(b.getId(), saved("FVV26000000101").getStoreId(), "step 2 ran");
		assertTrue(String.valueOf(service.getLastRun().get("failed")).startsWith("FVV26000000102"));

		erp.failure = new IllegalStateException("ERP down");
		tables.failOn = null;
		saved("FVV26000000101").setStoreId(null);
		assertThrows(IllegalStateException.class, () -> service.run());
		assertEquals(b.getId(), saved("FVV26000000101").getStoreId(), "the ERP down: step 2 still runs");
	}

	// ─── Confirmations ───────────────────────────────────────────

	private static DeliveryConfirmationDTO confirmation(String number, int... lineAndQuantity) {
		DeliveryConfirmationDTO confirmation = new DeliveryConfirmationDTO();
		confirmation.setNumber(number);
		confirmation.setReceivedAt("2026-10-09T11:00:00");
		confirmation.setReceivedBy("cashier");
		confirmation.setNote("one missing");
		for (int i = 0; i < lineAndQuantity.length; i += 2) {
			confirmation.getLines().add(new DeliveryConfirmationDTO.Line(lineAndQuantity[i], null, lineAndQuantity[i + 1]));
		}
		return confirmation;
	}

	@Test
	@DisplayName("Confirmations: every item line, received with a difference; again the same: accepted; other quantities, another store, not sent: refused")
	void confirmations() {
		twoInvoices();
		service.run();
		List<SalesCopyResultDTO> results = service.receiveConfirmations(b,
				Arrays.asList(confirmation("FVV26000000101", 20000, 5, 30000, 1)));
		assertTrue(results.get(0).isAccepted(), results.get(0).getMessage());
		HoErpInvoice received = saved("FVV26000000101");
		assertEquals(ErpInvoiceStatus.RECEIVED, received.getStatus());
		assertEquals(Integer.valueOf(5), received.getLines().get(0).getQuantityReceived());
		assertNull(received.getLines().get(2).getQuantityReceived(), "the OTHER line is not received");
		assertTrue(received.getDifference());
		assertEquals(LocalDateTime.of(2026, 10, 9, 11, 0), received.getReceivedAt());
		assertEquals("cashier", received.getReceivedBy());
		assertEquals("one missing", received.getStoreNote());
		assertEquals(NOW, received.getConfirmationReceivedAt());

		List<SalesCopyResultDTO> again = service.receiveConfirmations(b, Arrays.asList(
				confirmation("FVV26000000101", 20000, 5, 30000, 1), confirmation("FVV26000000101", 20000, 6, 30000, 1)));
		assertTrue(again.get(0).isAccepted(), "the same quantities again: accepted, nothing changes");
		assertEquals("already received with other quantities", again.get(1).getMessage());

		assertEquals("unknown invoice FVV26000000101 for this store",
				service.receiveConfirmations(c, Arrays.asList(confirmation("FVV26000000101", 20000, 6, 30000, 1))).get(0)
						.getMessage(), "another store's invoice");
		c.setErpCustomerNo("C-0009"); // C-0009's invoice is still waiting for its store (no match run since)
		assertNull(saved("FVV26000000102").getStoreId());
		assertEquals("unknown invoice FVV26000000102 for this store",
				service.receiveConfirmations(c, Arrays.asList(confirmation("FVV26000000102", 10000, 2))).get(0)
						.getMessage(), "not given to the store yet");
	}

	@Test
	@DisplayName("Confirmations: a line missing, an OTHER line, a line twice, a negative quantity: refused, nothing changes")
	void confirmationRules() {
		twoInvoices();
		service.run();
		List<SalesCopyResultDTO> results = service.receiveConfirmations(b,
				Arrays.asList(confirmation("FVV26000000101", 20000, 6), confirmation("FVV26000000101", 20000, 6, 40000, 1),
						confirmation("FVV26000000101", 20000, 6, 20000, 6), confirmation("FVV26000000101", 20000, -1, 30000, 1),
						confirmation(" ")));
		assertEquals("every item line of the invoice is required", results.get(0).getMessage());
		assertEquals("line 40000 does not match an item line of the invoice", results.get(1).getMessage());
		assertEquals("line 20000 is given twice", results.get(2).getMessage());
		assertEquals("line 20000: quantityReceived must be 0 or more", results.get(3).getMessage());
		assertEquals("number is required", results.get(4).getMessage());
		assertEquals(ErpInvoiceStatus.SENT, saved("FVV26000000101").getStatus());
		DeliveryConfirmationDTO wrongItem = confirmation("FVV26000000101", 20000, 6, 30000, 1);
		wrongItem.getLines().get(0).setItemCode("OTHER-CODE");
		assertFalse(service.receiveConfirmations(b, Arrays.asList(wrongItem)).get(0).isAccepted());
		assertTrue(service.receiveConfirmations(b, null).isEmpty());
	}

	// ─── The page and the copies down ────────────────────────────

	@Test
	@DisplayName("The page: filters by store, status, mapping, held and search; codes and the mapping in words; lines on GET only")
	void page() {
		twoInvoices();
		ErpSupplyInvoiceDTO fraction = invoice("FVV26000000103", "C-0001", item(10000, "6190000000017", "0.5", "3"));
		erp.invoices.add(fraction);
		service.run();

		assertEquals(3L, service.list(null, null, null, null, null, null, null, null, null).get("totalElements"));
		List<?> sent = (List<?>) service.list(b.getId(), "sent", "all", null, null, null, null, null, null)
				.get("content");
		assertEquals(1, sent.size());
		ErpInvoiceDTO row = (ErpInvoiceDTO) sent.get(0);
		assertEquals("FVV26000000101", row.getNumber());
		assertEquals("B", row.getStoreCode());
		assertEquals("SENT", row.getStatus());
		assertEquals("ASSIGNED", row.getMappingStatus());
		assertNull(row.getMappingMessage());
		assertNull(row.getLines(), "no lines in the list");
		assertEquals(3, row.getLineCount());

		ErpInvoiceDTO noStore = (ErpInvoiceDTO) ((List<?>) service
				.list(null, null, "NO_STORE", null, null, null, null, null, null).get("content")).get(0);
		assertEquals("no store for customer C-0009", noStore.getMappingMessage());
		assertEquals(1, ((List<?>) service.list(null, null, null, true, null, null, null, null, null).get("content"))
				.size());
		assertEquals(2, ((List<?>) service.list(null, null, null, false, null, null, null, null, null).get("content"))
				.size());
		assertEquals(1, ((List<?>) service.list(null, null, null, null, null, null, "name of c-0009", null, null)
				.get("content")).size());
		assertThrows(IllegalArgumentException.class,
				() -> service.list(null, "LOST", null, null, null, null, null, null, null));
		assertThrows(IllegalArgumentException.class,
				() -> service.list(null, null, "FOUND", null, null, null, null, null, null));
		assertNotNull(service.list(null, null, null, null, null, null, null, null, null).get("lastRun"));

		ErpInvoiceDTO detail = service.get(saved("FVV26000000101").getId()).get();
		assertEquals(3, detail.getLines().size());
		assertEquals("ITEM", detail.getLines().get(0).getType());
		assertEquals(6.0, detail.getLines().get(0).getUnitCost());
		assertEquals("OTHER", detail.getLines().get(2).getType());
		assertFalse(service.get(999L).isPresent());
	}

	/** The numbers of a page answer, in its order. */
	private static List<String> numbers(Map<String, Object> answer) {
		return ((List<?>) answer.get("content")).stream().map(row -> ((ErpInvoiceDTO) row).getNumber())
				.collect(Collectors.toList());
	}

	@Test
	@DisplayName("The page: with warnings, with difference, alone, together and with status, store and search; false = no filter")
	void pageWarningsAndDifference() {
		twoInvoices(); // 101 for B, 102 without store: no warnings
		erp.invoices.add(invoice("FVV26000000103", "C-0001", item(10000, "NEW-1", "1", "6"))); // B, warnings
		erp.invoices.add(invoice("FVV26000000104", "C-0009", item(10000, "NEW-2", "1", "6"))); // no store, warnings
		erp.invoices.add(invoice("FVV26000000105", "C-0001", item(10000, "6190000000017", "2", "12"))); // B
		service.run();
		service.receiveConfirmations(b, Arrays.asList(confirmation("FVV26000000101", 20000, 5, 30000, 1),
				confirmation("FVV26000000103", 10000, 0), confirmation("FVV26000000105", 10000, 2)));
		assertTrue(saved("FVV26000000101").getDifference());
		assertTrue(saved("FVV26000000103").getDifference());
		assertFalse(saved("FVV26000000105").getDifference());
		assertNull(saved("FVV26000000104").getDifference(), "not received");

		Map<String, Object> warnings = service.list(null, null, null, null, true, null, null, null, null);
		assertEquals(Arrays.asList("FVV26000000104", "FVV26000000103"), numbers(warnings), "newest first");
		assertEquals(2L, warnings.get("totalElements"));
		Map<String, Object> difference = service.list(null, null, null, null, null, true, null, null, null);
		assertEquals(Arrays.asList("FVV26000000103", "FVV26000000101"), numbers(difference));
		assertEquals(2L, difference.get("totalElements"));
		Map<String, Object> both = service.list(null, null, null, null, true, true, null, null, null);
		assertEquals(Arrays.asList("FVV26000000103"), numbers(both));
		assertEquals(1L, both.get("totalElements"));
		assertEquals(5L, service.list(null, null, null, null, false, false, null, null, null).get("totalElements"),
				"false: no filter");

		assertEquals(1L, service.list(b.getId(), null, null, null, true, null, null, null, null).get("totalElements"));
		assertEquals(0L, service.list(c.getId(), null, null, null, true, true, null, null, null).get("totalElements"));
		Map<String, Object> toSend = service.list(null, "READ", null, null, true, null, null, null, null);
		assertEquals(Arrays.asList("FVV26000000104"), numbers(toSend));
		assertEquals(1L, toSend.get("totalElements"));
		assertEquals(2L, service.list(null, "RECEIVED", null, null, null, true, null, null, null).get("totalElements"));
		assertEquals(0L, service.list(null, "READ", null, null, null, true, null, null, null).get("totalElements"));
		Map<String, Object> searched = service.list(null, null, null, null, true, null, "name of c-0009", null, null);
		assertEquals(Arrays.asList("FVV26000000104"), numbers(searched));
		assertEquals(1L, searched.get("totalElements"));
		assertEquals(0L, service.list(null, null, null, null, null, true, "c-0009", null, null).get("totalElements"));
		assertEquals(1L, service.list(b.getId(), "RECEIVED", null, null, true, true, "fvv26000000103", null, null)
				.get("totalElements"));
	}

	// ─── Step (c): the copies down ───────────────────────────────

	/** The service with the copies down (step c) over the same tables, and its feed. */
	private CopiesDownFeed withFeed(InMemoryDownTables down, HoErpInvoiceService[] serviceOut) {
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		HoErpInvoiceService copying = new HoErpInvoiceService(tables.repository(), ho.storeRepository(),
				ho.itemRepository(), erp, TransactionOperations.withoutTransaction(), () -> NOW, () -> holder[0],
				"Happyness");
		holder[0] = down.feed(Collections.singletonList(copying));
		holder[0].initialise();
		serviceOut[0] = copying;
		return holder[0];
	}

	private long supplyChanges(InMemoryDownTables down) {
		return down.changes.stream().filter(ch -> ch.getDomain() == DataDomain.SUPPLY).count();
	}

	@Test
	@DisplayName("Copies down: an assigned invoice recorded for its store only (ERPINV:), answered by codes, never held nor another store's")
	void copiesDown() {
		InMemoryDownTables down = new InMemoryDownTables();
		HoErpInvoiceService[] copying = new HoErpInvoiceService[1];
		CopiesDownFeed feed = withFeed(down, copying);
		twoInvoices();
		erp.invoices.add(invoice("FVV26000000103", "C-0001", item(10000, "6190000000017", "0.5", "3"))); // held
		copying[0].run();
		assertEquals(1, supplyChanges(down), "only B's invoice: the other has no store, the held one is never sent");

		CopiesDownAnswerDTO page = feed.pull(b, "SUPPLY", "", 100);
		assertEquals(1, page.getRecords().size());
		com.fasterxml.jackson.databind.JsonNode copy = page.getRecords().get(0);
		assertEquals("ERP_INVOICE", copy.get("kind").asText());
		assertEquals("FVV26000000101", copy.get("invoiceNumber").asText());
		assertFalse(copy.has("number"), "never 'number': a store that does not know the kind refuses it");
		assertEquals("Happyness", copy.get("sellerName").asText());
		assertEquals("C-0001", copy.get("customerNo").asText());
		assertEquals("2026-10-09T10:00:00", copy.get("sentAt").asText());
		assertEquals(41.0, copy.get("totalExclVat").asDouble());
		assertEquals(3, copy.get("lines").size());
		assertEquals("ITEM", copy.get("lines").get(0).get("lineType").asText());
		assertEquals(6, copy.get("lines").get(0).get("quantity").asInt());
		assertEquals(6.0, copy.get("lines").get(0).get("unitCost").asDouble());
		assertEquals("OTHER", copy.get("lines").get(2).get("lineType").asText());
		assertTrue(copy.get("lines").get(2).get("itemCode").isNull());
		assertTrue(feed.pull(c, "SUPPLY", "", 100).getRecords().isEmpty(), "another store sees nothing");

		assertTrue(copying[0].load(c, Arrays.asList("ERPINV:FVV26000000101")).isEmpty(), "another store's invoice");
		assertTrue(copying[0].load(b, Arrays.asList("ERPINV:FVV26000000103", "BL:BL-000001", "INV:X")).isEmpty(),
				"a held invoice, the codes of the BLs and invoices of the head office");
		assertEquals(Collections.singleton("ERPINV:FVV26000000101"), copying[0].currentTargets().keySet());
		assertEquals(Collections.singleton(b.getId()),
				copying[0].currentTargets().get("ERPINV:FVV26000000101").getStoreIds());

		// Received: still answered (the store keeps its copy, a pull again changes nothing)
		copying[0].receiveConfirmations(b, Arrays.asList(confirmation("FVV26000000101", 20000, 6, 30000, 1)));
		assertEquals(1, copying[0].load(b, Arrays.asList("ERPINV:FVV26000000101")).size());
	}

	@Test
	@DisplayName("Backfill: invoices assigned before step (c) get their change row at the next start, once")
	void backfillAfterRestart() {
		twoInvoices();
		service.run(); // step (b): assigned, nothing recorded
		assertEquals(b.getId(), saved("FVV26000000101").getStoreId());

		InMemoryDownTables down = new InMemoryDownTables();
		HoErpInvoiceService[] copying = new HoErpInvoiceService[1];
		CopiesDownFeed feed = withFeed(down, copying); // the start of the step (c) build
		assertEquals(1, supplyChanges(down));
		assertEquals("FVV26000000101", feed.pull(b, "SUPPLY", "", 100).getRecords().get(0).get("invoiceNumber").asText());
		feed.initialise(); // another start
		assertEquals(1, supplyChanges(down), "never twice");
	}

	@Test
	@DisplayName("Confirmations up: HeadOfficeSupplyAPI hands them to this head office's receiver (by the ERP number)")
	void confirmationsRouted() {
		twoInvoices();
		service.run();
		com.digithink.zsretail.headoffice.controller.HeadOfficeSupplyAPI api =
				new com.digithink.zsretail.headoffice.controller.HeadOfficeSupplyAPI(service, null);
		List<SalesCopyResultDTO> results = api
				.confirmations(b, Arrays.asList(confirmation("FVV26000000101", 20000, 6, 30000, 1))).getResults();
		assertTrue(results.get(0).isAccepted(), results.get(0).getMessage());
		assertEquals(ErpInvoiceStatus.RECEIVED, saved("FVV26000000101").getStatus());
		assertFalse(saved("FVV26000000101").getDifference());
		assertEquals(DataDomain.SUPPLY, service.getDomain());
	}

	// ─── In-memory ho_erp_invoice ────────────────────────────────

	/** ho_erp_invoice with its lines (saved with the header as the cascade does), uk_ho_erp_invoice_number applied. */
	final class Tables {
		final Map<Long, HoErpInvoice> rows = new LinkedHashMap<>();
		/** A number another run saves just before this one's save (the unique key then refuses it). */
		String savedMeanwhile;
		/** A number whose save fails unexpectedly. */
		String failOn;

		HoErpInvoiceRepository repository() {
			return proxy(HoErpInvoiceRepository.class, (method, args) -> {
				switch (method) {
				case "existsByBcNumber":
					return rows.values().stream().anyMatch(i -> i.getBcNumber().equals(args[0]));
				case "findHighestByYear": {
					Map<String, String> highest = new HashMap<>();
					for (HoErpInvoice i : rows.values()) {
						if (i.getYearPrefix() != null) {
							highest.merge(i.getYearPrefix(), i.getBcNumber(), (x, y) -> x.compareTo(y) >= 0 ? x : y);
						}
					}
					return highest.entrySet().stream().map(e -> new Object[] { e.getKey(), e.getValue() })
							.collect(Collectors.toList());
				}
				case "findIdsToAssign":
					return rows.values().stream().filter(i -> i.getStoreId() == null && !i.getHeld())
							.sorted(Comparator.comparing(HoErpInvoice::getBcNumber)).map(HoErpInvoice::getId)
							.collect(Collectors.toList());
				case "findById":
				case "findForUpdate":
					return Optional.ofNullable(rows.get(args[0]));
				case "findForStore": {
					java.util.Collection<?> numbers = (java.util.Collection<?>) args[1];
					java.util.Collection<?> statuses = (java.util.Collection<?>) args[2];
					return rows.values().stream().filter(i -> Objects.equals(i.getStoreId(), args[0])
							&& numbers.contains(i.getBcNumber()) && statuses.contains(i.getStatus()) && !i.getHeld())
							.collect(Collectors.toList());
				}
				case "findAssignedTargets":
					return rows.values().stream().filter(i -> i.getStoreId() != null && !i.getHeld())
							.sorted(Comparator.comparing(HoErpInvoice::getBcNumber))
							.map(i -> new Object[] { i.getBcNumber(), i.getStoreId() }).collect(Collectors.toList());
				case "findForUpdateByStoreAndNumber":
					return rows.values().stream()
							.filter(i -> Objects.equals(i.getStoreId(), args[0]) && i.getBcNumber().equals(args[1]))
							.findFirst();
				case "saveAndFlush":
				case "save": {
					HoErpInvoice invoice = (HoErpInvoice) args[0];
					if (invoice.getBcNumber().equals(failOn)) {
						throw new IllegalArgumentException("broken row");
					}
					if (invoice.getBcNumber().equals(savedMeanwhile)) {
						savedMeanwhile = null;
						HoErpInvoice other = new HoErpInvoice();
						other.setBcNumber(invoice.getBcNumber());
						other.setId(ho.nextId());
						rows.put(other.getId(), other);
					}
					if (invoice.getId() == null
							&& rows.values().stream().anyMatch(i -> i.getBcNumber().equals(invoice.getBcNumber()))) {
						throw new DataIntegrityViolationException("uk_ho_erp_invoice_number: " + invoice.getBcNumber());
					}
					if (invoice.getId() == null) {
						invoice.setId(ho.nextId());
					}
					for (HoErpInvoiceLine line : invoice.getLines()) {
						if (line.getId() == null) {
							line.setId(ho.nextId());
						}
					}
					rows.put(invoice.getId(), invoice);
					return invoice;
				}
				case "findPage": {
					long storeId = (Long) args[0];
					boolean anyStatus = (Long) args[1] == 1L;
					boolean anyMapping = (Long) args[3] == 1L;
					long held = (Long) args[5];
					boolean withWarnings = (Long) args[6] == 1L;
					boolean withDifference = (Long) args[7] == 1L;
					String search = (String) args[8];
					Pageable page = (Pageable) args[9];
					List<HoErpInvoice> content = rows.values().stream()
							.filter(i -> storeId == 0L || Objects.equals(i.getStoreId(), storeId))
							.filter(i -> anyStatus || i.getStatus() == args[2])
							.filter(i -> anyMapping || i.getMappingStatus() == args[4])
							.filter(i -> held == 0L || (held == 1L) == i.getHeld())
							.filter(i -> !withWarnings || (i.getWarnings() != null && !i.getWarnings().isEmpty()))
							.filter(i -> !withDifference || Boolean.TRUE.equals(i.getDifference()))
							.filter(i -> search == null || like(i.getBcNumber(), search) || like(i.getCustomerNo(), search)
									|| like(i.getCustomerName(), search))
							.sorted(Comparator.comparing(HoErpInvoice::getBcNumber).reversed()).collect(Collectors.toList());
					return new PageImpl<>(content, page, content.size());
				}
				default:
					return UNHANDLED;
				}
			});
		}

		private boolean like(String value, String pattern) {
			return value != null && value.toLowerCase().contains(pattern.replace("%", ""));
		}
	}
}
