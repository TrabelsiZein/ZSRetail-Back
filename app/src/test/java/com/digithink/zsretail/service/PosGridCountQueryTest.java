package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.repository.ItemRepository;

/**
 * 2.2.2, step 3: the one grouped query of the POS grid's counts (ItemRepository.POS_GRID_COUNTS), run as it is on the
 * three catalogue entities with Hibernate over an in-memory H2 database (no Spring context), against the grid's own rule
 * in Java (ItemService.listedInPos): the same items, counted per sub-family and per family.
 */
class PosGridCountQueryTest {

	private static SessionFactory sessions;
	private static final Map<String, Long> ids = new HashMap<>();

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:posgrid;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			// A: a sub-family with a listed item (and an inactive one), a sub-family with nothing listed, an item without
			// sub-family
			ItemFamily a = family(session, "A");
			ItemSubFamily a1 = subFamily(session, "A1", a, true);
			ItemSubFamily a2 = subFamily(session, "A2", a, true);
			item(session, "A1-OK", a, a1, true, true, 10.0);
			item(session, "A1-INACTIVE", a, a1, false, true, 10.0);
			item(session, "A2-HIDDEN", a, a2, true, false, 10.0);
			item(session, "A2-NO-PRICE", a, a2, true, true, 0.0);
			item(session, "A2-NULL-PRICE", a, a2, true, true, null);
			item(session, "A-NOSUB", a, null, true, true, 5.0);
			// B: items only without a sub-family
			ItemFamily b = family(session, "B");
			item(session, "B-NOSUB-1", b, null, true, true, 3.0);
			item(session, "B-NOSUB-2", b, null, null, true, 4.0); // active null counts as listed, as in the grid (show_in_pos is NOT NULL)
			// C: every item inactive or not shown in the POS, with and without sub-family
			ItemFamily c = family(session, "C");
			ItemSubFamily c1 = subFamily(session, "C1", c, true);
			item(session, "C1-INACTIVE", c, c1, false, true, 10.0);
			item(session, "C1-HIDDEN", c, c1, true, false, 10.0);
			item(session, "C-NOSUB-HIDDEN", c, null, true, false, 10.0);
			// D: a listed item in an inactive sub-family (the grid never shows that sub-family)
			ItemFamily d = family(session, "D");
			ItemSubFamily d1 = subFamily(session, "D1", d, false);
			item(session, "D1-OK", d, d1, true, true, 10.0);
			// E: no item at all; F: an item whose own family differs from its sub-family's (shown under the sub-family's)
			ItemFamily e = family(session, "E");
			ItemFamily f = family(session, "F");
			ItemSubFamily f1 = subFamily(session, "F1", f, true);
			item(session, "F1-OF-E", e, f1, true, true, 10.0);
			// no family at all (never reachable from the grid)
			item(session, "ORPHAN", null, null, true, true, 10.0);
			session.getTransaction().commit();
			for (Object entity : new Object[] { a, b, c, d, e, f }) {
				ids.put(((ItemFamily) entity).getCode(), ((ItemFamily) entity).getId());
			}
			for (ItemSubFamily s : new ItemSubFamily[] { a1, a2, c1, d1, f1 }) {
				ids.put(s.getCode(), s.getId());
			}
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static ItemFamily family(Session session, String code) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName("Family " + code);
		session.persist(family);
		return family;
	}

	private static ItemSubFamily subFamily(Session session, String code, ItemFamily family, boolean active) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setCode(code);
		subFamily.setName("Sub " + code);
		subFamily.setItemFamily(family);
		subFamily.setActive(active);
		session.persist(subFamily);
		return subFamily;
	}

	private static void item(Session session, String code, ItemFamily family, ItemSubFamily subFamily, Boolean active,
			Boolean showInPos, Double unitPrice) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(code);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setActive(active);
		item.setShowInPos(showInPos);
		item.setUnitPrice(unitPrice);
		session.persist(item);
	}

	private static PosCatalogueService.GridCounts counts() {
		try (Session session = sessions.openSession()) {
			List<Object[]> rows = session.createQuery(ItemRepository.POS_GRID_COUNTS, Object[].class).getResultList();
			return PosCatalogueService.GridCounts.of(rows);
		}
	}

	@Test
	@DisplayName("Families: a family counts its listed items whatever their sub-family, including none; inactive or hidden items, an inactive sub-family, no item: empty")
	void families() {
		PosCatalogueService.GridCounts counts = counts();
		assertTrue(counts.familyHasItems(ids.get("A")), "listed items in a sub-family and without");
		assertTrue(counts.familyHasItems(ids.get("B")), "items only without a sub-family");
		assertFalse(counts.familyHasItems(ids.get("C")), "every item inactive or not shown in the POS");
		assertFalse(counts.familyHasItems(ids.get("D")), "its only listed item is in an inactive sub-family");
		assertFalse(counts.familyHasItems(ids.get("E")), "no item of its own: F1-OF-E is shown under F");
		assertTrue(counts.familyHasItems(ids.get("F")), "the item of its sub-family, whatever the item's own family");
	}

	@Test
	@DisplayName("Sub-families: the count of the query is the number of items the grid lists (ItemService.listedInPos)")
	void subFamiliesAsTheGrid() {
		PosCatalogueService.GridCounts counts = counts();
		try (Session session = sessions.openSession()) {
			for (String code : new String[] { "A1", "A2", "C1", "D1", "F1" }) {
				List<Item> items = session.createQuery("from Item i where i.itemSubFamily.id = :id", Item.class)
						.setParameter("id", ids.get(code)).getResultList();
				long listed = items.stream().filter(ItemService::listedInPos).count();
				long expected = "D1".equals(code) ? 0 : listed; // an inactive sub-family is never shown
				assertEquals(expected, counts.subFamilyCount(ids.get(code)), code);
			}
		}
		assertEquals(1, counts.subFamilyCount(ids.get("A1")));
		assertFalse(counts.subFamilyHasItems(ids.get("A2")));
		assertFalse(counts.subFamilyHasItems(ids.get("C1")));
	}

	@Test
	@DisplayName("The family rule and the query agree item by item: a family has items exactly when the grid lists one under it")
	void familiesAsTheGrid() {
		PosCatalogueService.GridCounts counts = counts();
		try (Session session = sessions.openSession()) {
			List<Item> all = session.createQuery("from Item", Item.class).getResultList();
			for (String code : new String[] { "A", "B", "C", "D", "E", "F" }) {
				Long familyId = ids.get(code);
				boolean listed = all.stream().filter(ItemService::listedInPos).anyMatch(item -> {
					ItemSubFamily sub = item.getItemSubFamily();
					if (sub != null) {
						return Boolean.FALSE.equals(sub.getActive()) ? false : familyId.equals(sub.getItemFamily().getId());
					}
					return item.getItemFamily() != null && familyId.equals(item.getItemFamily().getId());
				});
				assertEquals(listed, counts.familyHasItems(familyId), code);
			}
		}
	}
}
