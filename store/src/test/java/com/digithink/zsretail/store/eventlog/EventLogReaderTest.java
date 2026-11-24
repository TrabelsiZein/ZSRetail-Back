package com.digithink.zsretail.store.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.model.EventLogSequence;
import com.digithink.zsretail.core.eventlog.service.EventLogBatchInsert;

/**
 * 3.4.0, event log step 6: the Journal page's reads (EventLogReader) on H2 in SQL Server mode, the table made by
 * Hibernate from the entity and the rows inserted by the real batch insert (its 37 columns). The order by the event's
 * own time, each filter, the runs of refused logins as one line, the counters, the missing numbers, the Excel file.
 */
class EventLogReaderTest {

	private static final String URL = "jdbc:h2:mem:eventlogpage;DB_CLOSE_DELAY=-1;MODE=MSSQLServer";
	private static SessionFactory sessions;
	private static JdbcTemplate jdbc;
	private static EventLogReader reader;
	private static final LocalDateTime T = LocalDateTime.of(2026, 11, 24, 9, 0, 0);

	private final List<EventLogEntry> rows = new ArrayList<>();
	private final long[] numbers = new long[40];

	@BeforeAll
	static void database() {
		sessions = new Configuration().addAnnotatedClass(EventLogEntry.class).addAnnotatedClass(EventLogSequence.class)
				.setProperty("hibernate.connection.driver_class", "org.h2.Driver")
				.setProperty("hibernate.connection.url", URL).setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.setProperty("hibernate.hbm2ddl.auto", "create-drop").buildSessionFactory();
		DriverManagerDataSource source = new DriverManagerDataSource(URL);
		source.setDriverClassName("org.h2.Driver");
		jdbc = new JdbcTemplate(source);
		reader = new EventLogReader(jdbc);
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

	// ─── Rows ────────────────────────────────────────────────────────

	private EventLogEntry event(EventCode code, LocalDateTime serverAt) {
		EventLogEntry e = new EventLogEntry();
		e.setEventUuid(UUID.randomUUID().toString());
		e.setCode(code.name());
		e.setImportance(code.getImportance());
		e.setSequenceNo(++numbers[code.getImportance() / 10]);
		e.setSubjectType(code.getSubject().name());
		e.setEventAt(serverAt);
		rows.add(e);
		return e;
	}

	private void store() {
		new EventLogBatchInsert(jdbc).accept(rows);
		for (int importance : new int[] { 10, 20, 30 }) {
			jdbc.update("insert into event_log_sequence (importance, last_value) values (?, ?)", importance,
					numbers[importance / 10]);
		}
	}

	private static EventLogFilter filter(String importances) {
		return filter(importances, null, null, null, null, null, null, null, null, null, null, null, false, null);
	}

	private static EventLogFilter filter(String importances, Long userId, Long authorisedById, Long badgeOwnerId,
			Long posMachineId, Long sessionId, String sessionNumber, String subjectType, String subjectId, String cartId,
			String codes, String text, boolean doubtOnly, String groupKey) {
		return EventLogFilter.of("2026-11-24", "2026-11-24", importances, userId, authorisedById, badgeOwnerId,
				posMachineId, sessionId, sessionNumber, subjectType, subjectId, cartId, codes, text, doubtOnly, groupKey);
	}

	private static List<String> codes(EventLogPage page) {
		return page.getRows().stream().map(EventLogRow::getCode).collect(Collectors.toList());
	}

	// ─── Tests ───────────────────────────────────────────────────────

	@Test
	@DisplayName("Ordered by the event's own time (the till's clock), newest first: a closing ticket sent late after the"
			+ " logout and the next login stays before them; the same second: the arrival order")
	void orderByOwnTime() {
		event(EventCode.SESSION_CLOSED, T.plusSeconds(41)); // the same second as the ticket, arrived first
		event(EventCode.LOGOUT, T.plusSeconds(43));
		event(EventCode.LOGIN_OK, T.plusSeconds(46));
		EventLogEntry ticket = event(EventCode.SESSION_TICKET_PRINTED, T.plusMinutes(4)); // arrived at 09:04
		ticket.setFrontAt(T.plusSeconds(41)); // printed at 09:00:41
		store();
		EventLogPage page = reader.page(filter(null), 0, 50);
		assertEquals(Arrays.asList("LOGIN_OK", "LOGOUT", "SESSION_TICKET_PRINTED", "SESSION_CLOSED"), codes(page));
		EventLogRow row = page.getRows().get(2);
		assertEquals(T.plusSeconds(41), row.getOccurredAt());
		assertEquals(T.plusMinutes(4), row.getEventAt());
		assertEquals(T.plusSeconds(41), row.getFrontAt());
	}

	@Test
	@DisplayName("The period runs on the event's own time, the last day included; a period is required, and to not"
			+ " before from")
	void period() {
		event(EventCode.LOGIN_OK, LocalDateTime.of(2026, 11, 23, 23, 59, 59));
		event(EventCode.LOGIN_OK, LocalDateTime.of(2026, 11, 24, 0, 0, 0));
		event(EventCode.LOGIN_OK, LocalDateTime.of(2026, 11, 24, 23, 59, 59));
		event(EventCode.LOGIN_OK, LocalDateTime.of(2026, 11, 25, 0, 0, 0));
		EventLogEntry late = event(EventCode.CART_LINE_ADDED, LocalDateTime.of(2026, 11, 25, 0, 5)); // arrived the 25th
		late.setFrontAt(LocalDateTime.of(2026, 11, 24, 23, 58)); // made the 24th
		store();
		assertEquals(3, reader.page(filter(null), 0, 50).getTotal());
		assertRefused(() -> EventLogFilter.of(null, "2026-11-24", null, null, null, null, null, null, null, null, null,
				null, null, null, false, null));
		assertRefused(() -> EventLogFilter.of("2026-11-25", "2026-11-24", null, null, null, null, null, null, null, null,
				null, null, null, null, false, null));
		assertRefused(() -> EventLogFilter.of("24/11/2026", "2026-11-24", null, null, null, null, null, null, null, null,
				null, null, null, null, false, null));
		assertRefused(() -> filter("ALERT"));
	}

	private static void assertRefused(Runnable call) {
		try {
			call.run();
		} catch (EventLogFilter.Refused expected) {
			return;
		}
		throw new AssertionError("not refused");
	}

	@Test
	@DisplayName("Each filter: importances, user, authorised by, badge owner, POS machine, session, subject, cart, codes,"
			+ " free text (the sentence, the subject's number), doubt only")
	void filters() {
		EventLogEntry sale = event(EventCode.SALE_VALIDATED, T.plusMinutes(1));
		sale.setUserId(7L);
		sale.setPosMachineId(1L);
		sale.setSessionId(50L);
		sale.setSessionNumber("SESSION261124002");
		sale.setSubjectId("812");
		sale.setSubjectLabel("FKAIROUAN261124007");
		sale.setCartId("c-1");
		sale.setDetails("Vente validée : ticket FKAIROUAN261124007.");
		EventLogEntry line = event(EventCode.CART_LINE_ADDED, T.plusMinutes(2));
		line.setUserId(7L);
		line.setPosMachineId(2L);
		line.setCartId("c-1");
		line.setDetails("Article ajouté par scan : CAP-SHA-DOC-0003 - DOCTEUR, quantité 1 (100%_[x]).");
		line.setDoubt("SESSION_UNKNOWN");
		EventLogEntry used = event(EventCode.TILL_RIGHT_USED, T.plusMinutes(3));
		used.setUserId(9L);
		used.setAuthorisedById(2L);
		used.setBadgeOwnerId(2L);
		EventLogEntry refused = event(EventCode.TILL_RIGHT_REFUSED, T.plusMinutes(4));
		refused.setUserId(9L);
		refused.setBadgeOwnerId(3L);
		store();

		assertEquals(Arrays.asList("TILL_RIGHT_REFUSED"), codes(reader.page(filter("30"), 0, 50)));
		assertEquals(Arrays.asList("TILL_RIGHT_USED", "CART_LINE_ADDED", "SALE_VALIDATED"),
				codes(reader.page(filter("10"), 0, 50)));
		assertEquals(4, reader.page(filter("30,10"), 0, 50).getTotal());
		assertEquals(Arrays.asList("CART_LINE_ADDED", "SALE_VALIDATED"),
				codes(reader.page(filter(null, 7L, null, null, null, null, null, null, null, null, null, null, false, null), 0, 50)));
		assertEquals(Arrays.asList("TILL_RIGHT_USED"),
				codes(reader.page(filter(null, null, 2L, null, null, null, null, null, null, null, null, null, false, null), 0, 50)));
		assertEquals(Arrays.asList("TILL_RIGHT_REFUSED"),
				codes(reader.page(filter(null, null, null, 3L, null, null, null, null, null, null, null, null, false, null), 0, 50)),
				"badges of user 3 used without the right");
		assertEquals(Arrays.asList("CART_LINE_ADDED"),
				codes(reader.page(filter(null, null, null, null, 2L, null, null, null, null, null, null, null, false, null), 0, 50)));
		assertEquals(Arrays.asList("SALE_VALIDATED"),
				codes(reader.page(filter(null, null, null, null, null, 50L, null, null, null, null, null, null, false, null), 0, 50)));
		assertEquals(Arrays.asList("SALE_VALIDATED"),
				codes(reader.page(filter(null, null, null, null, null, null, "SESSION261124002", null, null, null, null, null,
						false, null), 0, 50)));
		assertEquals(Arrays.asList("SALE_VALIDATED"),
				codes(reader.page(filter(null, null, null, null, null, null, null, "ticket", "812", null, null, null, false,
						null), 0, 50)), "a subject (the type in any case)");
		assertEquals(Arrays.asList("CART_LINE_ADDED", "SALE_VALIDATED"),
				codes(reader.page(filter(null, null, null, null, null, null, null, null, null, "c-1", null, null, false, null), 0, 50)),
				"the cart and the ticket it became");
		assertEquals(Arrays.asList("TILL_RIGHT_REFUSED", "TILL_RIGHT_USED"),
				codes(reader.page(filter(null, null, null, null, null, null, null, null, null, null,
						"till_right_used,TILL_RIGHT_REFUSED", null, false, null), 0, 50)));
		assertEquals(Arrays.asList("SALE_VALIDATED"),
				codes(reader.page(filter(null, null, null, null, null, null, null, null, null, null, null, "FKAIROUAN261124007",
						false, null), 0, 50)), "the ticket's number");
		assertEquals(Arrays.asList("CART_LINE_ADDED"),
				codes(reader.page(filter(null, null, null, null, null, null, null, null, null, null, null, "CAP-SHA-DOC-0003",
						false, null), 0, 50)), "an item's code in the sentence");
		assertEquals(Arrays.asList("CART_LINE_ADDED"),
				codes(reader.page(filter(null, null, null, null, null, null, null, null, null, null, null, "%_[x]", false,
						null), 0, 50)), "%, _ and [ as typed");
		assertEquals(0, reader.page(filter(null, null, null, null, null, null, null, null, null, null, null, "1%x", false,
				null), 0, 50).getTotal(), "no wildcard");
		assertEquals(Arrays.asList("CART_LINE_ADDED"),
				codes(reader.page(filter(null, null, null, null, null, null, null, null, null, null, null, null, true, null), 0, 50)));
	}

	@Test
	@DisplayName("A run of refused logins is one line (its last event, the count, the first and last times); its key"
			+ " gives its events; the counters count every event, whatever the importance filter")
	void runsAndCounters() {
		for (int i = 0; i < 6; i++) {
			EventLogEntry e = event(i < 4 ? EventCode.LOGIN_REFUSED : EventCode.LOGIN_REFUSED_REPEATED,
					T.plusSeconds(10L * i));
			e.setGroupKey("U9@2026-11-24T09:00");
			e.setUserId(9L);
		}
		EventLogEntry single = event(EventCode.LOGIN_REFUSED, T.plusMinutes(30));
		single.setGroupKey("A10.0.0.66@2026-11-24T09:30");
		event(EventCode.LOGIN_OK, T.plusMinutes(1));
		event(EventCode.SALE_VALIDATED, T.plusMinutes(2));
		store();

		EventLogPage page = reader.page(filter(null), 0, 50);
		assertEquals(Arrays.asList("LOGIN_REFUSED", "SALE_VALIDATED", "LOGIN_OK", "LOGIN_REFUSED_REPEATED"), codes(page));
		assertEquals(4, page.getTotal(), "a run counts once in the lines");
		EventLogRow run = page.getRows().get(3);
		assertEquals(6, run.getGroupCount());
		assertEquals(T, run.getGroupFirstAt());
		assertEquals(T.plusSeconds(50), run.getGroupLastAt());
		assertEquals(1, page.getRows().get(0).getGroupCount(), "a run of one is an ordinary line");
		assertEquals(2, page.getCounts().getAlerts());
		assertEquals(5, page.getCounts().getNotables());
		assertEquals(2, page.getCounts().getSteps());

		EventLogPage alerts = reader.page(filter("30"), 0, 50);
		assertEquals(1, alerts.getTotal());
		assertEquals(2, alerts.getRows().get(0).getGroupCount(), "the run within the filters");
		assertEquals(5, alerts.getCounts().getNotables(), "the counters leave the importance filter out");

		EventLogPage opened = reader.page(filter(null, null, null, null, null, null, null, null, null, null, null, null,
				false, "U9@2026-11-24T09:00"), 0, 50);
		assertEquals(6, opened.getTotal(), "the run's events, one per line");
		assertEquals("LOGIN_REFUSED_REPEATED", opened.getRows().get(0).getCode());
	}

	@Test
	@DisplayName("Paging on the server: 50 a page by default, the last page shorter, past the end empty; 200 at most")
	void paging() {
		for (int i = 0; i < 120; i++) {
			event(EventCode.CART_LINE_ADDED, T.plusSeconds(i));
		}
		store();
		EventLogPage first = reader.page(filter(null), 0, 0);
		assertEquals(50, first.getSize());
		assertEquals(50, first.getRows().size());
		assertEquals(120, first.getTotal());
		assertEquals(T.plusSeconds(119), first.getRows().get(0).getOccurredAt());
		EventLogPage third = reader.page(filter(null), 2, 50);
		assertEquals(20, third.getRows().size());
		assertEquals(T, third.getRows().get(19).getOccurredAt());
		assertTrue(reader.page(filter(null), 3, 50).getRows().isEmpty());
		assertEquals(200, reader.page(filter(null), 0, 5000).getSize());
	}

	@Test
	@DisplayName("The amount to 3 decimals (21.899999999999999 is 21.9); none stays none")
	void amountRounded() {
		event(EventCode.CART_LINE_ADDED, T).setAmount(21.899999999999999);
		event(EventCode.CART_LINE_ADDED, T.plusSeconds(1)).setAmount(8.0105);
		event(EventCode.LOGIN_OK, T.plusSeconds(2));
		store();
		List<EventLogRow> page = reader.page(filter(null), 0, 50).getRows();
		assertNull(page.get(0).getAmount());
		assertEquals(Double.valueOf(8.011), page.get(1).getAmount());
		assertEquals(Double.valueOf(21.9), page.get(2).getAmount());
	}

	@Test
	@DisplayName("Step 7: a count in the amount column (the refusals in a row, the events dropped) is a count, never an"
			+ " amount; several sessions; the new values (a cash movement's kind)")
	void countsSessionsValues() {
		event(EventCode.LOGIN_REFUSED_REPEATED, T).setAmount(8.0);
		event(EventCode.EVENTLOG_EVENTS_DROPPED, T.plusSeconds(1)).setAmount(3.0);
		EventLogEntry expense = event(EventCode.CASH_MOVEMENT, T.plusSeconds(2));
		expense.setAmount(12.5);
		expense.setNewValue("DEPENSE");
		expense.setSessionId(50L);
		EventLogEntry withdrawal = event(EventCode.CASH_MOVEMENT, T.plusSeconds(3));
		withdrawal.setNewValue("PRELEVEMENT");
		withdrawal.setSessionId(51L);
		event(EventCode.CASH_MOVEMENT, T.plusSeconds(4)).setSessionId(52L);
		store();
		List<EventLogRow> all = reader.page(filter(null), 0, 50).getRows();
		EventLogRow repeated = all.get(4);
		assertEquals("LOGIN_REFUSED_REPEATED", repeated.getCode());
		assertNull(repeated.getAmount());
		assertEquals(Long.valueOf(8), repeated.getCount());
		assertNull(all.get(3).getAmount(), "the events dropped: a count");
		assertEquals(Long.valueOf(3), all.get(3).getCount());
		assertEquals(Double.valueOf(12.5), all.get(2).getAmount(), "money stays money");
		assertNull(all.get(2).getCount());

		EventLogFilter two = EventLogFilter.of("2026-11-24", "2026-11-24", null, null, null, null, null, null, null, null,
				null, null, "CASH_MOVEMENT", null, false, null, "50,51", null);
		assertEquals(2, reader.page(two, 0, 50).getTotal(), "sessions 50 and 51");
		EventLogFilter expenses = EventLogFilter.of("2026-11-24", "2026-11-24", null, null, null, null, null, 52L, null,
				null, null, null, "CASH_MOVEMENT", null, false, null, "50,51", "depense");
		assertEquals(Arrays.asList("CASH_MOVEMENT"), codes(reader.page(expenses, 0, 50)));
		assertEquals("DEPENSE", reader.page(expenses, 0, 50).getRows().get(0).getNewValue());
		assertRefused(() -> EventLogFilter.of("2026-11-24", "2026-11-24", null, null, null, null, null, null, null, null,
				null, null, null, null, false, null, "50,x", null));
		StringBuilder many = new StringBuilder();
		for (int i = 1; i <= EventLogFilter.MAX_SESSIONS + 1; i++) {
			many.append(i).append(",");
		}
		assertRefused(() -> EventLogFilter.of("2026-11-24", "2026-11-24", null, null, null, null, null, null, null, null,
				null, null, null, null, false, null, many.toString(), null));
	}

	@Test
	@DisplayName("Step 8: one filter Badge de, the authoriser or the badge's owner (authorisedById of before is the same);"
			+ " the journal's first event, the copied badge scans aside")
	void badgeOfAndFirstEvent() {
		EventLogEntry old = event(EventCode.TILL_RIGHT_REFUSED, T.minusDays(30));
		old.setImported(true);
		old.setSequenceNo(null);
		EventLogEntry movement = event(EventCode.CASH_MOVEMENT, T.plusMinutes(1));
		movement.setAuthorisedById(4L);
		EventLogEntry refused = event(EventCode.TILL_RIGHT_REFUSED, T.plusMinutes(2));
		refused.setBadgeOwnerId(4L);
		event(EventCode.CART_CLEARED, T.plusMinutes(3));
		store();
		assertEquals(Arrays.asList("TILL_RIGHT_REFUSED", "CASH_MOVEMENT"),
				codes(reader.page(filter(null, null, null, 4L, null, null, null, null, null, null, null, null, false, null), 0, 50)));
		assertEquals(Arrays.asList("TILL_RIGHT_REFUSED", "CASH_MOVEMENT"),
				codes(reader.page(filter(null, null, 4L, null, null, null, null, null, null, null, null, null, false, null), 0, 50)),
				"an address of before (authorisedById)");
		assertEquals(T.plusMinutes(1), reader.page(filter(null), 0, 50).getFirstEventAt(), "the copied scan aside");
	}

	@Test
	@DisplayName("Missing numbers per importance: none; a row deleted in the middle and at the end; the counter higher"
			+ " than every row")
	void missingNumbers() {
		for (int i = 0; i < 6; i++) {
			event(EventCode.CART_LINE_ADDED, T.plusSeconds(i));
		}
		event(EventCode.LOGIN_REFUSED, T);
		event(EventCode.RECEIPT_REPRINTED, T);
		store();
		jdbc.update("delete from event_log where importance = 10 and sequence_no in (3, 4, 6)");
		jdbc.update("update event_log_sequence set last_value = 3 where importance = 30");
		List<EventLogReader.Missing> missing = reader.missing();
		EventLogReader.Missing alerts = missing.get(0);
		assertEquals(30, alerts.getImportance());
		assertEquals(2, alerts.getMissing());
		assertEquals("[2-3]", ranges(alerts));
		assertEquals(0, missing.get(1).getMissing(), "Notable whole");
		EventLogReader.Missing steps = missing.get(2);
		assertEquals(6, steps.getLastNumber());
		assertEquals(3, steps.getPresent());
		assertEquals(3, steps.getMissing());
		assertEquals("[3-4, 6-6]", ranges(steps));
	}

	private static String ranges(EventLogReader.Missing m) {
		return m.getRanges().stream().map(r -> r[0] + "-" + r[1]).collect(Collectors.toList()).toString();
	}

	@Test
	@DisplayName("The Excel file: every event of the filters (a run's events one by one), French headers, the amount to"
			+ " the millime, the doubt as a sentence; cut at the limit, said")
	void excel() throws Exception {
		EventLogEntry line = event(EventCode.CART_LINE_ADDED, T);
		line.setAmount(21.899999999999999);
		line.setUserLogin("amine");
		line.setUserName("Amine K.");
		line.setDoubt("SESSION_UNKNOWN,MACHINE_UNKNOWN");
		for (int i = 0; i < 3; i++) {
			event(EventCode.LOGIN_REFUSED, T.plusSeconds(10 + i)).setGroupKey("U9@x");
		}
		store();
		EventLogFilter all = filter(null);
		List<EventLogRow> events = reader.all(all, EventLogReader.EXCEL_LIMIT);
		assertEquals(4, events.size(), "a run's events one by one");
		byte[] file = EventLogExcel.of(all, events, 4);
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
			Sheet sheet = book.getSheet("Journal des événements");
			assertEquals("Journal des événements du 24/11/2026 au 24/11/2026", sheet.getRow(0).getCell(0).getStringCellValue());
			assertEquals("4 événements.", sheet.getRow(1).getCell(0).getStringCellValue());
			assertEquals("Date et heure", sheet.getRow(3).getCell(0).getStringCellValue());
			assertEquals("Action", sheet.getRow(3).getCell(2).getStringCellValue());
			org.apache.poi.ss.usermodel.Row last = sheet.getRow(7);
			assertEquals("Étape", last.getCell(1).getStringCellValue());
			assertEquals("Ligne ajoutée", last.getCell(2).getStringCellValue());
			assertEquals(21.9, last.getCell(4).getNumericCellValue(), 0.0);
			assertEquals("Amine K. (amine)", last.getCell(5).getStringCellValue());
			assertEquals("La session annoncée par la caisse n'existe pas. Le poste de caisse n'a pas été reconnu.",
					last.getCell(16).getStringCellValue());
		}
		byte[] cut = EventLogExcel.of(all, reader.all(all, 2), 4);
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(cut))) {
			assertTrue(book.getSheetAt(0).getRow(1).getCell(0).getStringCellValue().startsWith("2 premiers événements sur 4"));
		}
		assertEquals(4, reader.countAll(all));
	}
}
