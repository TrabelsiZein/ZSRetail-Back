package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.hibernate.query.Query;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.query.QueryUtils;

import com.digithink.zsretail.core.dto.NavItemFilter;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.service.NavItemService;

/**
 * Item variants (ZEN), step 11a: the grouped queries of the "NAV items" page (ItemRepository.NAV_ITEMS,
 * NAV_ITEMS_COUNT, NAV_ITEM_VARIANTS), run as they are with Hibernate over an in-memory H2 database (no Spring context).
 * NAV items 70061 (four variants, one inactive, sizes out of order, one without size order), 14ZDU05 (two variants),
 * LS-10PC (one inactive variant only); two items without a NAV item number (EMTOP or Happyness items) are never a line.
 */
class NavItemQueryTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:navitems;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily hom = family(session, "HOM", "Homme");
			ItemSubFamily chem = subFamily(session, "CHEM", "Chemises", hom);
			variant(session, "70061~L", "70061", "CHEMISE", hom, chem, 3, "BLK", true, "4");
			variant(session, "70061~S", "70061", "CHEMISE", hom, chem, 1, "BLK", true, "2");
			variant(session, "70061~M", "70061", "CHEMISE", hom, chem, 2, "BLK", false, "7");
			variant(session, "70061~X", "70061", "CHEMISE", hom, chem, null, "BLK", true, null);
			variant(session, "14ZDU05~B", "14ZDU05", "DUVET", null, null, 1, "WHT", true, "1.5");
			variant(session, "14ZDU05~A", "14ZDU05", "DUVET", null, null, 1, "BLU", null, "0.5");
			variant(session, "LS-10PC~LS-10PC-B", "LS-10PC", "LOT 10 PIECES", null, null, null, null, false, "9");
			variant(session, "EMTOP-1", null, "CHEMISE EMTOP", hom, chem, null, null, true, "100");
			variant(session, "HAPPY-1", null, "Duvet happy", null, null, null, null, true, "100");
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static ItemFamily family(Session session, String code, String name) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName(name);
		session.persist(family);
		return family;
	}

	private static ItemSubFamily subFamily(Session session, String code, String name, ItemFamily family) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setCode(code);
		subFamily.setName(name);
		subFamily.setItemFamily(family);
		session.persist(subFamily);
		return subFamily;
	}

	private static void variant(Session session, String code, String navItemNo, String name, ItemFamily family,
			ItemSubFamily subFamily, Integer sizeOrder, String color, Boolean active, String stock) {
		Item item = new Item();
		item.setItemCode(code);
		item.setNavItemNo(navItemNo);
		item.setName(name);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setSizeSortOrder(sizeOrder);
		item.setColorCode(color);
		item.setActive(active);
		item.setStockQuantity(stock == null ? null : new BigDecimal(stock));
		item.setCategoryCode(navItemNo == null ? null : "VET");
		item.setSectionCode("70061".equals(navItemNo) ? "MANCHE" : null);
		session.persist(item);
	}

	/** The page of NAV_ITEMS as Spring Data runs it: the service's default sort (by number) appended by QueryUtils. */
	private static List<Object[]> lines(String search, Boolean activeOnly, int first, int max) {
		try (Session session = sessions.openSession()) {
			String statement = QueryUtils.applySorting(ItemRepository.NAV_ITEMS, NavItemService.sortOf(new NavItemFilter()),
					"i");
			Query<Object[]> query = session.createQuery(statement, Object[].class);
			bind(query, search, activeOnly);
			return query.setFirstResult(first).setMaxResults(max).list();
		}
	}

	private static long count(String search, Boolean activeOnly) {
		try (Session session = sessions.openSession()) {
			Query<Long> query = session.createQuery(ItemRepository.NAV_ITEMS_COUNT, Long.class);
			bind(query, search, activeOnly);
			return query.uniqueResult();
		}
	}

	/** The parameters of the "NAV items" page: search and status only, no barcode, no level (NavItemService.page). */
	private static void bind(Query<?> query, String search, Boolean activeOnly) {
		query.setParameter("search", search).setParameterList("barcodeItemIds", ItemRepository.NO_ITEM_IDS)
				.setParameter("activeOnly", activeOnly).setParameter("categoryCode", null)
				.setParameter("familyId", null).setParameter("subFamilyId", null).setParameter("sectionCode", null);
	}

	private static List<String> variants(String navItemNo, Boolean activeOnly) {
		try (Session session = sessions.openSession()) {
			return session.createQuery(ItemRepository.NAV_ITEM_VARIANTS, Item.class).setParameter("navItemNo", navItemNo)
					.setParameter("activeOnly", activeOnly).list().stream().map(Item::getItemCode)
					.collect(Collectors.toList());
		}
	}

	private static List<String> numbers(List<Object[]> lines) {
		return lines.stream().map(row -> (String) row[0]).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Every variant: one line per NAV item number, by number; items without a number are no line")
	void everyVariant() {
		List<Object[]> lines = lines(null, null, 0, 20);
		assertEquals(Arrays.asList("14ZDU05", "70061", "LS-10PC"), numbers(lines));
		Object[] chemise = lines.get(1);
		assertEquals("CHEMISE", chemise[1]);
		assertEquals("Homme", chemise[2]);
		assertEquals("Chemises", chemise[3]);
		assertEquals("VET", chemise[4]);
		assertEquals("MANCHE", chemise[5]);
		assertEquals(4L, ((Number) chemise[6]).longValue());
		assertEquals(0, new BigDecimal("13").compareTo((BigDecimal) chemise[7]), "4 + 2 + 7, a null stock adds nothing");
		Object[] duvet = lines.get(0);
		assertNull(duvet[2], "no family");
		assertNull(duvet[5], "no section");
		assertEquals(2L, ((Number) duvet[6]).longValue());
		assertEquals(3, count(null, null));
	}

	@Test
	@DisplayName("Active only: the inactive variants are not counted, a NAV item with only inactive variants has no line")
	void activeOnly() {
		List<Object[]> lines = lines(null, Boolean.TRUE, 0, 20);
		assertEquals(Arrays.asList("14ZDU05", "70061"), numbers(lines));
		assertEquals(2L, ((Number) lines.get(0)[6]).longValue(), "active null counts as active");
		assertEquals(3L, ((Number) lines.get(1)[6]).longValue());
		assertEquals(0, new BigDecimal("6").compareTo((BigDecimal) lines.get(1)[7]), "the inactive M (7) left out");
		assertEquals(2, count(null, Boolean.TRUE));
	}

	@Test
	@DisplayName("Search: NAV item number or name, contains, any case (the service's like())")
	void search() {
		assertEquals(Arrays.asList("70061"), numbers(lines(NavItemService.likeOf("0061"), null, 0, 20)));
		assertEquals(Arrays.asList("14ZDU05"), numbers(lines(NavItemService.likeOf("duv"), null, 0, 20)));
		assertEquals(Arrays.asList("70061", "LS-10PC"), numbers(lines(NavItemService.likeOf("C"), null, 0, 20)));
		assertEquals(1, count(NavItemService.likeOf("chemise"), null), "the EMTOP item named CHEMISE is no line");
		assertEquals(0, count(NavItemService.likeOf("happy"), null));
	}

	@Test
	@DisplayName("Paging: offset and size over the lines, not over the variants")
	void paging() {
		assertEquals(Arrays.asList("14ZDU05", "70061"), numbers(lines(null, null, 0, 2)));
		assertEquals(Arrays.asList("LS-10PC"), numbers(lines(null, null, 2, 2)));
	}

	@Test
	@DisplayName("Names (promotion targets): one row per number asked that an item carries, inactive variants too")
	void names() {
		try (Session session = sessions.openSession()) {
			List<Object[]> rows = session.createQuery(ItemRepository.NAV_ITEM_NAMES, Object[].class)
					.setParameter("navItemNos", Arrays.asList("70061", "LS-10PC", "UNKNOWN", "EMTOP-1")).list();
			Map<String, String> names = rows.stream().collect(Collectors.toMap(row -> (String) row[0], row -> (String) row[1]));
			assertEquals(2, names.size(), names.toString());
			assertEquals("CHEMISE", names.get("70061"));
			assertEquals("LOT 10 PIECES", names.get("LS-10PC"), "its only variant is inactive");
		}
	}

	@Test
	@DisplayName("Variants: by size order (none last), colour, then code; the status filter applies")
	void variantOrder() {
		assertEquals(Arrays.asList("70061~S", "70061~M", "70061~L", "70061~X"), variants("70061", null));
		assertEquals(Arrays.asList("70061~S", "70061~L", "70061~X"), variants("70061", Boolean.TRUE));
		assertEquals(Arrays.asList("14ZDU05~A", "14ZDU05~B"), variants("14ZDU05", null), "same size: by colour");
		assertEquals(Arrays.asList(), variants("LS-10PC", Boolean.TRUE));
		assertEquals(Arrays.asList(), variants("EMTOP-1", null));
	}
}
