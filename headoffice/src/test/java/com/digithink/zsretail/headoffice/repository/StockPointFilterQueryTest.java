package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.NativeQuery;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.orm.jpa.hibernate.SpringImplicitNamingStrategy;
import org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy;

import com.digithink.zsretail.core.support.MigTestDatabase;
import com.digithink.zsretail.core.dto.ItemCatalogueLineDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemCatalogueQuery;
import com.digithink.zsretail.core.service.ItemCatalogueService;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.service.HoItemCatalogueService;

/**
 * 3.1.1, step 1 of retiring "Items by point de stock": the Item cards list with a point de stock chosen
 * ({@link HoItemCatalogueQuery#stockPoint(ItemCatalogueQuery.Criteria, Long, String)}): the row status at that point and
 * the price range on the point's row selling price with VAT. Run as the page runs it (ItemCatalogueQuery.build) over an
 * throwaway SQL Server database, as ItemCatalogueQueryTest.
 * <p>
 * Items (own price before VAT, VAT): NAV item 100, 100~A (10, 19 %), 100~B (50, 19 %) and 100~C (30, 19 %); X-1 (30,
 * 0 %), Y-1 (5, no row), Z-1 (7, an inactive item). Point P1: 100~A active 20 (23.8 with VAT); 100~B inactive 50 (59.5);
 * 100~C active 40 (47.6) in clearance at 30 (35.7); X-1 active 40 in clearance at 25; Z-1 active 7. Point P2: 100~A
 * active 20, clearance with a price 0 (does not apply); X-1 40, active null (counts as active).
 * <p>
 * Step 2: the lines' price at the point (core's line price hook, set by HoItemCatalogueQuery.stockPoint), its sort, and
 * the clearance count and normal range added after the page (HoItemCatalogueService.pointPrices).
 */
class StockPointFilterQueryTest {

	private static MigTestDatabase database;
	private static SessionFactory sessions;
	private static long p1;
	private static long p2;

	@BeforeAll
	static void database() {
		database = MigTestDatabase.fromSettings();
		database.recreate();
		Configuration configuration = new Configuration();
		database.connect(configuration);
		configuration.setPhysicalNamingStrategy(new CamelCaseToUnderscoresNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(HoStockPoint.class)
				.addAnnotatedClass(ItemCategory.class).addAnnotatedClass(ItemSection.class)
				.addAnnotatedClass(HoStockPointItem.class)
				.setProperty("hibernate.dialect", "org.hibernate.dialect.SQLServerDialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Item a = item(session, "100~A", "100", true, 10.0, 19);
			Item b = item(session, "100~B", "100", true, 50.0, 19);
			Item c = item(session, "100~C", "100", true, 30.0, 19);
			Item x = item(session, "X-1", null, true, 30.0, 0);
			item(session, "Y-1", null, true, 5.0, 0);
			Item z = item(session, "Z-1", null, false, 7.0, 0);
			HoStockPoint one = point(session, "P1");
			HoStockPoint two = point(session, "P2");
			p1 = one.getId();
			p2 = two.getId();
			row(session, one, a, 20.0, true, null, null);
			row(session, one, b, 50.0, false, null, null);
			row(session, one, c, 40.0, true, true, new BigDecimal("30"));
			row(session, one, x, 40.0, true, true, new BigDecimal("25"));
			row(session, one, z, 7.0, true, null, null);
			row(session, two, a, 20.0, true, true, BigDecimal.ZERO);
			row(session, two, x, 40.0, null, null, null);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		if (sessions != null) {
			sessions.close();
		}
		database.drop();
	}

	private static Item item(Session session, String code, String navItemNo, boolean active, double price, int vat) {
		Item item = new Item();
		item.setItemCode(code);
		item.setNavItemNo(navItemNo);
		item.setName(code);
		item.setActive(active);
		item.setUnitPrice(price);
		item.setDefaultVAT(vat);
		session.persist(item);
		return item;
	}

	private static HoStockPoint point(Session session, String code) {
		HoStockPoint point = new HoStockPoint();
		point.setCode(code);
		point.setName(code);
		point.setSortOrder(1);
		point.setActive(true);
		session.persist(point);
		return point;
	}

	private static void row(Session session, HoStockPoint point, Item item, double price, Boolean active,
			Boolean clearance, BigDecimal clearancePrice) {
		HoStockPointItem row = new HoStockPointItem();
		row.setStockPointId(point.getId());
		row.setItemId(item.getId());
		row.setName(item.getName());
		row.setUnitPrice(price);
		row.setActive(active);
		row.setClearance(clearance);
		row.setClearancePrice(clearancePrice);
		session.persist(row);
	}

	/** The criteria of the page: item status All (all) or Active, a price range (null: none). */
	private static ItemCatalogueQuery.Criteria criteria(boolean all, Double priceMin, Double priceMax) {
		ItemCatalogueQuery.Criteria criteria = new ItemCatalogueQuery.Criteria();
		criteria.setActiveOnly(!all);
		criteria.setPriceMin(priceMin);
		criteria.setPriceMax(priceMax);
		return criteria;
	}

	/** The line codes of the criteria, after checking that the count query counts as many. */
	private static List<String> codes(ItemCatalogueQuery.Criteria criteria) {
		return codes(lines(criteria));
	}

	private static List<ItemCatalogueLineDTO> lines(ItemCatalogueQuery.Criteria criteria) {
		ItemCatalogueQuery.Built built = ItemCatalogueQuery.build(criteria, false);
		try (Session session = sessions.openSession()) {
			NativeQuery<?> query = session.createNativeQuery(built.getLines());
			built.getParameters().forEach(query::setParameter);
			List<ItemCatalogueLineDTO> lines = query.list().stream()
					.map(row -> ItemCatalogueService.line((Object[]) row, false)).collect(Collectors.toList());
			NativeQuery<?> count = session.createNativeQuery(built.getCount());
			built.getParameters().forEach(count::setParameter);
			assertEquals(lines.size(), ((Number) count.uniqueResult()).intValue(), "the count of the lines");
			return lines;
		}
	}

	private static List<String> codes(List<ItemCatalogueLineDTO> lines) {
		return lines.stream().map(line -> (line.isSingle() ? "item:" : "nav:") + line.getCode())
				.collect(Collectors.toList());
	}

	/** The criteria with the point de stock filter. */
	private static ItemCatalogueQuery.Criteria at(ItemCatalogueQuery.Criteria criteria, Long point, String rowStatus) {
		return HoItemCatalogueQuery.stockPoint(criteria, point, rowStatus);
	}

	@Test
	@DisplayName("No point chosen: the row status changes nothing and the price range stays on the item's own price with VAT")
	void noPoint() {
		for (String status : Arrays.asList(null, "ACTIVE", "INACTIVE", "ALL")) {
			ItemCatalogueQuery.Criteria criteria = at(criteria(true, 30.0, null), null, status);
			assertEquals(Double.valueOf(30.0), criteria.getPriceMin(), status);
			assertTrue(criteria.getItemConditions().isEmpty(), status);
			assertTrue(criteria.getItemParameters().isEmpty(), status);
			// 100~B 59.5, X-1 30; Y-1 5 and 100~A 11.9 below; Z-1 7 below
			assertEquals(Arrays.asList("nav:100", "item:X-1"), codes(criteria), status);
		}
		assertEquals(Arrays.asList("nav:100", "item:X-1", "item:Y-1", "item:Z-1"),
				codes(at(criteria(true, null, null), null, "INACTIVE")), "every line");
	}

	@Test
	@DisplayName("Point chosen, row status Active (absent, blank, any case or unknown), Inactive, All; the item status filter applies as before")
	void rowStatus() {
		List<String> active = Arrays.asList("nav:100", "item:X-1", "item:Z-1");
		for (String status : Arrays.asList(null, "", " active ", "ACTIVE", "ON")) {
			assertEquals(active, codes(at(criteria(true, null, null), p1, status)), "Active: " + status);
		}
		List<ItemCatalogueLineDTO> inactive = lines(at(criteria(true, null, null), p1, "inactive"));
		assertEquals(Collections.singletonList("nav:100"), codes(inactive), "only 100~B's row is inactive");
		assertEquals(3, inactive.get(0).getVariants(), "the line keeps every variant of the item status");
		assertEquals(active, codes(at(criteria(true, null, null), p1, "ALL")), "every row; Y-1 has none");

		// Item status Active: Z-1 (an inactive item) is left out whatever its row
		assertEquals(Arrays.asList("nav:100", "item:X-1"), codes(at(criteria(false, null, null), p1, "ACTIVE")));
		assertEquals(Collections.singletonList("nav:100"), codes(at(criteria(false, null, null), p1, "INACTIVE")),
				"an active item, inactive at the point");

		// P2: a null active counts as active
		assertEquals(Arrays.asList("nav:100", "item:X-1"), codes(at(criteria(true, null, null), p2, "ACTIVE")));
		assertEquals(Collections.emptyList(), codes(at(criteria(true, null, null), p2, "INACTIVE")));
	}

	@Test
	@DisplayName("Point chosen: the price range on the row's selling price with the item's VAT, not on the item's own price, under the row status")
	void pointPrice() {
		ItemCatalogueQuery.Criteria moved = at(criteria(true, 26.0, 60.0), p1, "ALL");
		assertNull(moved.getPriceMin(), "the item's own price no longer filters");
		assertNull(moved.getPriceMax());
		// 100~B's row 50 HT is 59.5 with VAT; X-1 sells at 25 in clearance (its own price 30 would match); A 23.8
		assertEquals(Collections.singletonList("nav:100"), codes(moved));
		assertEquals(Collections.singletonList("nav:100"), codes(at(criteria(true, 55.0, null), p1, "ALL")),
				"55: only with the VAT (50 before VAT)");
		assertEquals(Arrays.asList("nav:100", "item:X-1", "item:Z-1"), codes(at(criteria(true, null, 26.0), p1, "ALL")),
				"A 23.8, X-1 its clearance 25 (its row price 40 would not), Z-1 7");
		assertEquals(Collections.singletonList("nav:100"), codes(at(criteria(true, 26.0, null), p1, "ACTIVE")),
				"100~C sells at 35.7; 100~B matches the price but its row is inactive; A 23.8 does not");
		assertEquals(Collections.emptyList(), codes(at(criteria(true, 36.0, null), p1, "ACTIVE")),
				"36: 100~C's normal 47.6 does not count, only its clearance 35.7");
		assertEquals(Collections.singletonList("nav:100"), codes(at(criteria(true, 26.0, null), p1, "INACTIVE")));

		// P2: a clearance price 0 does not apply (A at 23.8); X-1's row at 40, its active null
		assertEquals(Collections.singletonList("nav:100"), codes(at(criteria(true, null, 24.0), p2, null)));
		assertEquals(Collections.singletonList("item:X-1"), codes(at(criteria(true, 39.0, null), p2, null)));
	}

	// ─── Step 2: the lines' price at the point ───

	/** The item's own price with VAT, as core writes it (ItemCatalogueQuery.PRICE_OF_I). */
	private static final String OWN_PRICE = "(i.unit_price * (1 + COALESCE(i.defaultvat, 0) / 100.0))";

	private static ItemCatalogueLineDTO line(List<ItemCatalogueLineDTO> lines, String code) {
		return lines.stream().filter(line -> code.equals(line.getCode())).findFirst().orElse(null);
	}

	private static void assertRange(double min, double max, ItemCatalogueLineDTO line) {
		assertEquals(min, line.getPriceMin(), 0.0001, line.getCode() + " min");
		assertEquals(max, line.getPriceMax(), 0.0001, line.getCode() + " max");
	}

	/** The lines of the criteria at the point, with what the service adds after the page. */
	private static List<ItemCatalogueLineDTO> pointLines(boolean all, long point, String rowStatus) {
		ItemCatalogueQuery.Criteria criteria = at(criteria(all, null, null), point, rowStatus);
		List<ItemCatalogueLineDTO> lines = lines(criteria);
		try (Session session = sessions.openSession()) {
			List<Object[]> rows = new java.util.ArrayList<>(session
					.createQuery(HoStockPointItemRepository.POINT_ROWS_OF_NAV_ITEMS, Object[].class)
					.setParameter("pointId", point).setParameterList("navItemNos", Arrays.asList("100", "NONE")).list());
			List<Long> itemIds = lines.stream().map(ItemCatalogueLineDTO::getItemId).filter(java.util.Objects::nonNull)
					.collect(Collectors.toList());
			if (!itemIds.isEmpty()) {
				rows.addAll(session.createQuery(HoStockPointItemRepository.POINT_ROWS_OF_ITEMS, Object[].class)
						.setParameter("pointId", point).setParameterList("itemIds", itemIds).list());
			}
			HoItemCatalogueService.pointPrices(lines, rows, criteria.isActiveOnly(), rowStatus);
		}
		return lines;
	}

	@Test
	@DisplayName("Without a point the lines' SQL is core's own, unchanged; with one it differs only by the line price and its join")
	void lineSqlUnchanged() {
		ItemCatalogueQuery.Criteria plain = criteria(true, 10.0, null);
		plain.setSort("price");
		String own = ItemCatalogueQuery.build(plain, false).getLines();
		assertFalse(own.contains("ho_stock_point_item pr"), own);
		assertTrue(own.contains("MIN(" + OWN_PRICE + ") AS price_min"), own);
		assertTrue(own.contains("MIN(" + OWN_PRICE + ") ASC"), own);

		ItemCatalogueQuery.Criteria point = at(criteria(true, 10.0, null), p1, "ACTIVE");
		point.setSort("price");
		String withPoint = ItemCatalogueQuery.build(point, false).getLines();
		point.setLinePrice(null);
		point.setLinePriceJoin(null);
		String withoutHook = ItemCatalogueQuery.build(point, false).getLines();
		assertEquals(withoutHook, withPoint.replace(HoItemCatalogueQuery.LINE_PRICE, OWN_PRICE)
				.replace(HoItemCatalogueQuery.LINE_PRICE_JOIN + " AND (pr.active IS NULL OR pr.active = 1)", ""));
	}

	@Test
	@DisplayName("Line price: the items' own prices without a point; with one, the selling prices of the rows of the row status there, a NAV line as their range")
	void linePrice() {
		List<ItemCatalogueLineDTO> own = lines(criteria(true, null, null));
		assertRange(11.9, 59.5, line(own, "100"));
		assertFalse(line(own, "100").isPointPrice());
		assertRange(30, 30, line(own, "X-1"));

		List<ItemCatalogueLineDTO> active = pointLines(true, p1, "ACTIVE");
		// 100~A 23.8, 100~C its clearance 35.7; 100~B's row inactive, not counted
		assertRange(23.8, 35.7, line(active, "100"));
		assertTrue(line(active, "100").isPointPrice());
		assertRange(25, 25, line(active, "X-1"));
		assertRange(7, 7, line(active, "Z-1"));
		assertRange(23.8, 59.5, line(pointLines(true, p1, "ALL"), "100"));
		assertRange(59.5, 59.5, line(pointLines(true, p1, "INACTIVE"), "100"));
		assertRange(23.8, 23.8, line(pointLines(true, p2, "ACTIVE"), "100"), "P2: a clearance price 0 does not apply");
	}

	private static void assertRange(double min, double max, ItemCatalogueLineDTO line, String message) {
		assertEquals(min, line.getPriceMin(), 0.0001, message);
		assertEquals(max, line.getPriceMax(), 0.0001, message);
	}

	@Test
	@DisplayName("Price sort with a point: by the lowest selling price there, not the item's own price")
	void pointPriceSort() {
		ItemCatalogueQuery.Criteria own = criteria(true, null, null);
		own.setSort("price");
		assertEquals(Arrays.asList("item:Y-1", "item:Z-1", "nav:100", "item:X-1"), codes(own), "5, 7, 11.9, 30");
		ItemCatalogueQuery.Criteria point = at(criteria(true, null, null), p1, "ACTIVE");
		point.setSort("price");
		assertEquals(Arrays.asList("item:Z-1", "nav:100", "item:X-1"), codes(point), "7, 23.8, 25 (X-1's own 30)");
		point.setDescending(true);
		assertEquals(Arrays.asList("item:X-1", "nav:100", "item:Z-1"), codes(point));
	}

	@Test
	@DisplayName("Clearance at the point: the count of rows selling at their clearance price and, then, the normal range; nothing without one")
	void pointClearance() {
		List<ItemCatalogueLineDTO> active = pointLines(true, p1, "ACTIVE");
		ItemCatalogueLineDTO nav = line(active, "100");
		assertEquals(1, nav.getClearanceVariants(), "100~C");
		assertEquals(23.8, nav.getNormalPriceMin(), 0.0001, "100~A 23.8");
		assertEquals(47.6, nav.getNormalPriceMax(), 0.0001, "100~C's normal 40 with VAT");
		ItemCatalogueLineDTO x = line(active, "X-1");
		assertEquals(1, x.getClearanceVariants());
		assertEquals(40, x.getNormalPriceMin(), 0.0001);
		ItemCatalogueLineDTO z = line(active, "Z-1");
		assertEquals(0, z.getClearanceVariants());
		assertNull(z.getNormalPriceMin(), "no clearance, no normal range");

		ItemCatalogueLineDTO inactive = line(pointLines(true, p1, "INACTIVE"), "100");
		assertEquals(0, inactive.getClearanceVariants(), "only 100~B's row, not in clearance");
		assertNull(inactive.getNormalPriceMax());
		assertEquals(0, line(pointLines(true, p2, "ACTIVE"), "100").getClearanceVariants(), "P2: a clearance price 0");
		// Item status Active: Z-1 (an inactive item) has no line; the others as with All
		List<ItemCatalogueLineDTO> activeItems = pointLines(false, p1, "ACTIVE");
		assertEquals(Arrays.asList("nav:100", "item:X-1"), codes(activeItems));
		assertEquals(1, line(activeItems, "100").getClearanceVariants());
	}
}
