package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderDTO;
import com.digithink.zsretail.erp.api.dto.ErpSalesOrderResult;
import com.digithink.zsretail.headoffice.enumeration.HoStoreOrderStatus;
import com.digithink.zsretail.headoffice.enumeration.PostedMode;
import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.headoffice.model.HoStoreOrder;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.support.InMemoryCatalogue;
import com.digithink.zsretail.core.link.dto.StoreOrderDTO;
import com.digithink.zsretail.core.link.dto.StoreOrderStatusCopyDTO;

/**
 * 3.1.1, the job POST_STORE_ORDERS and "retry now" (ERP mode): the orders waiting (SENT, ERROR), oldest first, posted
 * through the poster (the connector, logged); success POSTED with the ERP's number, failure ERROR with the reason, posted
 * again at the next run (the job then shows WARNING); never a REJECTED or POSTED order; a store without its ERP customer
 * number is an ERROR without calling the ERP. The ERP's order: the store's location and customer, the order number as
 * the external document number, the send date, the ERP item numbers and variants.
 */
class HoStoreOrderPostingTest {

	private static final String FIRST = HoStoreOrderServiceTest.FIRST;

	private InMemoryCatalogue ho;
	private InMemoryHoStoreOrders tables;
	private InMemoryDownTables down;
	private HoStoreOrderService service;
	private HoStoreOrderPosting posting;
	private Store b;
	private final List<ErpSalesOrderDTO> posted = new ArrayList<>();
	/** What the ERP answers next. */
	private Function<ErpSalesOrderDTO, ErpSalesOrderResult> erp;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		tables = new InMemoryHoStoreOrders(500_000);
		b = ho.store("B");
		b.setErpCustomerNo(" C-0001 ");
		down = new InMemoryDownTables();
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		service = new HoStoreOrderService(tables.repository(), ho.storeRepository(), (item, location, customer) -> null,
				() -> holder[0], TransactionOperations.withoutTransaction(), () -> HoStoreOrderServiceTest.NOW, true);
		holder[0] = down.feed(Arrays.asList(service));
		holder[0].initialise();
		erp = order -> ErpSalesOrderResult.of(ErpOperationResult.success("SO-0042"));
		posting = new HoStoreOrderPosting(service, tables.repository(), order -> {
			posted.add(order);
			return erp.apply(order);
		}, TransactionOperations.withoutTransaction());
	}

	private HoStoreOrder receive(String number) {
		StoreOrderDTO order = HoStoreOrderServiceTest.order(number, "B", HoStoreOrderServiceTest.line("70061~S", "S", 2),
				new StoreOrderDTO.Line("BOUGIE", null, null, "BOUGIE", null, Quantities.of(1)));
		assertTrue(service.receive(b, order).isAccepted());
		return tables.byNumber(number);
	}

	private HoDownChange change(String number) {
		return down.changes.stream().filter(c -> c.getDomain() == DataDomain.SUPPLY && c.getRecordCode().equals("ORD:" + number))
				.findFirst().orElse(null);
	}

	@Test
	@DisplayName("Posted: the ERP's order built from the store's, POSTED with the ERP's number, mode ERP, recorded for the store")
	void posted() {
		HoStoreOrder order = receive(FIRST);
		posting.postStoreOrders();
		assertEquals(1, posted.size());
		ErpSalesOrderDTO sales = posted.get(0);
		assertEquals("B", sales.getLocationCode());
		assertEquals("C-0001", sales.getCustomerNo());
		assertEquals(FIRST, sales.getExternalDocumentNo());
		assertEquals(LocalDate.of(2026, 10, 27), sales.getOrderDate(), "the store's send date");
		assertEquals("70061", sales.getLines().get(0).getItemNo());
		assertEquals("S", sales.getLines().get(0).getVariantCode());
		assertEquals(0, new BigDecimal("2").compareTo(sales.getLines().get(0).getQuantity()));
		assertEquals("BOUGIE", sales.getLines().get(1).getItemNo(), "no ERP number: the item code");
		assertEquals("", sales.getLines().get(1).getVariantCode());

		assertEquals(HoStoreOrderStatus.POSTED, order.getStatus());
		assertEquals("SO-0042", order.getNavOrderNo());
		assertEquals(PostedMode.ERP, order.getPostedMode());
		assertEquals(1, order.getPostAttempts());
		assertEquals(HoStoreOrderServiceTest.NOW, order.getPostedAt());
		assertEquals(java.util.Collections.singleton(b.getId()), java.util.Collections.singleton(change(FIRST).getStoreId()));
		posting.postStoreOrders();
		assertEquals(1, posted.size(), "a posted order is never taken again");
	}

	@Test
	@DisplayName("NAV's answer kept: its message on the order, per line (by ERP item number and variant) the quantity accepted"
			+ " (to 3 decimals, a line given twice added up) and its messages; a line NAV did not answer keeps none; all of it"
			+ " in the store's copy")
	void answerKept() {
		HoStoreOrder order = receive(FIRST);
		erp = o -> {
			ErpSalesOrderResult result = ErpSalesOrderResult.of(ErpOperationResult.success("CV26000004"));
			result.setOrderMessage(" Commande reçue ");
			result.getLines().add(new ErpSalesOrderResult.Line("70061", "S", new BigDecimal("1"), "Stock insuffisant"));
			result.getLines().add(new ErpSalesOrderResult.Line(" 70061 ", " S ", new BigDecimal("0.5004"), " "));
			result.getLines().add(new ErpSalesOrderResult.Line("AUTRE", "", BigDecimal.TEN, "pas à nous"));
			return result;
		};
		posting.postStoreOrders();
		assertEquals(HoStoreOrderStatus.POSTED, order.getStatus());
		assertEquals("Commande reçue", order.getNavMessage());
		assertEquals(0, new BigDecimal("1.5").compareTo(order.getLines().get(0).getNavAcceptedQty()), "2 asked, 1.5 accepted");
		assertEquals("Stock insuffisant", order.getLines().get(0).getNavLineMessage());
		assertNull(order.getLines().get(1).getNavAcceptedQty(), "BOUGIE: NAV gave no line");
		assertNull(order.getLines().get(1).getNavLineMessage());

		StoreOrderStatusCopyDTO copy = HoStoreOrderService.copyOf(order);
		assertEquals("Commande reçue", copy.getNavMessage());
		assertEquals(2, copy.getLines().size());
		assertEquals("70061~S", copy.getLines().get(0).getItemCode());
		assertEquals(0, new BigDecimal("1.5").compareTo(copy.getLines().get(0).getAcceptedQty()));
		assertEquals("Stock insuffisant", copy.getLines().get(0).getMessage());
		assertEquals("BOUGIE", copy.getLines().get(1).getItemCode());
		assertNull(copy.getLines().get(1).getAcceptedQty());
	}

	@Test
	@DisplayName("Without NAV's answer (a connector giving none, a posting by hand): no message, no lines in the copy")
	void noAnswer() {
		HoStoreOrder order = receive(FIRST);
		posting.postStoreOrders();
		assertEquals(HoStoreOrderStatus.POSTED, order.getStatus());
		assertNull(order.getNavMessage());
		assertNull(HoStoreOrderService.copyOf(order).getLines(), "nothing answered: no lines go down");
	}

	@Test
	@DisplayName("Failure: ERROR with the reason, the job WARNING; the same failure again changes nothing for the store; posted at a later run")
	void errorThenRetried() {
		HoStoreOrder order = receive(FIRST);
		erp = o -> ErpSalesOrderResult
				.of(ErpOperationResult.failure("zen: posting to the page commandesVente failed: HTTP 400 Bad Request"));
		ErpSyncWarningException warning = assertThrows(ErpSyncWarningException.class, () -> posting.postStoreOrders());
		assertTrue(warning.getMessage().startsWith("1 of 1 store orders not posted"), warning.getMessage());
		assertEquals(HoStoreOrderStatus.ERROR, order.getStatus());
		assertTrue(order.getErrorMessage().contains("HTTP 400"));
		long version = change(FIRST).getChangeVersion();

		assertThrows(ErpSyncWarningException.class, () -> posting.postStoreOrders());
		assertEquals(2, order.getPostAttempts());
		assertEquals(version, change(FIRST).getChangeVersion(), "the store sees the same: not recorded again");

		erp = o -> {
			throw new IllegalStateException("connection reset");
		};
		assertThrows(ErpSyncWarningException.class, () -> posting.postStoreOrders());
		assertTrue(order.getErrorMessage().contains("connection reset"), order.getErrorMessage());

		erp = o -> ErpSalesOrderResult.of(ErpOperationResult.success("SO-0043"));
		posting.postStoreOrders();
		assertEquals(HoStoreOrderStatus.POSTED, order.getStatus());
		assertEquals("SO-0043", order.getNavOrderNo());
		assertNull(order.getErrorMessage());
		assertEquals(4, order.getPostAttempts(), "no limit on the attempts");
	}

	@Test
	@DisplayName("Retry now: one order posted at once; refused for a rejected or posted order; empty for an unknown one")
	void retryNow() {
		HoStoreOrder first = receive(FIRST);
		HoStoreOrder second = receive("B-2026-000002");
		service.reject(second.getId(), "non", "admin");
		assertEquals("POSTED", posting.post(first.getId()).get().getStatus());
		assertThrows(IllegalStateException.class, () -> posting.post(first.getId()), "posted");
		assertThrows(IllegalStateException.class, () -> posting.post(second.getId()), "rejected");
		assertTrue(!posting.post(999L).isPresent());
		posting.postStoreOrders();
		assertEquals(1, posted.size(), "the rejected order is never posted");
	}

	@Test
	@DisplayName("A store without its ERP customer number any more: ERROR, the ERP not called")
	void noCustomerNumber() {
		HoStoreOrder order = receive(FIRST);
		b.setErpCustomerNo(null);
		assertThrows(ErpSyncWarningException.class, () -> posting.postStoreOrders());
		assertTrue(posted.isEmpty());
		assertEquals(HoStoreOrderStatus.ERROR, order.getStatus());
		assertTrue(order.getErrorMessage().contains("has no ERP customer number"), order.getErrorMessage());
		assertEquals(LocalDateTime.of(2026, 10, 28, 9, 0), order.getLastAttemptAt());
	}
}
