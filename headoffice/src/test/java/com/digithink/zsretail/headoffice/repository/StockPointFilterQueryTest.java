package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import org.springframework.boot.orm.jpa.hibernate.SpringPhysicalNamingStrategy;

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

/**
 * 3.1.1, step 1 of retiring "Items by point de stock": the Item cards list with a point de stock chosen
 * ({@link HoItemCatalogueQuery#stockPoint(ItemCatalogueQuery.Criteria, Long, String)}): the row status at that point and
 * the price range on the point's row selling price with VAT. Run as the page runs it (ItemCatalogueQuery.build) over an
 * in-memory H2 database, as ItemCatalogueQueryTest.
 * <p>
 * Items (own price before VAT, VAT): NAV item 100, 100~A (10, 19 %) and 100~B (50, 19 %); X-1 (30, 0 %), Y-1 (5, no
 * row), Z-1 (7, an inactive item). Point P1: 100~A active 20 (23.8 with VAT); 100~B inactive 50 (59.5); X-1 active 40 in
 * clearance at 25; Z-1 active 7. Point P2: 100~A active 20, clearance with a price 0 (does not apply); X-1 40, active
 * null (counts as active).
 */
class StockPointFilterQueryTest {

	private static SessionFactory sessions;
	private static long p1;
	private static long p2;

	@BeforeAll
	static void database() {
		Configuration configuration = new Configuration();
		configuration.setPhysicalNamingStrategy(new SpringPhysicalNamingStrategy());
		configuration.setImplicitNamingStrategy(new SpringImplicitNamingStrategy());
		sessions = configuration.addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class).addAnnotatedClass(ItemBarcode.class).addAnnotatedClass(HoStockPoint.class)
				.addAnnotatedClass(ItemCategory.class).addAnnotatedClass(ItemSection.class)
				.addAnnotatedClass(HoStockPointItem.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:stockpointfilter;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			Item a = item(session, "100~A", "100", true, 10.0, 19);
			Item b = item(session, "100~B", "100", true, 50.0, 19);
			Item x = item(session, "X-1", null, true, 30.0, 0);
			item(session, "Y-1", null, true, 5.0, 0);
			Item z = item(session, "Z-1", null, false, 7.0, 0);
			HoStockPoint one = point(session, "P1");
			HoStockPoint two = point(session, "P2");
			p1 = one.getId();
			p2 = two.getId();
			row(session, one, a, 20.0, true, null, null);
			row(session, one, b, 50.0, false, null, null);
			row(session, one, x, 40.0, true, true, new BigDecimal("25"));
			row(session, one, z, 7.0, true, null, null);
			row(session, two, a, 20.0, true, true, BigDecimal.ZERO);
			row(session, two, x, 40.0, null, null, null);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
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
		ItemCatalogueQuery.Built built = ItemCatalogueQuery.build(criteria, true);
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
		assertEquals(2, inactive.get(0).getVariants(), "the line keeps every variant of the item status");
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
		assertEquals(Collections.emptyList(), codes(at(criteria(true, 26.0, null), p1, "ACTIVE")),
				"100~B matches the price but its row is inactive; A 23.8 does not");
		assertEquals(Collections.singletonList("nav:100"), codes(at(criteria(true, 26.0, null), p1, "INACTIVE")));

		// P2: a clearance price 0 does not apply (A at 23.8); X-1's row at 40, its active null
		assertEquals(Collections.singletonList("nav:100"), codes(at(criteria(true, null, 24.0), p2, null)));
		assertEquals(Collections.singletonList("item:X-1"), codes(at(criteria(true, 39.0, null), p2, null)));
	}
}
