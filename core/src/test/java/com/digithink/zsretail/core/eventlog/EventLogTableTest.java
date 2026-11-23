package com.digithink.zsretail.core.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;

import javax.persistence.PersistenceException;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.model.EventLogSequence;
import com.digithink.zsretail.core.eventlog.repository.EventLogSequenceRepository;

/**
 * 3.4.0, event log step 1: the two tables made by Hibernate on an in-memory H2 database (as a start does), the queries of
 * EventLogSequenceRepository run as they are (read from their annotations), the unique UUID (a till's event sent again
 * is not stored twice), the one number row per importance, and a row never updated (Immutable).
 */
class EventLogTableTest {

	private static SessionFactory sessions;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(EventLogEntry.class).addAnnotatedClass(EventLogSequence.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", "jdbc:h2:mem:eventlog;DB_CLOSE_DELAY=-1;MODE=MSSQLServer")
				.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static String jpql(String method) {
		for (Method m : EventLogSequenceRepository.class.getMethods()) {
			if (m.getName().equals(method)) {
				return m.getAnnotation(Query.class).value();
			}
		}
		throw new IllegalStateException(method);
	}

	private static int increment(Session session, int importance) {
		return session.createQuery(jpql("increment")).setParameter("importance", importance).executeUpdate();
	}

	private static List<?> lastValue(Session session, int importance) {
		return session.createQuery(jpql("lastValue")).setParameter("importance", importance).getResultList();
	}

	private static EventLogEntry entry(String uuid, long number) {
		EventLogEntry entry = new EventLogEntry();
		entry.setEventUuid(uuid);
		entry.setImportance(EventImportance.STEP);
		entry.setSequenceNo(number);
		entry.setCode(EventCode.SALE_VALIDATED.name());
		entry.setSubjectType(EventSubject.TICKET.name());
		entry.setSubjectLabel("Ticket n° 12 — قسيمة"); // nvarchar: accents and Arabic kept
		entry.setEventAt(LocalDateTime.of(2026, 11, 23, 10, 0));
		return entry;
	}

	@Test
	@DisplayName("The number row: no row, 0 updated; created, then +1 at each increment, one importance at a time")
	void numberRow() {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			assertEquals(0, increment(session, EventImportance.NOTABLE));
			assertTrue(lastValue(session, EventImportance.NOTABLE).isEmpty());
			session.persist(new EventLogSequence(EventImportance.NOTABLE, 1));
			session.persist(new EventLogSequence(EventImportance.ALERT, 1));
			session.flush();
			assertEquals(1, increment(session, EventImportance.NOTABLE));
			assertEquals(1, increment(session, EventImportance.NOTABLE));
			assertEquals(3L, lastValue(session, EventImportance.NOTABLE).get(0));
			assertEquals(1L, lastValue(session, EventImportance.ALERT).get(0)); // another importance, untouched
			session.getTransaction().rollback();
		}
	}

	@Test
	@DisplayName("One number row per importance: a second row for the same importance is refused")
	void oneRowPerImportance() {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			session.persist(new EventLogSequence(EventImportance.STEP, 1));
			// IDENTITY: the insert runs at persist
			assertThrows(PersistenceException.class, () -> session.persist(new EventLogSequence(EventImportance.STEP, 1)));
			session.getTransaction().rollback();
		}
	}

	@Test
	@DisplayName("The same event UUID twice is refused (a till's event sent again is stored once)")
	void uuidOnce() {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			session.persist(entry("a0000000-0000-4000-8000-000000000001", 1));
			assertThrows(PersistenceException.class,
					() -> session.persist(entry("a0000000-0000-4000-8000-000000000001", 2)));
			session.getTransaction().rollback();
		}
	}

	@Test
	@DisplayName("A row is never updated: a change through JPA is not written; imported false when not said")
	void neverUpdated() {
		Long id;
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			EventLogEntry entry = entry("b0000000-0000-4000-8000-000000000002", 1);
			session.persist(entry);
			session.getTransaction().commit();
			id = entry.getId();
		}
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			EventLogEntry entry = session.get(EventLogEntry.class, id);
			entry.setDetails("changed");
			entry.setAmount(99.0);
			session.getTransaction().commit();
		}
		try (Session session = sessions.openSession()) {
			EventLogEntry entry = session.get(EventLogEntry.class, id);
			assertEquals(null, entry.getDetails());
			assertEquals(null, entry.getAmount());
			assertFalse(entry.isImported());
			assertEquals("Ticket n° 12 — قسيمة", entry.getSubjectLabel());
		}
	}

	@Test
	@DisplayName("Step 4: reserve adds a group's count at once; storedUuids finds the UUIDs stored; the batch SQL writes"
			+ " every column, nulls included, as Hibernate reads them (install_id, doubt)")
	void batch() throws Exception {
		try (Session session = sessions.openSession()) {
			session.beginTransaction();
			session.persist(new EventLogSequence(EventImportance.NOTABLE, 7));
			session.flush();
			assertEquals(1, session.createQuery(jpql("reserve")).setParameter("importance", EventImportance.NOTABLE)
					.setParameter("count", 3L).executeUpdate());
			assertEquals(10L, lastValue(session, EventImportance.NOTABLE).get(0));
			assertEquals(0, session.createQuery(jpql("reserve")).setParameter("importance", EventImportance.ALERT)
					.setParameter("count", 3L).executeUpdate(), "no row: 0");

			EventLogEntry full = entry("c0000000-0000-4000-8000-000000000003", 8);
			full.setImportance(EventImportance.NOTABLE);
			full.setCode(EventCode.CART_LINE_DELETED.name());
			full.setSubjectType(EventSubject.CART.name());
			full.setSubjectId("12");
			full.setFrontAt(LocalDateTime.of(2026, 11, 23, 9, 59, 58));
			full.setPosMachineId(1L);
			full.setPosMachineName("Caisse 1");
			full.setSessionId(3L);
			full.setSessionNumber("SESSION261123001");
			full.setCartId("d0000000-0000-4000-8000-000000000004");
			full.setUserId(7L);
			full.setUserLogin("amine");
			full.setUserName("Amine K.");
			full.setUserRole("POS_USER");
			full.setSentById(2L);
			full.setSentByLogin("sarra");
			full.setClientAddress("192.168.1.21");
			full.setMachineSequenceNo(41L);
			full.setInstallId("e0000000-0000-4000-8000-000000000005");
			full.setDoubt("SESSION_OF_ANOTHER_USER");
			full.setOldValue("2");
			full.setNewValue("0");
			full.setAmount(12.5);
			full.setDetails("Ligne supprimée — سطر");
			EventLogEntry bare = entry("c0000000-0000-4000-8000-000000000006", 9);
			session.doWork(connection -> {
				try (java.sql.PreparedStatement ps = connection.prepareStatement(
						com.digithink.zsretail.core.eventlog.service.EventLogBatchInsert.SQL)) {
					for (EventLogEntry row : java.util.Arrays.asList(full, bare)) {
						com.digithink.zsretail.core.eventlog.service.EventLogBatchInsert.bind(ps, row);
						ps.addBatch();
					}
					assertEquals(2, ps.executeBatch().length);
				}
			});
			String storedQuery = null;
			for (Method m : com.digithink.zsretail.core.eventlog.repository.EventLogEntryRepository.class.getMethods()) {
				if (m.getName().equals("storedUuids")) {
					storedQuery = m.getAnnotation(Query.class).value();
				}
			}
			assertEquals(java.util.Arrays.asList("c0000000-0000-4000-8000-000000000003"),
					session.createQuery(storedQuery).setParameter("uuids", java.util.Arrays.asList(
							"c0000000-0000-4000-8000-000000000003", "f0000000-0000-4000-8000-000000000009"))
							.getResultList());
			EventLogEntry read = session.createQuery("from EventLogEntry where eventUuid = :u", EventLogEntry.class)
					.setParameter("u", full.getEventUuid()).getSingleResult();
			session.refresh(read);
			assertEquals("e0000000-0000-4000-8000-000000000005", read.getInstallId());
			assertEquals("SESSION_OF_ANOTHER_USER", read.getDoubt());
			assertEquals(Long.valueOf(41), read.getMachineSequenceNo());
			assertEquals(Long.valueOf(8), read.getSequenceNo());
			assertEquals(LocalDateTime.of(2026, 11, 23, 9, 59, 58), read.getFrontAt());
			assertEquals("Ligne supprimée — سطر", read.getDetails());
			assertEquals(12.5, read.getAmount());
			assertEquals("sarra", read.getSentByLogin());
			EventLogEntry readBare = session.createQuery("from EventLogEntry where eventUuid = :u", EventLogEntry.class)
					.setParameter("u", bare.getEventUuid()).getSingleResult();
			assertEquals(null, readBare.getInstallId());
			assertEquals(null, readBare.getAmount());
			assertFalse(readBare.isImported());
			session.getTransaction().rollback();
		}
	}
}
