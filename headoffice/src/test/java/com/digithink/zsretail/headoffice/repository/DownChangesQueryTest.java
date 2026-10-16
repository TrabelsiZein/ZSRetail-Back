package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.headoffice.model.HoDownChange;

/**
 * Copies down, speed: the query of a store's pull (HoDownChangeRepository.CHANGED, native, the domain cast to varchar
 * so SQL Server seeks ix_ho_down_change_pull), run as it is with Hibernate over an in-memory H2 database (no Spring
 * context): the same answer as the JPQL query of before. CATALOGUE: A for store 1 (3), B every store (5), C for store
 * 2 (4), D every store (2) and for store 1 (6); PROMOTIONS: P every store (1).
 */
class DownChangesQueryTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(HoDownChange.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:downchanges;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			change(session, DataDomain.CATALOGUE, "A", 1L, 3);
			change(session, DataDomain.CATALOGUE, "B", null, 5);
			change(session, DataDomain.CATALOGUE, "C", 2L, 4);
			change(session, DataDomain.CATALOGUE, "D", null, 2);
			change(session, DataDomain.CATALOGUE, "D", 1L, 6);
			change(session, DataDomain.PROMOTIONS, "P", null, 1);
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static void change(Session session, DataDomain domain, String code, Long storeId, long version) {
		HoDownChange change = new HoDownChange();
		change.setDomain(domain);
		change.setRecordCode(code);
		change.setStoreId(storeId);
		change.setChangeVersion(version);
		session.persist(change);
	}

	/** "code:number" of the pull of store 1, as the repository runs it (the page: setMaxResults). */
	@SuppressWarnings("unchecked")
	private static List<String> changed(long after, long upTo, int size) {
		try (Session session = sessions.openSession()) {
			List<Object[]> rows = session.createNativeQuery(HoDownChangeRepository.CHANGED)
					.setParameter("domain", DataDomain.CATALOGUE.name()).setParameter("storeId", 1L)
					.setParameter("after", after).setParameter("upTo", upTo).setMaxResults(size).list();
			return rows.stream().map(row -> row[0] + ":" + ((Number) row[1]).longValue()).collect(Collectors.toList());
		}
	}

	@Test
	@DisplayName("The store's rows and the every-store rows, the highest number of a code, oldest first; the other"
			+ " store's and the other domain's left out")
	void storeAndEveryStore() {
		assertEquals(Arrays.asList("A:3", "B:5", "D:6"), changed(0, 10, 101));
	}

	@Test
	@DisplayName("After the cursor and up to the horizon; the page bounds the number of codes")
	void cursorHorizonAndPage() {
		assertEquals(Arrays.asList("B:5", "D:6"), changed(3, 10, 101));
		assertEquals(Arrays.asList("D:2", "A:3", "B:5"), changed(0, 5, 101), "D's store row (6) is past the horizon");
		assertEquals(Arrays.asList("A:3", "B:5"), changed(0, 10, 2));
		assertEquals(Arrays.asList(), changed(6, 10, 101));
	}
}
