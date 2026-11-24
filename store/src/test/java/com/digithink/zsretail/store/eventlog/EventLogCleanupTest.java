package com.digithink.zsretail.store.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.EventImportance;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.model.EventLogSequence;
import com.digithink.zsretail.core.eventlog.service.EventLogBatchInsert;
import com.digithink.zsretail.core.eventlog.service.EventLogSettings;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.support.InMemoryEventLog;

/**
 * 3.4.0, event log step 8: EventLogCleanup on H2 (SQL Server mode), the table made from the entities. What is older than
 * its duration goes, never the last 30 days; a numbered importance loses a run of its first numbers and records it
 * (cleaned_to): no missing number after; the cleanups and the settings' changes stay; a copied badge scan (no number) goes
 * by its date; a duration left empty keeps everything; one EVENTLOG_CLEANUP says who and how many.
 */
class EventLogCleanupTest {

	private static final String URL = "jdbc:h2:mem:eventlogcleanup;DB_CLOSE_DELAY=-1;MODE=MSSQLServer";
	private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
	private static SessionFactory sessions;
	private static JdbcTemplate jdbc;

	private final long[] numbers = new long[40];
	private final List<EventLogEntry> rows = new ArrayList<>();
	private final InMemoryEventLog log = new InMemoryEventLog();

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(EventLogEntry.class).addAnnotatedClass(EventLogSequence.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", URL).setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		DriverManagerDataSource source = new DriverManagerDataSource(URL);
		source.setDriverClassName("org.h2.Driver");
		jdbc = new JdbcTemplate(source);
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	@BeforeEach
	void empty() {
		jdbc.update("delete from event_log");
		jdbc.update("delete from event_log_sequence");
	}

	private EventLogEntry event(EventCode code, LocalDateTime at) {
		EventLogEntry e = new EventLogEntry();
		e.setEventUuid(UUID.randomUUID().toString());
		e.setCode(code.name());
		e.setImportance(code.getImportance());
		e.setSequenceNo(++numbers[code.getImportance() / 10]);
		e.setSubjectType(code.getSubject().name());
		e.setEventAt(at);
		rows.add(e);
		return e;
	}

	private void store() {
		new EventLogBatchInsert(jdbc).accept(rows);
		for (int importance : new int[] { 10, 20, 30 }) {
			jdbc.update("insert into event_log_sequence (importance, last_value, cleaned_to) values (?, ?, 0)", importance,
					numbers[importance / 10]);
		}
	}

	private static EventLogSettings keep(Integer step, Integer notable, Integer alert) {
		Map<Integer, Integer> months = new HashMap<>();
		months.put(EventImportance.STEP, step);
		months.put(EventImportance.NOTABLE, notable);
		months.put(EventImportance.ALERT, alert);
		return new EventLogSettings(EventImportance.STEP, months, false);
	}

	private EventLogCleanup cleanup(EventLogSettings settings) {
		return new EventLogCleanup(jdbc, log.eventLog(), settings, () -> TODAY, 0);
	}

	private static LocalDateTime daysAgo(int days) {
		return TODAY.atStartOfDay().minusDays(days).plusHours(10);
	}

	private long count(int importance) {
		return jdbc.queryForObject("select count(*) from event_log where importance = ?", Long.class, importance);
	}

	@Test
	@DisplayName("Steps kept 3 months: the older go, a run of the first numbers recorded; no missing number after;"
			+ " Notables and Alerts (24 months) all kept; one EVENTLOG_CLEANUP says who and how many")
	void durations() {
		for (int i = 0; i < 5; i++) {
			event(EventCode.CART_LINE_ADDED, daysAgo(200 - i)); // Steps 1-5: older than 3 months
		}
		for (int i = 0; i < 3; i++) {
			event(EventCode.CART_LINE_ADDED, daysAgo(40 - i)); // Steps 6-8: younger
		}
		event(EventCode.CART_LINE_DELETED, daysAgo(200)); // a Notable of 200 days: kept (24 months)
		event(EventCode.RECEIPT_REPRINTED, daysAgo(400)); // an Alert of 400 days: kept
		store();

		EventLogCleanup cleanup = cleanup(keep(3, 24, 24));
		List<EventLogCleanup.Part> preview = cleanup.preview();
		EventLogCleanup.Part steps = preview.get(2);
		assertEquals(EventImportance.STEP, steps.getImportance());
		assertEquals(LocalDate.of(2026, 8, 24), steps.getLimit());
		assertEquals(5, steps.getRows());
		assertEquals(5, steps.getCleanedTo());
		assertEquals(0, preview.get(0).getRows(), "the Alert of 400 days, 24 months: kept");

		UserAccount admin = new UserAccount();
		admin.setId(1L);
		admin.setUsername("admin");
		admin.setFullName("System Administrator");
		EventLogCleanup.Result result = cleanup.run(admin);
		assertEquals(5, result.getTotal());
		assertEquals(3, count(EventImportance.STEP));
		assertEquals(1, count(EventImportance.NOTABLE));
		assertEquals(1, count(EventImportance.ALERT));
		assertEquals(5L, jdbc.queryForObject("select cleaned_to from event_log_sequence where importance = 10", Long.class));
		new EventLogReader(jdbc).missing().forEach(m -> assertEquals(0, m.getMissing(), "importance " + m.getImportance()));

		EventLogEntry written = log.only("EVENTLOG_CLEANUP");
		assertEquals(EventImportance.ALERT, written.getImportance());
		assertEquals("admin", written.getUserLogin());
		assertEquals(Double.valueOf(5), written.getAmount());
		assertEquals("Journal nettoyé par System Administrator : 0 Alertes d'avant le 24/11/2024 ; 0 Notables d'avant"
				+ " le 24/11/2024 ; 5 Étapes d'avant le 24/08/2026.", written.getDetails());
	}

	@Test
	@DisplayName("The last 30 days are never deleted, whatever the durations: 1 month on 15 March is 28 days, the limit"
			+ " moves to 30 days back (a row of 29 days stays)")
	void floor() {
		LocalDate march15 = LocalDate.of(2027, 3, 15);
		event(EventCode.CART_LINE_ADDED, march15.minusDays(45).atTime(10, 0));
		event(EventCode.CART_LINE_ADDED, march15.minusDays(29).atTime(10, 0));
		event(EventCode.CART_LINE_ADDED, march15.minusDays(20).atTime(10, 0));
		store();
		EventLogCleanup cleanup = new EventLogCleanup(jdbc, log.eventLog(), keep(1, 1, 1), () -> march15, 0);
		EventLogCleanup.Part steps = cleanup.preview().get(2);
		assertTrue(steps.isFloored(), "15 February is 28 days back: the floor");
		assertEquals(march15.minusDays(30), steps.getLimit());
		cleanup.run(null);
		assertEquals(2, count(EventImportance.STEP), "45 days goes; 29 and 20 days stay");
		EventLogEntry night = log.only("EVENTLOG_CLEANUP_NIGHTLY");
		assertEquals(EventImportance.NOTABLE, night.getImportance(), "the night job: Notable (the button: Alert)");
		assertNull(night.getUserLogin(), "the night job: nobody");
		assertTrue(night.getDetails().startsWith("Journal nettoyé par la tâche de nuit"));
		assertTrue(night.getDetails().contains("(les 30 derniers jours gardés)"));
	}

	@Test
	@DisplayName("Never deleted: the cleanups and the settings' changes; a copied badge scan (no number) goes by its date;"
			+ " a duration left empty keeps everything")
	void keptAndImported() {
		event(EventCode.EVENTLOG_CLEANUP, daysAgo(900));
		event(EventCode.EVENTLOG_SETTINGS_CHANGED, daysAgo(900));
		event(EventCode.RECEIPT_REPRINTED, daysAgo(900));
		EventLogEntry oldScan = event(EventCode.TILL_RIGHT_REFUSED, daysAgo(900));
		oldScan.setSequenceNo(null);
		oldScan.setImported(true);
		numbers[3]--;
		EventLogEntry recentScan = event(EventCode.TILL_RIGHT_REFUSED, daysAgo(100));
		recentScan.setSequenceNo(null);
		recentScan.setImported(true);
		numbers[3]--;
		event(EventCode.CART_LINE_ADDED, daysAgo(900));
		store();
		cleanup(keep(null, 24, 24)).run(null);
		List<String> left = jdbc.queryForList("select code from event_log order by id", String.class);
		assertEquals(java.util.Arrays.asList("EVENTLOG_CLEANUP", "EVENTLOG_SETTINGS_CHANGED", "TILL_RIGHT_REFUSED",
				"CART_LINE_ADDED"), left, "the reprint and the old scan went; the Step stays (no duration)");
		assertEquals(3L, jdbc.queryForObject("select cleaned_to from event_log_sequence where importance = 30", Long.class));
		new EventLogReader(jdbc).missing().forEach(m -> assertEquals(0, m.getMissing(), "importance " + m.getImportance()));
	}

	@Test
	@DisplayName("A row deleted by hand above the cleaned numbers is still missing")
	void handDeletionStillSeen() {
		for (int i = 0; i < 6; i++) {
			event(EventCode.CART_LINE_ADDED, daysAgo(i < 3 ? 200 : 10));
		}
		store();
		cleanup(keep(3, 24, 24)).run(null);
		jdbc.update("delete from event_log where importance = 10 and sequence_no = 5");
		EventLogReader.Missing steps = new EventLogReader(jdbc).missing().get(2);
		assertEquals(3, steps.getCleanedTo());
		assertEquals(1, steps.getMissing());
		assertEquals(5L, steps.getRanges().get(0)[0]);
	}
}
