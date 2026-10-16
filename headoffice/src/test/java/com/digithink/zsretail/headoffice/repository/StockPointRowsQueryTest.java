package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;

/**
 * The two queries of the page "Items by point de stock" (HoStockPointItemRepository.ROWS without a search,
 * ROWS_SEARCHED with one), run as they are with Hibernate over an in-memory H2 database (no Spring context): the same
 * rows, order and counts as the single query of before; the query without a search reads no barcode. Points FRANCHISE
 * (order 1) and MAG1 (order 2). 70061~S (barcode 9000002740592): active in both. 14ZDU05~A (row "Duvet"): inactive in
 * FRANCHISE, family F1. HAPPY-1: in MAG1.
 */
class StockPointRowsQueryTest {

	private static SessionFactory sessions;
	private static long franchise;
	private static long mag1;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(HoStockPoint.class)
				.addAnnotatedClass(HoStockPointItem.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:stockpointrows;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			franchise = point(session, "FRANCHISE", 1);
			mag1 = point(session, "MAG1", 2);
			Item dress = item(session, "70061~S");
			Item duvet = item(session, "14ZDU05~A");
			Item happy = item(session, "HAPPY-1");
			ItemBarcode barcode = new ItemBarcode();
			barcode.setItem(dress);
			barcode.setBarcode("9000002740592");
			session.persist(barcode);
			row(session, franchise, dress, "Robe S", null, 10.0, true);
			row(session, franchise, duvet, "Duvet", "F1", 20.0, false);
			row(session, mag1, dress, "Robe S", null, 12.0, true);
			row(session, mag1, happy, "Happy", null, 5.0, true);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static long point(Session session, String code, int order) {
		HoStockPoint point = new HoStockPoint();
		point.setCode(code);
		point.setName(code);
		point.setSortOrder(order);
		point.setActive(true);
		session.persist(point);
		return point.getId();
	}

	private static Item item(Session session, String code) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(code);
		session.persist(item);
		return item;
	}

	private static void row(Session session, long pointId, Item item, String name, String familyCode, double price,
			boolean active) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(pointId);
		row.setItemId(item.getId());
		row.setName(name);
		row.setFamilyCode(familyCode);
		row.setUnitPrice(price);
		row.setActive(active);
		session.persist(row);
	}

	/** The rows of a query as "point:item", and checks its count query gives their number. */
	private static List<String> rows(String search, Long pointId, String familyCode, Double priceMin, Boolean active) {
		try (Session session = sessions.openSession()) {
			String select = search == null ? HoStockPointItemRepository.ROWS : HoStockPointItemRepository.ROWS_SEARCHED;
			String count = search == null ? HoStockPointItemRepository.ROWS_COUNT
					: HoStockPointItemRepository.ROWS_SEARCHED_COUNT;
			Query<Object[]> rows = filters(session.createQuery(select, Object[].class), search, pointId, familyCode,
					priceMin, active);
			List<String> found = rows.list().stream()
					.map(line -> ((HoStockPoint) line[2]).getCode() + ":" + ((Item) line[1]).getItemCode())
					.collect(Collectors.toList());
			Query<Long> counted = filters(session.createQuery(count, Long.class), search, pointId, familyCode, priceMin,
					active);
			assertEquals(Long.valueOf(found.size()), counted.uniqueResult(), "the count query counts the same rows");
			return found;
		}
	}

	private static <T> Query<T> filters(Query<T> query, String search, Long pointId, String familyCode, Double priceMin,
			Boolean active) {
		if (search != null) {
			query.setParameter("search", search);
		}
		return query.setParameter("pointId", pointId).setParameter("familyCode", familyCode)
				.setParameter("subFamilyCode", null).setParameter("priceMin", priceMin).setParameter("priceMax", null)
				.setParameter("active", active);
	}

	@Test
	@DisplayName("Without a search: every row by point order then item code; the filters given apply; no barcode read")
	void withoutSearch() {
		assertEquals(Arrays.asList("FRANCHISE:14ZDU05~A", "FRANCHISE:70061~S", "MAG1:70061~S", "MAG1:HAPPY-1"),
				rows(null, null, null, null, null));
		assertEquals(Arrays.asList("FRANCHISE:70061~S"), rows(null, franchise, null, null, true));
		assertEquals(Arrays.asList("FRANCHISE:14ZDU05~A"), rows(null, null, "F1", null, null));
		assertEquals(Arrays.asList("FRANCHISE:14ZDU05~A", "MAG1:70061~S"), rows(null, null, null, 11.0, null));
		assertFalse(HoStockPointItemRepository.ROWS.contains("ItemBarcode"));
		assertFalse(HoStockPointItemRepository.ROWS_COUNT.contains("ItemBarcode"));
	}

	@Test
	@DisplayName("With a search: the item code, the row's name or a barcode of the item, with the other filters")
	void withSearch() {
		assertEquals(Arrays.asList("FRANCHISE:70061~S", "MAG1:70061~S"), rows("%0592%", null, null, null, null),
				"a barcode of the item");
		assertEquals(Arrays.asList("FRANCHISE:14ZDU05~A"), rows("%duvet%", null, null, null, null), "the row's name");
		assertEquals(Arrays.asList("MAG1:HAPPY-1"), rows("%happy-%", null, null, null, null), "the item code");
		assertEquals(Arrays.asList("MAG1:70061~S"), rows("%0592%", mag1, null, null, null), "with a point");
		assertEquals(Arrays.asList(), rows("%0592%", null, null, null, false), "with a status");
		assertTrue(HoStockPointItemRepository.ROWS_SEARCHED.contains("ItemBarcode"));
		assertTrue(HoStockPointItemRepository.ROWS_SEARCHED_COUNT.contains("ItemBarcode"));
	}
}
