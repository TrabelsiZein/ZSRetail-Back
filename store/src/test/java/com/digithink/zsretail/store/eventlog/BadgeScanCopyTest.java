package com.digithink.zsretail.store.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.model.EventLogSequence;

/**
 * 3.4.0, event log step 7: db/3.4.0/update.sql step 5, the badge scans of before copied into the Journal: the statement
 * between "step7:copy:begin" and "step7:copy:end", as written, run twice on H2 (SQL Server mode) over scans of each kind.
 * A badge used and a refused scan become TILL_RIGHT_USED / TILL_RIGHT_REFUSED with today's texts, imported, without a
 * number; a role pass and a scan that already has its event are not copied; a second run copies nothing; the imported
 * rows are not missing numbers.
 */
class BadgeScanCopyTest {

	private static final String URL = "jdbc:h2:mem:badgescancopy;DB_CLOSE_DELAY=-1;MODE=MSSQLServer";
	private static final LocalDateTime T = LocalDateTime.of(2026, 11, 2, 19, 8, 20);
	private static SessionFactory sessions;
	private static JdbcTemplate jdbc;

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(EventLogEntry.class).addAnnotatedClass(EventLogSequence.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", URL).setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		DriverManagerDataSource source = new DriverManagerDataSource(URL);
		source.setDriverClassName("org.h2.Driver");
		jdbc = new JdbcTemplate(source);
		jdbc.execute("create table app_role (id bigint primary key, name varchar(100))");
		jdbc.execute("create table user_account (id bigint primary key, username varchar(100), full_name varchar(150),"
				+ " app_role_id bigint)");
		jdbc.execute("create table cashier_session (id bigint primary key, session_number varchar(50))");
		jdbc.execute("create table pos_machine (id bigint primary key, name varchar(100))");
		jdbc.execute("create table cashier_session_pos_machine (cashier_session_id bigint, pos_machine_id bigint)");
		jdbc.execute("create table badge_scan_log (id bigint primary key, scanned_by_user_id bigint, scanned_user_id bigint,"
				+ " functionality varchar(50), success boolean, failure_reason varchar(500), session_id bigint,"
				+ " [timestamp] timestamp, granted_by varchar(10), scan_type varchar(20), scanned_badge_code varchar(50))");
		jdbc.update("insert into app_role values (1, 'ADMIN'), (2, 'RESPONSIBLE'), (3, 'POS_USER')");
		jdbc.update("insert into user_account values (2, 'responsible', 'Responsible Manager', 2),"
				+ " (3, 'cashier', 'Cashier User', 3), (9, 'caissier_test', 'Caissier Test', 3), (10, 'amine', 'Amine K.', 3)");
		jdbc.update("insert into cashier_session values (50, 'SESSION261124002'), (51, 'SESSION261102001')");
		jdbc.update("insert into pos_machine values (1, 'Caisse 1')");
		jdbc.update("insert into cashier_session_pos_machine values (50, 1)");
		scan(1, 9, 2, "MAKE_RETURN", true, null, 50, T, "BADGE", "SCAN");
		scan(2, 9, 3, "MAKE_RETURN", false, "BADGE_NO_ACCESS", 50, T.plusSeconds(10), "BADGE", "SCAN");
		scan(3, 9, null, "MAKE_RETURN", false, "BADGE_NOT_EXISTS", 50, T.plusSeconds(20), "BADGE", "SCAN");
		scan(4, 9, 9, "APPLY_LINE_DISCOUNT", true, null, 50, T.plusSeconds(30), "ROLE", "ROLE"); // the user's own role
		scan(5, 2, 10, "CONSULT_CUSTOMER_LIST", true, null, 51, T.plusSeconds(40), null, "BARCODE"); // before step C
		scan(6, 9, 2, "MAKE_RETURN", false, "BADGE_REVOKED", 50, T.plusSeconds(50), "BADGE", "SCAN");
		// scan 6 already has its event (written since step 3), a second later: not copied
		jdbc.update("insert into event_log (event_uuid, importance, sequence_no, code, subject_type, subject_id, event_at,"
				+ " occurred_at, user_id, imported) values ('e-6', 30, 1, 'TILL_RIGHT_REFUSED', 'TILL_RIGHT', 'MAKE_RETURN', ?, ?,"
				+ " 9, 0)", Timestamp.valueOf(T.plusSeconds(51)), Timestamp.valueOf(T.plusSeconds(51)));
		jdbc.update("insert into event_log_sequence (importance, last_value) values (10, 0), (20, 0), (30, 1)");
	}

	@AfterAll
	static void close() {
		sessions.close();
	}

	private static void scan(long id, long by, Integer owner, String action, boolean success, String reason, long session,
			LocalDateTime at, String grantedBy, String type) {
		jdbc.update("insert into badge_scan_log (id, scanned_by_user_id, scanned_user_id, functionality, success,"
				+ " failure_reason, session_id, [timestamp], granted_by, scan_type, scanned_badge_code)"
				+ " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SECRET-CODE')", id, by, owner, action, success, reason, session,
				Timestamp.valueOf(at), grantedBy, type);
	}

	/** The script's copy statement, as written between its two markers. */
	static String copyStatement() throws Exception {
		try (InputStream in = BadgeScanCopyTest.class.getClassLoader().getResourceAsStream("db/3.4.0/update.sql");
				Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name()).useDelimiter("\\A")) {
			String script = scanner.next();
			int begin = script.indexOf("-- step7:copy:begin");
			int end = script.indexOf("-- step7:copy:end");
			assertTrue(begin > 0 && end > begin, "the markers");
			String statement = script.substring(script.indexOf('\n', begin) + 1, end).trim();
			return statement.endsWith(";") ? statement.substring(0, statement.length() - 1) : statement;
		}
	}

	private static Map<String, Object> row(String uuid) {
		return jdbc.queryForMap("select * from event_log where event_uuid = ?", uuid);
	}

	@Test
	@DisplayName("First run: the badges used and refused copied (imported, no number, today's texts), not the role pass"
			+ " nor the scan that has its event; second run: nothing; the imported rows are not missing numbers")
	void copiedOnce() throws Exception {
		String copy = copyStatement();
		assertEquals(4, jdbc.update(copy), "scans 1, 2, 3 and 5");
		assertEquals(0, jdbc.update(copy), "a second run copies nothing");
		assertEquals(5, jdbc.queryForObject("select count(*) from event_log", Integer.class));

		Map<String, Object> used = row("badge-scan-1");
		assertEquals("TILL_RIGHT_USED", used.get("CODE"));
		assertEquals(10, ((Number) used.get("IMPORTANCE")).intValue());
		assertNull(used.get("SEQUENCE_NO"));
		assertEquals(Boolean.TRUE, used.get("IMPORTED"));
		assertEquals("TILL_RIGHT", used.get("SUBJECT_TYPE"));
		assertEquals("MAKE_RETURN", used.get("SUBJECT_ID"));
		assertEquals("Effectuer un retour", used.get("SUBJECT_LABEL"));
		assertEquals("Droit « Effectuer un retour » ouvert par le badge de Responsible Manager, pour Caissier Test.",
				used.get("DETAILS"));
		assertEquals(9L, ((Number) used.get("USER_ID")).longValue());
		assertEquals("caissier_test", used.get("USER_LOGIN"));
		assertEquals("POS_USER", used.get("USER_ROLE"));
		assertEquals(2L, ((Number) used.get("AUTHORISED_BY_ID")).longValue());
		assertEquals(2L, ((Number) used.get("BADGE_OWNER_ID")).longValue());
		assertEquals("Responsible Manager", used.get("BADGE_OWNER_NAME"));
		assertEquals(1L, ((Number) used.get("POS_MACHINE_ID")).longValue());
		assertEquals("Caisse 1", used.get("POS_MACHINE_NAME"));
		assertEquals("SESSION261124002", used.get("SESSION_NUMBER"));
		assertEquals(Timestamp.valueOf(T), used.get("OCCURRED_AT"));
		assertEquals(Timestamp.valueOf(T), used.get("EVENT_AT"));
		assertNull(used.get("FRONT_AT"));

		Map<String, Object> noRight = row("badge-scan-2");
		assertEquals("TILL_RIGHT_REFUSED", noRight.get("CODE"));
		assertEquals(30, ((Number) noRight.get("IMPORTANCE")).intValue());
		assertEquals("BADGE_NO_ACCESS", noRight.get("NEW_VALUE"));
		assertEquals("Badge refusé au scan pour « Effectuer un retour » : le rôle de Cashier User n'a pas ce droit.",
				noRight.get("DETAILS"));
		assertEquals(3L, ((Number) noRight.get("BADGE_OWNER_ID")).longValue());
		assertNull(noRight.get("AUTHORISED_BY_ID"), "a refusal authorised nothing");

		Map<String, Object> unknown = row("badge-scan-3");
		assertEquals("Badge refusé au scan pour « Effectuer un retour » : badge inconnu.", unknown.get("DETAILS"));
		assertNull(unknown.get("BADGE_OWNER_ID"));

		Map<String, Object> before = row("badge-scan-5");
		assertEquals("TILL_RIGHT_USED", before.get("CODE"), "a scan of before step C (no granted_by): a badge");
		assertEquals("Droit « Consulter la liste des clients » ouvert par le badge de Amine K., pour Responsible Manager.",
				before.get("DETAILS"));
		assertNull(before.get("POS_MACHINE_ID"), "no POS machine known for its session");
		assertEquals("SESSION261102001", before.get("SESSION_NUMBER"));

		List<Map<String, Object>> noCode = jdbc.queryForList("select * from event_log where details like '%SECRET%'"
				+ " or old_value like '%SECRET%' or new_value like '%SECRET%'");
		assertTrue(noCode.isEmpty(), "no badge code written");

		List<EventLogReader.Missing> missing = new EventLogReader(jdbc).missing();
		missing.forEach(m -> assertEquals(0, m.getMissing(), "importance " + m.getImportance()));
	}
}
