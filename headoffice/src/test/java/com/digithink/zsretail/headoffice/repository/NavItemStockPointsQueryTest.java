package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;

/**
 * Item variants (ZEN), step 11a: the two point de stock queries of the "NAV items" page
 * (HoStockPointItemRepository.NAV_ITEM_POINT_COUNTS, ROWS_OF_ITEMS), run as they are with Hibernate over an in-memory
 * H2 database (no Spring context). Points FRANCHISE and MAG1 (active), OLD (inactive). 70061: S in FRANCHISE and MAG1
 * (clearance in FRANCHISE), M in MAG1 inactive and in OLD. 14ZDU05: only an inactive row. An item without NAV item number
 * (Happyness) has a row and is never counted.
 */
class NavItemStockPointsQueryTest {

	private static SessionFactory sessions;
	private static long franchise;
	private static long mag1;
	private static long old;
	private static long s;
	private static long m;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(HoStockPoint.class)
				.addAnnotatedClass(HoStockPointItem.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:navitempoints;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			franchise = point(session, "FRANCHISE", 1, true);
			mag1 = point(session, "MAG1", 2, true);
			old = point(session, "OLD", 3, false);
			s = item(session, "70061~S", "70061");
			m = item(session, "70061~M", "70061");
			long duvet = item(session, "14ZDU05~A", "14ZDU05");
			long happy = item(session, "HAPPY-1", null);
			row(session, franchise, s, true, true, "7");
			row(session, mag1, s, true, null, null);
			row(session, mag1, m, false, false, null);
			row(session, old, m, true, false, null);
			row(session, franchise, duvet, false, false, null);
			row(session, franchise, happy, true, false, null);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static long point(Session session, String code, int order, boolean active) {
		HoStockPoint point = new HoStockPoint();
		point.setCode(code);
		point.setName(code);
		point.setSortOrder(order);
		point.setActive(active);
		session.persist(point);
		return point.getId();
	}

	private static long item(Session session, String code, String navItemNo) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(code);
		item.setNavItemNo(navItemNo);
		session.persist(item);
		return item.getId();
	}

	private static void row(Session session, long pointId, long itemId, boolean active, Boolean clearance,
			String clearancePrice) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(pointId);
		row.setItemId(itemId);
		row.setName("row");
		row.setUnitPrice(19.5);
		row.setActive(active);
		row.setClearance(clearance);
		row.setClearancePrice(clearancePrice == null ? null : new BigDecimal(clearancePrice));
		session.persist(row);
	}

	private static Map<String, Long> counts(Collection<String> navItemNos) {
		try (Session session = sessions.openSession()) {
			Map<String, Long> counts = new TreeMap<>();
			for (Object[] row : session.createQuery(HoStockPointItemRepository.NAV_ITEM_POINT_COUNTS, Object[].class)
					.setParameterList("navItemNos", navItemNos).list()) {
				counts.put((String) row[0], ((Number) row[1]).longValue());
			}
			return counts;
		}
	}

	private static List<Object[]> rows(Collection<Long> itemIds) {
		try (Session session = sessions.openSession()) {
			return session.createQuery(HoStockPointItemRepository.ROWS_OF_ITEMS, Object[].class)
					.setParameterList("itemIds", itemIds).list();
		}
	}

	@Test
	@DisplayName("Point counts: the active points with an active row of one variant; inactive rows and points not counted")
	void pointCounts() {
		Map<String, Long> counts = counts(Arrays.asList("70061", "14ZDU05", "UNKNOWN"));
		assertEquals(1, counts.size(), "14ZDU05 has only an inactive row: absent (the service reads 0)");
		assertEquals(Long.valueOf(2), counts.get("70061"), "S in FRANCHISE and MAG1; M's MAG1 row inactive, OLD inactive");
	}

	@Test
	@DisplayName("Rows of items: every row, active or not, with its price and clearance")
	void rowsOfItems() {
		Map<String, Object[]> byKey = new TreeMap<>();
		for (Object[] row : rows(Arrays.asList(s, m))) {
			byKey.put(row[0] + "@" + row[1], row);
		}
		assertEquals(4, byKey.size());
		Object[] clearance = byKey.get(s + "@" + franchise);
		assertEquals(19.5, clearance[2]);
		assertEquals(Boolean.TRUE, clearance[3]);
		assertEquals(Boolean.TRUE, clearance[4]);
		assertEquals(0, new BigDecimal("7").compareTo((BigDecimal) clearance[5]));
		assertNull(byKey.get(s + "@" + mag1)[4], "a null clearance stays null (the service reads it as false)");
		assertEquals(Boolean.FALSE, byKey.get(m + "@" + mag1)[3]);
		assertEquals(Boolean.TRUE, byKey.get(m + "@" + old)[3]);
	}
}
