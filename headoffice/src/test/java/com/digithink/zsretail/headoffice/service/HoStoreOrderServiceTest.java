package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.link.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.core.link.dto.SalesCopyResultDTO;
import com.digithink.zsretail.core.link.dto.StoreOrderDTO;
import com.digithink.zsretail.core.link.dto.StoreOrderStatusCopyDTO;
import com.digithink.zsretail.core.link.dto.VariantStockDTO;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.erp.api.ErpNotSupportedException;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderResult;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.digithink.zsretail.headoffice.dto.HoStoreOrderDTO;
import com.digithink.zsretail.headoffice.enumeration.HoStoreOrderStatus;
import com.digithink.zsretail.headoffice.enumeration.PostedMode;
import com.digithink.zsretail.headoffice.model.HoStoreOrder;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoErpInvoiceRepository.OrderInvoice;
import com.digithink.zsretail.headoffice.support.InMemoryCatalogue;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * 3.1.1, the stores' orders at the head office: the real HoStoreOrderService over in-memory tables and a real
 * CopiesDownFeed. The reception takes an order once per store and number and refuses (accepted false, nothing saved) a
 * store without an ERP customer number, another location, an empty order; the variant stock asks the connector with the
 * store's code (503 without it); "mark posted" (MANUAL mode) and "reject" (both modes) only on an order waiting; every
 * change the store sees records ORD:&lt;number&gt; for that store only (REJECTED going down as ERROR); the SUPPLY domain
 * answers the orders beside another provider of the domain.
 */
class HoStoreOrderServiceTest {

	static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 9, 0);
	static final String FIRST = "B-2026-000001";

	private InMemoryCatalogue ho;
	private InMemoryHoStoreOrders tables;
	private InMemoryDownTables down;
	private CopiesDownFeed feed;
	private HoStoreOrderService service;
	private Store b;
	private Store c;
	private final List<String> stockAsked = new ArrayList<>();
	private RuntimeException stockFailure;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		tables = new InMemoryHoStoreOrders(500_000);
		b = ho.store("B");
		b.setErpCustomerNo("C-0001");
		c = ho.store("C");
		c.setErpCustomerNo("C-0002");
		service = service(false);
	}

	/** The service in MANUAL (false) or ERP (true) mode, with the copies down over in-memory tables. */
	HoStoreOrderService service(boolean byErp) {
		down = new InMemoryDownTables();
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		HoStoreOrderService made = new HoStoreOrderService(tables.repository(), ho.storeRepository(),
				(itemNo, location, customerNo) -> {
					stockAsked.add(itemNo + "@" + location + "/" + customerNo);
					if (stockFailure != null) {
						throw stockFailure;
					}
					LinkedHashMap<String, String> details = new LinkedHashMap<>();
					details.put("Prix net", "12.5");
					details.put("Stock", "48");
					return Arrays.asList(new ErpVariantStockDTO(itemNo, "S", new BigDecimal("4.000"), details, 160),
							new ErpVariantStockDTO(itemNo, "", null));
				}, () -> holder[0], TransactionOperations.withoutTransaction(), () -> NOW, byErp);
		holder[0] = down.feed(Arrays.asList(made, erpInvoices()));
		holder[0].initialise();
		feed = holder[0];
		return made;
	}

	/** Another provider of the SUPPLY domain (the ERP's invoices), answering its own codes only. */
	static DownDomainProvider erpInvoices() {
		return new DownDomainProvider() {
			@Override
			public DataDomain getDomain() {
				return DataDomain.SUPPLY;
			}

			@Override
			public Map<String, JsonNode> load(Store store, List<String> codes) {
				Map<String, JsonNode> copies = new HashMap<>();
				if (codes.contains("ERPINV:FVE26001")) {
					copies.put("ERPINV:FVE26001", JsonNodeFactory.instance.objectNode().put("kind", "ERP_INVOICE"));
				}
				return copies;
			}

			@Override
			public Map<String, StoreTargets> currentTargets() {
				return Collections.singletonMap("ERPINV:FVE26001", StoreTargets.all());
			}
		};
	}

	static StoreOrderDTO order(String number, String location, StoreOrderDTO.Line... lines) {
		StoreOrderDTO order = new StoreOrderDTO();
		order.setNumber(number);
		order.setLocationCode(location);
		order.setNote("Livrer lundi");
		order.setCreatedAt("2026-10-27T09:00:00");
		order.setSentAt("2026-10-27T10:00:00");
		order.setLines(Arrays.asList(lines));
		return order;
	}

	static StoreOrderDTO.Line line(String itemCode, String variant, int quantity) {
		String navItemNo = itemCode.contains("~") ? itemCode.substring(0, itemCode.indexOf('~')) : null;
		return new StoreOrderDTO.Line(itemCode, navItemNo, variant, "CHEMISE", "Taille " + variant, Quantities.of(quantity));
	}

	private HoStoreOrder received() {
		assertTrue(service.receive(b, order(FIRST, "B", line("70061~S", "S", 2), line("70061~L", "L", 1))).isAccepted());
		return tables.byNumber(FIRST);
	}

	private List<JsonNode> pulled(Store store) {
		CopiesDownAnswerDTO page = feed.pull(store, "SUPPLY", "", 100);
		return page.getRecords();
	}

	private JsonNode orderCopy(Store store) {
		return pulled(store).stream().filter(r -> "STORE_ORDER".equals(r.path("kind").asText())).findFirst().orElse(null);
	}

	// ─── Reception ───────────────────────────────────────────────

	@Test
	@DisplayName("Reception: the order saved SENT for its store, the store's send time, lines by codes, the totals")
	void receive() {
		HoStoreOrder order = received();
		assertEquals(HoStoreOrderStatus.SENT, order.getStatus());
		assertEquals(b.getId(), order.getStoreId());
		assertEquals("B", order.getLocationCode());
		assertEquals(NOW, order.getReceivedAt());
		assertEquals(LocalDateTime.of(2026, 10, 27, 10, 0), order.getSentAt());
		assertEquals("Livrer lundi", order.getNote());
		assertEquals(2, order.getLineCount());
		assertEquals(0, new BigDecimal("3").compareTo(order.getTotalQty()));
		assertEquals("70061~S", order.getLines().get(0).getItemCode());
		assertEquals("70061", order.getLines().get(0).getNavItemNo());
		assertEquals("S", order.getLines().get(0).getVariantCode());
		assertNull(orderCopy(b), "nothing goes down at the reception: the store has SENT already");
	}

	@Test
	@DisplayName("The same number again answers the order already here, never a second one; another store's number is its own")
	void sameNumberTwice() {
		received();
		SalesCopyResultDTO again = service.receive(b, order(FIRST, "B", line("70061~S", "S", 9)));
		assertTrue(again.isAccepted());
		assertEquals(FIRST, again.getDocumentNumber());
		assertEquals(1, tables.orders.size());
		assertEquals(2, tables.byNumber(FIRST).getLineCount(), "the order already here is kept as it was");
		assertTrue(service.receive(c, order(FIRST, "C", line("70061~S", "S", 1))).isAccepted());
		assertEquals(2, tables.orders.size());
	}

	@Test
	@DisplayName("Refused, nothing saved: no ERP customer number, another location, no line, a quantity of 0, no number")
	void refusals() {
		b.setErpCustomerNo(" ");
		SalesCopyResultDTO noCustomer = service.receive(b, order(FIRST, "B", line("70061~S", "S", 2)));
		assertFalse(noCustomer.isAccepted());
		assertEquals(FIRST, noCustomer.getDocumentNumber());
		assertTrue(noCustomer.getMessage().startsWith("Store B has no ERP customer number"), noCustomer.getMessage());
		b.setErpCustomerNo("C-0001");
		assertFalse(service.receive(b, order(FIRST, "C", line("70061~S", "S", 2))).isAccepted(), "another location");
		assertFalse(service.receive(b, order(FIRST, "B")).isAccepted(), "no line");
		assertFalse(service.receive(b, order(FIRST, "B", line("70061~S", "S", 0))).isAccepted(), "0");
		assertFalse(service.receive(b, order(" ", "B", line("70061~S", "S", 1))).isAccepted(), "no number");
		assertTrue(tables.orders.isEmpty());
		assertTrue(service.receive(b, order(FIRST, null, line("70061~S", "S", 1))).isAccepted(), "no location: the store's");
	}

	// ─── Variant stock ───────────────────────────────────────────

	@Test
	@DisplayName("Variant stock: the connector asked with the store's code and ERP customer number; details and size order"
			+ " passed on; 503 without it; 400 without a number")
	void variantStock() {
		List<VariantStockDTO> rows = service.variantStock(b, " 70061 ");
		assertEquals(Collections.singletonList("70061@B/C-0001"), stockAsked);
		assertEquals(2, rows.size());
		assertEquals("S", rows.get(0).getVariantCode());
		assertEquals(0, new BigDecimal("4").compareTo(rows.get(0).getQuantity()));
		assertEquals(Arrays.asList("Prix net", "Stock"), new ArrayList<>(rows.get(0).getDetails().keySet()), "in order");
		assertEquals(Integer.valueOf(160), rows.get(0).getSizeOrder());
		assertNull(rows.get(1).getQuantity(), "a row without quantity: unknown, never 0");
		assertNull(rows.get(1).getDetails());
		assertNull(rows.get(1).getSizeOrder());
		stockFailure = new ErpNotSupportedException("The ERP connector in effect does not take the stores' orders");
		HoStoreOrderService.ErpUnavailableException e = assertThrows(HoStoreOrderService.ErpUnavailableException.class,
				() -> service.variantStock(b, "70061"));
		assertTrue(e.getMessage().contains("does not take the stores' orders"), e.getMessage());
		assertThrows(IllegalArgumentException.class, () -> service.variantStock(b, " "));
	}

	@Test
	@DisplayName("Variant stock of a store without an ERP customer number: the reception's refusal, the ERP not asked")
	void variantStockWithoutCustomerNo() {
		b.setErpCustomerNo(" ");
		HoStoreOrderService.StoreRefusedException e = assertThrows(HoStoreOrderService.StoreRefusedException.class,
				() -> service.variantStock(b, "70061"));
		assertEquals(service.receive(b, order(FIRST, "B", line("70061~S", "S", 2))).getMessage(), e.getMessage());
		assertTrue(e.getMessage().startsWith("Store B has no ERP customer number"), e.getMessage());
		assertTrue(stockAsked.isEmpty());
	}

	// ─── Admin actions and the copies down ───────────────────────

	@Test
	@DisplayName("Mark posted (MANUAL): POSTED with the number typed, recorded for its store only, beside the other SUPPLY records")
	void markPosted() {
		HoStoreOrder order = received();
		HoStoreOrderDTO view = service.markPosted(order.getId(), " SO-0042 ", "admin").get();
		assertEquals("POSTED", view.getStatus());
		assertEquals("SO-0042", view.getNavOrderNo());
		assertEquals("MANUAL", view.getPostedMode());
		assertEquals(NOW, view.getPostedAt());
		assertEquals("admin", view.getUpdatedBy());
		assertEquals("B", view.getStoreCode());
		assertEquals(PostedMode.MANUAL, order.getPostedMode());

		JsonNode copy = orderCopy(b);
		assertEquals(FIRST, copy.get("orderNumber").asText());
		assertEquals("POSTED", copy.get("status").asText());
		assertEquals("SO-0042", copy.get("navOrderNo").asText());
		assertFalse(copy.has("number"), "never 'number': a store of an older build refuses the record");
		assertTrue(pulled(b).stream().anyMatch(r -> "ERP_INVOICE".equals(r.path("kind").asText())),
				"the other provider of SUPPLY answers its own records");
		assertNull(orderCopy(c), "another store sees nothing");

		assertThrows(IllegalStateException.class, () -> service.markPosted(order.getId(), "SO-1", "admin"), "posted");
		assertThrows(IllegalArgumentException.class, () -> service.markPosted(order.getId(), " ", "admin"));
		assertFalse(service.markPosted(999L, "SO-1", "admin").isPresent());
	}

	@Test
	@DisplayName("Delivered: the POSTED order of the invoice's orderNo DELIVERED with the invoice and its posting date,"
			+ " recorded for its store; not another store's customer, not twice, not an order waiting")
	void delivered() {
		HoStoreOrder order = received();
		assertEquals(0, service.deliveredBy("SO-0042", "FVE2600009", LocalDate.of(2026, 10, 29), "C-0001"),
				"waiting: no ERP number yet");
		service.markPosted(order.getId(), "SO-0042", "admin");
		assertEquals(0, service.deliveredBy("SO-0042", "FVE2600009", LocalDate.of(2026, 10, 29), "C-0002"),
				"the invoice of another store's customer");
		assertEquals(HoStoreOrderStatus.POSTED, order.getStatus());
		assertEquals(0, service.deliveredBy("SO-0043", "FVE2600009", LocalDate.of(2026, 10, 29), "C-0001"));

		assertEquals(1, service.deliveredBy(" SO-0042 ", " FVE2600009 ", LocalDate.of(2026, 10, 29), "c-0001"));
		HoStoreOrderDTO view = service.get(order.getId()).get();
		assertEquals("DELIVERED", view.getStatus());
		assertEquals("FVE2600009", view.getInvoiceNo());
		assertEquals(LocalDate.of(2026, 10, 29), view.getDeliveredAt());
		assertEquals("SO-0042", view.getNavOrderNo(), "kept");
		JsonNode copy = orderCopy(b);
		assertEquals("DELIVERED", copy.get("status").asText());
		assertEquals("FVE2600009", copy.get("invoiceNo").asText());
		assertEquals("2026-10-29", copy.get("deliveredAt").asText());
		assertEquals("SO-0042", copy.get("navOrderNo").asText());
		assertNull(orderCopy(c));
		assertEquals(Collections.singleton(StoreOrderCodes.of(FIRST)), service.currentTargets().keySet());

		assertEquals(0, service.deliveredBy("SO-0042", "FVE2600010", LocalDate.of(2026, 10, 30), "C-0001"),
				"delivered already: the first invoice kept");
		assertEquals("FVE2600009", order.getInvoiceNo());
		assertThrows(IllegalStateException.class, () -> service.reject(order.getId(), "late", "admin"));
		assertEquals(1, ((List<?>) service.list(null, "DELIVERED", null, null, null, null, null, 0, 20).get("content")).size());
	}

	@Test
	@DisplayName("Posted after its invoice was read (MANUAL): mark posted finds the invoice of that ERP number and the order"
			+ " is DELIVERED at once, the store sees DELIVERED; another store's customer's invoice is passed over")
	void markPostedAfterTheInvoice() {
		List<String> asked = new ArrayList<>();
		service.useOrderInvoices(orderNo -> {
			asked.add(orderNo);
			return "SO-0042".equals(orderNo)
					? Arrays.asList(new OrderInvoice("FVE2600007", LocalDate.of(2026, 10, 27), "C-0002"),
							new OrderInvoice("FVE2600008", LocalDate.of(2026, 10, 28), "C-0001"))
					: Collections.<OrderInvoice>emptyList();
		});
		HoStoreOrder order = received();
		HoStoreOrderDTO view = service.markPosted(order.getId(), " SO-0042 ", "admin").get();
		assertEquals(Collections.singletonList("SO-0042"), asked);
		assertEquals("DELIVERED", view.getStatus());
		assertEquals("FVE2600008", view.getInvoiceNo(), "FVE2600007 is store C's customer's");
		assertEquals(LocalDate.of(2026, 10, 28), view.getDeliveredAt());
		assertEquals("SO-0042", view.getNavOrderNo());
		assertEquals("MANUAL", view.getPostedMode());
		JsonNode copy = orderCopy(b);
		assertEquals("DELIVERED", copy.get("status").asText());
		assertEquals("FVE2600008", copy.get("invoiceNo").asText());

		assertTrue(service.receive(b, order("B-2026-000002", "B", line("70061~S", "S", 1))).isAccepted());
		HoStoreOrder second = tables.byNumber("B-2026-000002");
		assertEquals("POSTED", service.markPosted(second.getId(), "SO-0099", "admin").get().getStatus(),
				"no invoice of that number yet: POSTED, the import delivers it later");
		assertNull(second.getInvoiceNo());
	}

	@Test
	@DisplayName("Posted by the job after its invoice was read (ERP): DELIVERED in the posting's transaction, with NAV's"
			+ " answer kept; a failed posting looks for nothing")
	void postedByTheJobAfterTheInvoice() {
		HoStoreOrderService erp = service(true);
		List<String> asked = new ArrayList<>();
		erp.useOrderInvoices(orderNo -> {
			asked.add(orderNo);
			return Collections.singletonList(new OrderInvoice("FVE2600011", null, null));
		});
		assertTrue(erp.receive(b, order(FIRST, "B", line("70061~S", "S", 2))).isAccepted());
		HoStoreOrder order = tables.byNumber(FIRST);
		erp.recordPosting(order.getId(), null, "NAV down");
		assertTrue(asked.isEmpty(), "ERROR: no ERP number to look for");
		HoStoreOrderDTO view = erp.recordPosting(order.getId(), "CV26000004", null).get();
		assertEquals(Collections.singletonList("CV26000004"), asked);
		assertEquals("DELIVERED", view.getStatus());
		assertEquals("FVE2600011", view.getInvoiceNo());
		assertEquals(NOW.toLocalDate(), view.getDeliveredAt(), "no posting date: the head office's day");
		assertEquals("ERP", view.getPostedMode());
		assertEquals("DELIVERED", orderCopy(b).get("status").asText());
	}

	@Test
	@DisplayName("Delivered without a posting date: the head office's day; without a customer on the invoice: any store")
	void deliveredWithoutDateOrCustomer() {
		HoStoreOrder order = received();
		service.markPosted(order.getId(), "SO-0042", "admin");
		assertEquals(1, service.deliveredBy("SO-0042", "FVE2600009", null, null));
		assertEquals(NOW.toLocalDate(), order.getDeliveredAt());
		assertEquals(0, service.deliveredBy(" ", "FVE2600009", null, null));
		assertEquals(0, service.deliveredBy("SO-0042", " ", null, null));
	}

	/** A colis row as the store sends it: one colis of 43ZFCHD17 = 2 pieces of S + 3 of M (content 5). */
	static StoreOrderDTO.Colis chemise(int nbColis) {
		return new StoreOrderDTO.Colis(" 43ZFCHD17 ", "CHEMISE", nbColis, 5, 30,
				Arrays.asList(new StoreOrderDTO.ColisPart("43ZFCHD17~001", "001", 2, "S", "Taille S", "04040", "BLEU"),
						new StoreOrderDTO.ColisPart("43ZFCHD17~002", "002", 3)));
	}

	@Test
	@DisplayName("3.1.1, colis of the item: received beside the lines (totals apart) with their composition by item code,"
			+ " alone too; posted as a colis line of the item; NAV's answer (colis and pieces accepted, colis number,"
			+ " message) on the colis row, never on a pieces line; down with the status")
	void colis() {
		StoreOrderDTO dto = order(FIRST, "B", line("70061~S", "S", 2));
		dto.getColis().add(chemise(2));
		assertTrue(service.receive(b, dto).isAccepted());
		HoStoreOrder order = tables.byNumber(FIRST);
		assertEquals(1, order.getColis().size());
		assertEquals("43ZFCHD17", order.getColis().get(0).getNavItemNo());
		assertEquals(Integer.valueOf(5), order.getColis().get(0).getColisContent());
		assertEquals(Integer.valueOf(30), order.getColis().get(0).getColisAvailable());
		assertEquals(2, order.getTotalColis());
		assertEquals(0, new BigDecimal("2").compareTo(order.getTotalQty()), "the pieces of the lines only");
		HoStoreOrderDTO view = service.get(order.getId()).get();
		assertEquals(2, view.getTotalColis());
		assertEquals("CHEMISE", view.getColis().get(0).getDescription());
		assertEquals(Arrays.asList("43ZFCHD17~001:2", "43ZFCHD17~002:3"), view.getColis().get(0).getComposition().stream()
				.map(part -> part.getItemCode() + ":" + part.getQtyPerColis()).collect(java.util.stream.Collectors.toList()));
		assertEquals("Taille S", view.getColis().get(0).getComposition().get(0).getSizeDescription());
		assertEquals("BLEU", view.getColis().get(0).getComposition().get(0).getColorDescription());

		ErpSalesOrderDTO sales = HoStoreOrderService.salesOrderOf(order, b);
		assertEquals(2, sales.getLines().size());
		ErpSalesOrderDTO.Line colisLine = sales.getLines().get(1);
		assertTrue(colisLine.isColis());
		assertEquals("43ZFCHD17", colisLine.getItemNo());
		assertEquals("", colisLine.getVariantCode(), "a colis is of the item");
		assertEquals(Integer.valueOf(2), colisLine.getNbColis());
		assertEquals(0, new BigDecimal("10").compareTo(colisLine.getQuantity()), "2 x 5 pieces, informative");

		ErpSalesOrderResult answer = new ErpSalesOrderResult();
		answer.getLines().add(new ErpSalesOrderResult.Line("70061", "S", new BigDecimal("2"), null));
		answer.getLines().add(new ErpSalesOrderResult.Line("43ZFCHD17", "", new BigDecimal("5"), "Colis partiel", true, 1,
				"COL-0007"));
		HoStoreOrderService.keepAnswer(order, answer);
		assertEquals(Integer.valueOf(1), order.getColis().get(0).getNavAcceptedColis());
		assertEquals(0, new BigDecimal("5").compareTo(order.getColis().get(0).getNavAcceptedQty()));
		assertEquals("COL-0007", order.getColis().get(0).getNavColisNumber());
		assertEquals("Colis partiel", order.getColis().get(0).getNavMessage());
		assertEquals(0, new BigDecimal("2").compareTo(order.getLines().get(0).getNavAcceptedQty()));
		StoreOrderStatusCopyDTO copy = HoStoreOrderService.copyOf(order);
		assertEquals(1, copy.getColis().size());
		assertEquals(Integer.valueOf(1), copy.getColis().get(0).getAcceptedColis());
		assertEquals(0, new BigDecimal("5").compareTo(copy.getColis().get(0).getAcceptedQty()));
		assertEquals("COL-0007", copy.getColis().get(0).getColisNumber());
		assertEquals("Colis partiel", copy.getColis().get(0).getMessage());

		StoreOrderDTO only = order("B-2026-000002", "B");
		only.getColis().add(new StoreOrderDTO.Colis("43ZFCHD17", null, 1, null, null, null));
		assertTrue(service.receive(b, only).isAccepted(), "colis alone, no composition known: an order");
		assertNull(HoStoreOrderService.copyOf(tables.byNumber("B-2026-000002")).getColis(), "no answer yet: none down");
	}

	@Test
	@DisplayName("3.1.1, colis refused: none at all with no line, 0 colis, no item number, the same item twice")
	void colisRefused() {
		assertTrue(HoStoreOrderService.refusalOf(b, order(FIRST, "B")).contains("without a line"));
		StoreOrderDTO zero = order(FIRST, "B");
		zero.getColis().add(chemise(0));
		assertTrue(HoStoreOrderService.refusalOf(b, zero).contains("1 or more"));
		StoreOrderDTO blank = order(FIRST, "B");
		blank.getColis().add(new StoreOrderDTO.Colis(" ", null, 1, null, null, null));
		assertTrue(HoStoreOrderService.refusalOf(b, blank).contains("without an ERP item number"));
		StoreOrderDTO twice = order(FIRST, "B");
		twice.getColis().add(chemise(1));
		twice.getColis().add(chemise(2));
		assertTrue(HoStoreOrderService.refusalOf(b, twice).contains("twice"));
	}

	@Test
	@DisplayName("Mark posted is refused in ERP mode (the job posts)")
	void markPostedRefusedInErpMode() {
		HoStoreOrderService erp = service(true);
		assertTrue(erp.receive(b, order(FIRST, "B", line("70061~S", "S", 2))).isAccepted());
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> erp.markPosted(tables.byNumber(FIRST).getId(), "SO-0042", "admin"));
		assertTrue(e.getMessage().contains("retry now"), e.getMessage());
	}

	@Test
	@DisplayName("Reject: REJECTED, never posted; the store sees ERROR with the message; only an order waiting")
	void reject() {
		HoStoreOrder order = received();
		HoStoreOrderDTO view = service.reject(order.getId(), " Article bloqué ", "admin").get();
		assertEquals("REJECTED", view.getStatus());
		assertEquals("Article bloqué", view.getErrorMessage());
		JsonNode copy = orderCopy(b);
		assertEquals("ERROR", copy.get("status").asText());
		assertEquals("Article bloqué", copy.get("errorMessage").asText());
		assertThrows(IllegalStateException.class, () -> service.reject(order.getId(), "again", "admin"));
		assertThrows(IllegalStateException.class, () -> service.markPosted(order.getId(), "SO-1", "admin"));
		assertThrows(IllegalArgumentException.class, () -> service.reject(order.getId(), " ", "admin"));
		assertEquals(Collections.singleton(StoreOrderCodes.of(FIRST)),
				service.currentTargets().keySet(), "the backfill gives it to its store");
		assertEquals(Collections.singleton(b.getId()), service.currentTargets().get(StoreOrderCodes.of(FIRST)).getStoreIds());
	}

	@Test
	@DisplayName("List: by store and status, the store's code and name; one order with its lines")
	void list() {
		received();
		service.receive(c, order("C-2026-000001", "C", line("70061~L", "L", 5)));
		service.reject(tables.byNumber("C-2026-000001").getId(), "non", "admin");
		Map<String, Object> all = service.list(null, null, null, null, null, null, null, null, null);
		assertEquals(2L, all.get("totalElements"));
		Map<String, Object> rejected = service.list(null, "rejected", null, null, null, null, null, null, null);
		assertEquals(1L, rejected.get("totalElements"));
		@SuppressWarnings("unchecked")
		List<HoStoreOrderDTO> rows = (List<HoStoreOrderDTO>) service.list(b.getId(), "ALL", null, null, null, "number",
				"asc", 0, 20).get("content");
		assertEquals(1, rows.size());
		assertEquals("Store B", rows.get(0).getStoreName());
		assertNull(rows.get(0).getLines(), "a row has no lines");
		assertEquals(2, service.get(tables.byNumber(FIRST).getId()).get().getLines().size());
		assertThrows(IllegalArgumentException.class, () -> service.list(null, "LOST", null, null, null, null, null, null, null));
	}

	@Test
	@DisplayName("List, the search: the order number, the ERP order number, a line's code, ERP item number or name, any case;"
			+ " blank: every order")
	void listSearch() {
		received();
		service.receive(c, order("C-2026-000001", "C", line("70061~L", "L", 5)));
		service.markPosted(tables.byNumber("C-2026-000001").getId(), "CV26000009", "admin");
		assertEquals(1L, service.list(null, null, null, null, " " + FIRST.toLowerCase() + " ", null, null, null, null)
				.get("totalElements"), "the number, any case");
		assertEquals(1L, service.list(null, null, null, null, "cv2600000", null, null, null, null).get("totalElements"),
				"the ERP order number");
		assertEquals(1L, service.list(null, null, null, null, "70061~S", null, null, null, null).get("totalElements"),
				"a line's code");
		assertEquals(2L, service.list(null, null, null, null, "chemise", null, null, null, null).get("totalElements"),
				"a line's name");
		assertEquals(0L, service.list(null, null, null, null, "%", null, null, null, null).get("totalElements"),
				"a wildcard is a character");
		assertEquals(2L, service.list(null, null, null, null, " ", null, null, null, null).get("totalElements"));
	}

	/** The record codes of the orders. */
	static final class StoreOrderCodes {
		static String of(String number) {
			return "ORD:" + number;
		}
	}
}
