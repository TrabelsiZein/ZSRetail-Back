package com.digithink.zsretail.headoffice.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.digithink.zsretail.headoffice.model.HoDownSequence;

/**
 * 3.1.1: the two queries of HoDownSequenceRepository, native, the domain cast to varchar so SQL Server seeks
 * uk_ho_down_sequence_domain (with the driver's nvarchar it scanned the table: a pull of LOYALTY waited behind a writer
 * holding the CATALOGUE row, HTTP 500 after the 15 s timeout). Run as they are with Hibernate over an in-memory H2
 * database (no Spring context): the update adds to its own domain only, the read answers its own domain only.
 */
class DownSequenceQueriesTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(HoDownSequence.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:downsequence;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			for (DataDomain domain : new DataDomain[] { DataDomain.CATALOGUE, DataDomain.LOYALTY }) {
				HoDownSequence sequence = new HoDownSequence();
				sequence.setDomain(domain);
				sequence.setLastVersion(domain == DataDomain.CATALOGUE ? 261151 : 14);
				session.persist(sequence);
			}
			session.getTransaction().commit();
		}
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	@SuppressWarnings("unchecked")
	private static List<Long> lastVersion(DataDomain domain) {
		try (Session session = sessions.openSession()) {
			List<Number> rows = session.createNativeQuery(HoDownSequenceRepository.LAST_VERSION)
					.setParameter("domain", domain.name()).list();
			return rows.stream().map(Number::longValue).collect(Collectors.toList());
		}
	}

	private static int incrementBy(DataDomain domain, long count) {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			int updated = session.createNativeQuery(HoDownSequenceRepository.INCREMENT_BY)
					.setParameter("domain", domain.name()).setParameter("count", count).executeUpdate();
			session.getTransaction().commit();
			return updated;
		}
	}

	@Test
	@DisplayName("Both queries cast the domain to varchar(20) (a seek on SQL Server)")
	void cast() {
		assertTrue(HoDownSequenceRepository.INCREMENT_BY.contains("domain = cast(:domain as varchar(20))"));
		assertTrue(HoDownSequenceRepository.LAST_VERSION.contains("domain = cast(:domain as varchar(20))"));
	}

	@Test
	@DisplayName("The update adds to its domain only and says how many rows; the read answers its domain only")
	void ownDomainOnly() {
		assertEquals(1, incrementBy(DataDomain.LOYALTY, 3));
		assertEquals(java.util.Collections.singletonList(17L), lastVersion(DataDomain.LOYALTY));
		assertEquals(java.util.Collections.singletonList(261151L), lastVersion(DataDomain.CATALOGUE));
		assertEquals(0, incrementBy(DataDomain.SUPPLY, 1), "no row yet: 0 (the feed then creates it)");
		assertTrue(lastVersion(DataDomain.SUPPLY).isEmpty());
	}
}
