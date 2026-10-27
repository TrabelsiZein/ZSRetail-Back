package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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
import com.digithink.zsretail.core.link.dto.VariantStockDTO;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.erp.api.ErpNotSupportedException;
import com.digithink.zsretail.erp.api.dto.ErpVariantStockDTO;
import com.digithink.zsretail.headoffice.dto.HoStoreOrderDTO;
import com.digithink.zsretail.headoffice.enumeration.HoStoreOrderStatus;
import com.digithink.zsretail.headoffice.enumeration.PostedMode;
import com.digithink.zsretail.headoffice.model.HoStoreOrder;
import com.digithink.zsretail.headoffice.model.Store;
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
