package com.digithink.zsretail.core.service;

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

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemRepository;

/**
 * 3.1.0 (ZEN): the two grouped queries of the till's catégories and sections (ItemRepository.POS_SECTION_COUNTS,
 * POS_CATEGORY_COUNTS), run as they are with Hibernate over an in-memory H2 database (as PosGridCountQueryTest), against
 * the grid's rule in Java (ItemService.listedInPos). The codes of the local ZEN test rows: TEST-FAM, TEST-SF, TEST-SEC.
 */
class PosLevelCountQueryTest {

	private static SessionFactory sessions;
	private static final Map<String, Long> ids = new HashMap<>();

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class).addAnnotatedClass(ItemSubFamily.class)
				.addAnnotatedClass(Item.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:poslevels;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			ItemFamily family = family(session, "TEST-FAM", "VET");
			ItemSubFamily sub = subFamily(session, "TEST-SF", family, true);
			ItemSubFamily other = subFamily(session, "TEST-SF2", family, true);
			ItemSubFamily closed = subFamily(session, "TEST-CLOSED", family, false);
			// sections: two listed in TEST-SF, one inactive, one without price, one blank code, one without section
			item(session, "SEC-1", family, sub, null, "TEST-SEC", true, true, 10.0);
			item(session, "SEC-2", family, sub, null, "TEST-SEC", null, true, 10.0);
			item(session, "SEC-INACTIVE", family, sub, null, "TEST-SEC", false, true, 10.0);
			item(session, "SEC-NO-PRICE", family, sub, null, "TEST-SEC", true, true, 0.0);
			item(session, "SEC-BLANK", family, sub, null, "", true, true, 10.0);
			item(session, "NO-SEC", family, sub, null, null, true, true, 10.0);
			// the same code in another sub-family, counted there; an inactive sub-family is never counted
			item(session, "SF2-SEC", family, other, null, "TEST-SEC", true, true, 10.0);
			item(session, "CLOSED-SEC", family, closed, null, "TEST-SEC", true, true, 10.0);
			// the local ZEN rows: a section code, no famille, no sous-famille (no tile reaches them)
			item(session, "15ZCR104~001", null, null, null, "TEST-SEC", true, true, 24.5);
			// catégories: items without famille fall through to their catégorie; one with a famille does not
			item(session, "CAT-1", null, null, "VET", null, true, true, 5.0);
			item(session, "CAT-2", null, null, "VET", null, null, true, 5.0); // show_in_pos is NOT NULL
			item(session, "CAT-HIDDEN", null, null, "VET", null, true, false, 5.0);
			item(session, "CAT-DIVERS-NO-PRICE", null, null, "DIVERS", null, true, true, null);
			item(session, "CAT-WITH-FAMILY", family, sub, "MEUBLES", null, true, true, 5.0);
			session.getTransaction().commit();
			ids.put("TEST-SF", sub.getId());
			ids.put("TEST-SF2", other.getId());
			ids.put("TEST-CLOSED", closed.getId());
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static ItemFamily family(Session session, String code, String categoryCode) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName("Family " + code);
		family.setCategoryCode(categoryCode);
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

	private static void item(Session session, String code, ItemFamily family, ItemSubFamily subFamily,
			String categoryCode, String sectionCode, Boolean active, Boolean showInPos, Double unitPrice) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(code);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setCategoryCode(categoryCode);
		item.setSectionCode(sectionCode);
		item.setActive(active);
		item.setShowInPos(showInPos);
		item.setUnitPrice(unitPrice);
		session.persist(item);
	}

	private static List<Object[]> rows(String query) {
		try (Session session = sessions.openSession()) {
			return session.createQuery(query, Object[].class).getResultList();
		}
	}

	private static List<Item> all() {
		try (Session session = sessions.openSession()) {
			return session.createQuery("from Item", Item.class).getResultList();
		}
	}

	@Test
	@DisplayName("Sections: the items the grid lists, by their own active sub-family and section code; blank, no sub-family or an inactive one: not counted")
	void sections() {
		PosCatalogueService.SectionCounts counts = PosCatalogueService.SectionCounts
				.of(rows(ItemRepository.POS_SECTION_COUNTS));
		assertEquals(2, counts.count(ids.get("TEST-SF"), "TEST-SEC"));
		assertEquals(2, counts.count(ids.get("TEST-SF"), " TEST-SEC "), "codes compared trimmed");
		assertEquals(1, counts.count(ids.get("TEST-SF2"), "TEST-SEC"), "the same code, under its own sub-family");
		assertFalse(counts.has(ids.get("TEST-CLOSED"), "TEST-SEC"), "an inactive sub-family is never shown");
		assertFalse(counts.has(ids.get("TEST-SF"), ""), "a blank code is no section");
	}

	@Test
	@DisplayName("Sections: the query agrees with the grid's rule in Java, sub-family by sub-family")
	void sectionsAsTheGrid() {
		PosCatalogueService.SectionCounts counts = PosCatalogueService.SectionCounts
				.of(rows(ItemRepository.POS_SECTION_COUNTS));
		List<Item> all = all();
		for (String subFamily : new String[] { "TEST-SF", "TEST-SF2", "TEST-CLOSED" }) {
			Long id = ids.get(subFamily);
			long expected = all.stream().filter(ItemService::listedInPos)
					.filter(item -> item.getItemSubFamily() != null && id.equals(item.getItemSubFamily().getId())
							&& !Boolean.FALSE.equals(item.getItemSubFamily().getActive()))
					.filter(item -> "TEST-SEC".equals(PosCatalogueService.levelCode(item.getSectionCode()))).count();
			assertEquals(expected, counts.count(id, "TEST-SEC"), subFamily);
		}
	}

	@Test
	@DisplayName("Catégories: the items the grid lists without famille and sous-famille, by their own catégorie code; one with a famille is not counted")
	void categories() {
		Map<String, Long> counts = new HashMap<>();
		for (Object[] row : rows(ItemRepository.POS_CATEGORY_COUNTS)) {
			counts.put((String) row[0], ((Number) row[1]).longValue());
		}
		assertEquals(Map.of("VET", 2L), counts);
		long expected = all().stream().filter(ItemService::listedInPos)
				.filter(item -> item.getItemFamily() == null && item.getItemSubFamily() == null
						&& "VET".equals(item.getCategoryCode()))
				.count();
		assertEquals(expected, counts.get("VET"));
		assertTrue(rows(ItemRepository.POS_GRID_COUNTS).size() > 0, "the grid's query still runs with POS_LISTED");
	}
}
