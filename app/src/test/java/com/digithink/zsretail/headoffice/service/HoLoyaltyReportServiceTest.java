package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.LoyaltyMovementCopyDTO;
import com.digithink.zsretail.headoffice.model.HoLoyaltyMovement;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.support.InMemoryLoyalty;

/**
 * Head office plan, step 5: the overspend report. Movements whose removal stopped at zero are listed once (a movement
 * sent again changes nothing), newest first, with the store, the card sent, the member, the sale or return number and
 * the points missing; filters by store, dates and text; the count for the home page. Real HoLoyaltyReceiver and
 * HoLoyaltyReportService over in-memory tables (the stub applies the rules of the JPQL query).
 */
class HoLoyaltyReportServiceTest {

	private final InMemoryLoyalty db = new InMemoryLoyalty(1);
	private HoLoyaltyReceiver receiver;
	private HoLoyaltyReportService report;
	private final Store rs01 = store(1L, "RS01");
	private final Store rs02 = store(2L, "RS02");
	private LoyaltyMember sami;

	@BeforeEach
	void setUp() {
		InMemoryDownTables tables = new InMemoryDownTables();
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		HoLoyaltyService register = new HoLoyaltyService(db.memberRepository(), db.programRepository(),
				() -> holder[0]);
		holder[0] = tables.feed(Collections.singletonList(register));
		receiver = new HoLoyaltyReceiver(db.memberRepository(), db.programRepository(), db.transactionRepository(),
				db.functionRepository(), db.customerRepository(), db.aliasRepository(), db.movementRepository(),
				register, () -> null, TransactionOperations.withoutTransaction());
		StoreRepository stores = InMemoryLoyalty.proxy(StoreRepository.class, (method, a) -> {
			if ("findAllById".equals(method)) {
				Collection<?> ids = (Collection<?>) a[0];
				return Arrays.asList(rs01, rs02).stream().filter(s -> ids.contains(s.getId())).collect(Collectors.toList());
			}
			return InMemoryLoyalty.UNHANDLED;
		});
		report = new HoLoyaltyReportService(db.movementRepository(), stores);
		sami = db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		sami.setLoyaltyPoints(100);
		db.member("LYL-HO-000002", "ALI", "KHARAT", "22984935", true, null).setLoyaltyPoints(10);
	}

	@Test
	@DisplayName("Listed once, newest first, with store, card, member, sale and points missing; count for the home page")
	void listedOnce() {
		LoyaltyMovementCopyDTO spend = movement("1", "LYL-HO-000001", -130, "RS01-0001", null);
		receiver.receiveMovements(rs01, Collections.singletonList(spend));
		receiver.receiveMovements(rs01, Collections.singletonList(spend)); // sent again: already applied
		receiver.receiveMovements(rs02, Collections.singletonList(movement("1", "LYL-HO-000002", -25, null, "RS02-R1")));
		receiver.receiveMovements(rs02, Collections.singletonList(movement("2", "LYL-HO-000001", 50, "RS02-0002", null)));
		db.movements.get(1).setCreatedAt(db.movements.get(0).getCreatedAt().plusSeconds(5));

		Map<String, Object> page = report.overspends(null, null, null, null, null, null);
		List<Map<String, Object>> rows = content(page);
		assertEquals(2L, page.get("totalElements"));
		assertEquals("RS02", rows.get(0).get("storeCode"));
		assertEquals("RS02-R1", rows.get(0).get("returnNumber"));
		assertEquals(15, rows.get(0).get("overspendPoints"));
		Map<String, Object> first = rows.get(1);
		assertEquals("RS01", first.get("storeCode"));
		assertEquals("Store RS01", first.get("storeName"));
		assertEquals("LYL-HO-000001", first.get("cardNumber"));
		assertEquals("SAMI BEN", first.get("memberName"));
		assertEquals("RS01-0001", first.get("salesNumber"));
		assertEquals(130, first.get("points"));
		assertEquals(30, first.get("overspendPoints"));

		Map<String, Object> count = report.count(null, null);
		assertEquals(2L, count.get("count"));
		assertEquals(45L, count.get("points"));
	}

	@Test
	@DisplayName("Filters: store, text (card, name, sale or return number, any case), dates; a bad date refused")
	void filters() {
		receiver.receiveMovements(rs01, Collections.singletonList(movement("1", "LYL-HO-000001", -130, "RS01-0001", null)));
		receiver.receiveMovements(rs02, Collections.singletonList(movement("1", "LYL-HO-000002", -25, null, "RS02-R1")));
		HoLoyaltyMovement old = db.movements.get(0);
		old.setCreatedAt(LocalDateTime.of(2026, 9, 1, 10, 0));
		db.movements.get(1).setCreatedAt(LocalDateTime.of(2026, 10, 2, 9, 0));

		assertEquals(1L, report.overspends(0, 10, 2L, null, null, null).get("totalElements"));
		assertEquals("RS02", content(report.overspends(0, 10, 2L, null, null, null)).get(0).get("storeCode"));
		assertEquals(1L, report.overspends(null, null, null, null, null, "kharat").get("totalElements"));
		assertEquals(1L, report.overspends(null, null, null, null, null, "rs01-0001").get("totalElements"));
		assertEquals(1L, report.overspends(null, null, null, null, null, "r1").get("totalElements"));
		assertEquals(1L, report.overspends(null, null, null, "2026-10-01", null, null).get("totalElements"));
		assertEquals(1L, report.overspends(null, null, null, "2026-09-01", "2026-09-01", null).get("totalElements"));
		assertEquals(1L, report.count("2026-09-01", "2026-09-30").get("count"));
		assertThrows(IllegalArgumentException.class, () -> report.overspends(null, null, null, "01/09/2026", null, null));
		assertThrows(IllegalArgumentException.class, () -> report.overspends(-1, null, null, null, null, null));
		assertEquals(0L, report.overspends(null, null, 9L, null, null, null).get("totalElements"));
	}

	@SuppressWarnings("unchecked")
	private static List<Map<String, Object>> content(Map<String, Object> page) {
		assertTrue(page.keySet().containsAll(Arrays.asList("content", "totalElements", "totalPages", "number", "size")));
		return (List<Map<String, Object>>) page.get("content");
	}

	private static LoyaltyMovementCopyDTO movement(String key, String card, int delta, String sale, String ret) {
		LoyaltyMovementCopyDTO m = new LoyaltyMovementCopyDTO();
		m.setKey(key);
		m.setCardNumber(card);
		m.setType(delta < 0 ? (ret != null ? "REVERSED" : "REDEEMED") : "EARNED");
		m.setPoints(Math.abs(delta));
		m.setDelta(delta);
		m.setSalesNumber(sale == null ? "RS02-0009" : sale);
		m.setReturnNumber(ret);
		return m;
	}

	private static Store store(long id, String code) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		store.setName("Store " + code);
		return store;
	}
}
