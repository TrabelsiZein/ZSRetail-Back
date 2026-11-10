package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.model.Store;

/**
 * Stock points, the copies down of a head office whose catalogue comes from the ERP: the three queries of
 * HoStockPointItemRepository that name the stores of an item and the codes of a point (STORE_AND_ITEM_IDS,
 * ITEM_CODES_OF_POINTS, BARCODES_OF_POINTS), run as they are with Hibernate over an in-memory H2 database (no Spring
 * context). Points P1 (an active and an inactive row), P2, P3 (no row); an item in two points, an item in none, a
 * TAX_STAMP row (never named); stores on P1 (two), P2, P3 and none.
 */
class StockPointCopiesQueryTest {

	private static final long P1 = 1;
	private static final long P2 = 2;
	private static final long P3 = 3;

	private static SessionFactory sessions;
	/** item id to code, store id to code: the answers are compared as codes. */
	private static final Map<Long, String> itemCodes = new HashMap<>();
	private static final Map<Long, String> storeCodes = new HashMap<>();

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(Store.class)
				.addAnnotatedClass(HoStockPointItem.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:stockpointcopies;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Item a1 = item(session, "A1");
			Item a2 = item(session, "A2");
			Item b1 = item(session, "B1");
			Item ab = item(session, "AB");
			Item none = item(session, "NONE");
			Item taxStamp = item(session, CatalogueKind.TAX_STAMP_CODE);
			barcode(session, "101", a1);
			barcode(session, "102", a1);
			barcode(session, "201", a2);
			barcode(session, "301", b1);
			barcode(session, "401", ab);
			barcode(session, "501", none);
			barcode(session, "TS1", taxStamp);
			row(session, P1, a1, true);
			row(session, P1, a2, false);
			row(session, P1, ab, true);
			row(session, P1, taxStamp, true);
			row(session, P2, b1, true);
			row(session, P2, ab, true);
			store(session, "S1", P1);
			store(session, "S1B", P1);
			store(session, "S2", P2);
			store(session, "S3", P3);
			store(session, "S0", null);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static Item item(Session session, String code) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName("Item " + code);
		item.setErpExternalId(code);
		item.setActive(true);
		session.persist(item);
		itemCodes.put(item.getId(), code);
		return item;
	}

	private static void barcode(Session session, String value, Item item) {
		ItemBarcode barcode = new ItemBarcode();
		barcode.setBarcode(value);
		barcode.setErpExternalId("E" + value);
		barcode.setItem(item);
		barcode.setActive(true);
		session.persist(barcode);
	}

	private static void row(Session session, long pointId, Item item, boolean active) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(pointId);
		row.setItemId(item.getId());
		row.setName(item.getName());
		row.setUnitPrice(10.0);
		row.setActive(active);
		session.persist(row);
	}

	private static void store(Session session, String code, Long pointId) {
		Store store = new Store();
		store.setCode(code);
		store.setName("Store " + code);
		store.setApiKeyHash("x");
		store.setStockPointId(pointId);
		session.persist(store);
		storeCodes.put(store.getId(), code);
	}

	private static Long idOf(String itemCode) {
		return itemCodes.entrySet().stream().filter(e -> e.getValue().equals(itemCode)).findFirst().get().getKey();
	}

	/** "store:item" of the answer, sorted. */
	private static Set<String> storesOf(String... items) {
		try (Session session = sessions.openSession()) {
			List<Object[]> pairs = session.createQuery(HoStockPointItemRepository.STORE_AND_ITEM_IDS, Object[].class)
					.setParameter("itemIds", Arrays.stream(items).map(StockPointCopiesQueryTest::idOf)
							.collect(java.util.stream.Collectors.toList()))
					.getResultList();
			Set<String> found = new TreeSet<>();
			for (Object[] pair : pairs) {
				found.add(storeCodes.get(pair[0]) + ":" + itemCodes.get(pair[1]));
			}
			assertEquals(pairs.size(), found.size(), "each store and item once");
			return found;
		}
	}

	private static List<String> codes(String jpql, Collection<Long> pointIds) {
		try (Session session = sessions.openSession()) {
			return session.createQuery(jpql, String.class).setParameter("pointIds", pointIds)
					.setParameter("excludedItemCode", CatalogueKind.TAX_STAMP_CODE).getResultList();
		}
	}

	@Test
	@DisplayName("The stores of an item: those whose point has a row for it, active or not; none for an item in no point")
	void storesOfItems() {
		assertEquals(new TreeSet<>(Arrays.asList("S1:A1", "S1B:A1", "S1:A2", "S1B:A2", "S1:AB", "S1B:AB", "S2:B1",
				"S2:AB")), storesOf("A1", "A2", "B1", "AB", "NONE"));
		assertEquals(new TreeSet<>(Arrays.asList("S1:A2", "S1B:A2")), storesOf("A2"), "an inactive row counts");
		assertTrue(storesOf("NONE").isEmpty(), "no point has it: no store, not the store without a point");
	}

	/** The rows of a query without parameter, or with one collection parameter, each row's values joined by ":". */
	private static Set<String> rows(String jpql, String parameter, Collection<String> values) {
		try (Session session = sessions.openSession()) {
			org.hibernate.query.Query<Object[]> query = session.createQuery(jpql, Object[].class);
			if (parameter != null) {
				query.setParameter(parameter, values);
			}
			Set<String> found = new TreeSet<>();
			for (Object[] row : query.getResultList()) {
				found.add(Arrays.stream(row).map(String::valueOf).collect(java.util.stream.Collectors.joining(":")));
			}
			return found;
		}
	}

	@Test
	@DisplayName("Step 4, the backfill: every store with the items of its point, every item with its ERP id, every barcode with its item")
	void backfillQueries() {
		Set<String> pairs = new TreeSet<>();
		for (String row : rows(HoStockPointItemRepository.ALL_STORE_AND_ITEM_IDS, null, null)) {
			String[] ids = row.split(":");
			pairs.add(storeCodes.get(Long.valueOf(ids[0])) + ":" + itemCodes.get(Long.valueOf(ids[1])));
		}
		assertEquals(new TreeSet<>(Arrays.asList("S1:A1", "S1B:A1", "S1:A2", "S1B:A2", "S1:AB", "S1B:AB", "S1:TAX_STAMP",
				"S1B:TAX_STAMP", "S2:B1", "S2:AB")), pairs);
		assertTrue(rows(HoStockPointItemRepository.ITEM_KEYS, null, null).contains(idOf("NONE") + ":NONE:NONE"));
		assertEquals(itemCodes.size(), rows(HoStockPointItemRepository.ITEM_KEYS, null, null).size());
		Set<String> barcodes = rows(HoStockPointItemRepository.BARCODE_ITEM_IDS, null, null);
		assertEquals(7, barcodes.size());
		assertTrue(barcodes.contains("401:" + idOf("AB")));
	}

	@Test
	@DisplayName("Step 4, before an import of barcodes: id, value and item of the barcodes of these ERP ids, of these values")
	void barcodesBefore() {
		Set<String> byErpId = rows(HoStockPointItemRepository.BARCODES_BY_ERP_ID, "externalIds",
				Arrays.asList("E101", "E301", "E999"));
		assertEquals(2, byErpId.size());
		assertTrue(byErpId.stream().anyMatch(row -> row.endsWith(":101:" + idOf("A1"))));
		assertTrue(byErpId.stream().anyMatch(row -> row.endsWith(":301:" + idOf("B1"))));
		Set<String> byValue = rows(HoStockPointItemRepository.BARCODES_BY_VALUE, "values", Arrays.asList("201", "999"));
		assertEquals(1, byValue.size());
		assertTrue(byValue.iterator().next().endsWith(":201:" + idOf("A2")));
	}

	@Test
	@DisplayName("The item codes of points: each once, by code, active or inactive rows, never TAX_STAMP")
	void itemCodesOfPoints() {
		assertEquals(Arrays.asList("A1", "A2", "AB"), codes(HoStockPointItemRepository.ITEM_CODES_OF_POINTS,
				Collections.singletonList(P1)));
		assertEquals(Arrays.asList("A1", "A2", "AB", "B1"), codes(HoStockPointItemRepository.ITEM_CODES_OF_POINTS,
				Arrays.asList(P1, P2)), "the previous and the new point: an item of both once");
		assertTrue(codes(HoStockPointItemRepository.ITEM_CODES_OF_POINTS, Collections.singletonList(P3)).isEmpty());
	}

	@Test
	@DisplayName("The barcodes of points: those of the items with a row, each once, by value, never TAX_STAMP's")
	void barcodesOfPoints() {
		assertEquals(Arrays.asList("101", "102", "201", "401"), codes(HoStockPointItemRepository.BARCODES_OF_POINTS,
				Collections.singletonList(P1)));
		assertEquals(Arrays.asList("101", "102", "201", "301", "401"),
				codes(HoStockPointItemRepository.BARCODES_OF_POINTS, Arrays.asList(P1, P2)));
		assertTrue(codes(HoStockPointItemRepository.BARCODES_OF_POINTS, Collections.singletonList(P3)).isEmpty());
	}
}
