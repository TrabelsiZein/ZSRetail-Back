package com.digithink.zsretail.core.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.TreeMap;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.ItemFamily;

/**
 * Item variants (ZEN), step 11b: the count of the "Catégories" page (ItemFamilyRepository.FAMILY_COUNTS_BY_CATEGORY),
 * run as it is with Hibernate over an in-memory H2 database (no Spring context). VET has two families (one inactive),
 * MEUBLES one; a family without catégorie (EMTOP, Happyness) is never counted.
 */
class ItemTreeQueryTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(ItemFamily.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:itemtree;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			family(session, "HOM", "VET", true);
			family(session, "FEM", "VET", false);
			family(session, "SALON", "MEUBLES", true);
			family(session, "HAPPY", null, true);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static void family(Session session, String code, String categoryCode, boolean active) {
		ItemFamily family = new ItemFamily();
		family.setCode(code);
		family.setName(code);
		family.setCategoryCode(categoryCode);
		family.setActive(active);
		session.persist(family);
	}

	@Test
	@DisplayName("Families per catégorie: active or not; a family without catégorie is not counted")
	void countsByCategory() {
		Map<String, Long> counts = new TreeMap<>();
		try (Session session = sessions.openSession()) {
			for (Object[] row : session.createQuery(ItemFamilyRepository.FAMILY_COUNTS_BY_CATEGORY, Object[].class)
					.list()) {
				counts.put((String) row[0], ((Number) row[1]).longValue());
			}
		}
		Map<String, Long> expected = new TreeMap<>();
		expected.put("MEUBLES", 1L);
		expected.put("VET", 2L);
		assertEquals(expected, counts);
	}
}
