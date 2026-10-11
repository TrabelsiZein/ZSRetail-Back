package com.digithink.zsretail.holink.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.holink.dto.DownRecordRowDTO;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * The store's list of the records received from the head office (DownRecordRepository.TO_CHECK and its count), run as
 * they are with Hibernate over an in-memory H2 database (no Spring context): the asked statuses only, ERROR before
 * WAITING then by code, sorted and paged by the database, and the SQL never selects the payload.
 */
class DownRecordToCheckQueryTest {

	private static SessionFactory sessions;
	private static final List<String> SQL = new java.util.concurrent.CopyOnWriteArrayList<>();

	/** Hibernate's statement inspector: every SQL statement prepared. */
	public static class Recorder implements org.hibernate.resource.jdbc.spi.StatementInspector {
		private static final long serialVersionUID = 1L;

		@Override
		public String inspect(String sql) {
			SQL.add(sql);
			return sql;
		}
	}

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(DownRecord.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:downrecords;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop")
				.setProperty("hibernate.session_factory.statement_inspector", Recorder.class.getName())
				.buildSessionFactory();
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			for (String code : new String[] { "W2", "A1", "E2", "W1", "A2", "E1", "W3" }) {
				DownRecordStatus status = code.startsWith("E") ? DownRecordStatus.ERROR
						: code.startsWith("W") ? DownRecordStatus.WAITING : DownRecordStatus.APPLIED;
				session.persist(row(DataDomain.CATALOGUE, "ITEM:" + code, status));
			}
			session.persist(row(DataDomain.PROMOTIONS, "P1", DownRecordStatus.ERROR));
			session.getTransaction().commit();
		}
	}

	private static DownRecord row(DataDomain domain, String code, DownRecordStatus status) {
		DownRecord row = new DownRecord();
		row.setDomain(domain);
		row.setRecordCode(code);
		row.setRecordName("n " + code);
		row.setStatus(status);
		row.setReason(status == DownRecordStatus.APPLIED ? null : "why " + code);
		row.setPayload("{\"big\":\"" + code + "\"}");
		row.setReceivedAt(LocalDateTime.of(2026, 10, 8, 9, 0));
		row.setStatusSince(LocalDateTime.of(2026, 10, 8, 9, 0));
		return row;
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static List<String> page(Collection<DownRecordStatus> statuses, int first, int max) {
		try (Session session = sessions.openSession()) {
			return session.createQuery(DownRecordRepository.TO_CHECK, DownRecordRowDTO.class)
					.setParameter("domain", DataDomain.CATALOGUE).setParameter("statuses", statuses).setFirstResult(first)
					.setMaxResults(max).getResultList().stream().map(DownRecordRowDTO::getRecordCode)
					.collect(Collectors.toList());
		}
	}

	private static long count(Collection<DownRecordStatus> statuses) {
		try (Session session = sessions.openSession()) {
			return session.createQuery(DownRecordRepository.TO_CHECK_COUNT, Long.class)
					.setParameter("domain", DataDomain.CATALOGUE).setParameter("statuses", statuses).getSingleResult();
		}
	}

	@Test
	@DisplayName("WAITING and ERROR of the domain, ERROR first then by code, paged by the database; the payload never selected")
	void toCheck() {
		List<DownRecordStatus> both = Arrays.asList(DownRecordStatus.WAITING, DownRecordStatus.ERROR);
		SQL.clear();
		assertEquals(Arrays.asList("ITEM:E1", "ITEM:E2", "ITEM:W1", "ITEM:W2", "ITEM:W3"), page(both, 0, 20));
		String select = SQL.get(SQL.size() - 1).toLowerCase();
		assertFalse(select.contains("payload"), select);
		assertEquals(5, count(both));
		assertEquals(Arrays.asList("ITEM:W1", "ITEM:W2"), page(both, 2, 2), "the third and fourth rows");
		assertEquals(Arrays.asList("ITEM:W1", "ITEM:W2", "ITEM:W3"),
				page(Collections.singleton(DownRecordStatus.WAITING), 0, 20));
		assertEquals(2, count(Collections.singleton(DownRecordStatus.ERROR)));
	}
}
