package com.digithink.zsretail.core.eventlog;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.model.EventLogSequence;
import com.digithink.zsretail.core.eventlog.repository.EventLogEntryRepository;
import com.digithink.zsretail.core.eventlog.repository.EventLogSequenceRepository;
import com.digithink.zsretail.core.eventlog.service.EventLog;
import com.digithink.zsretail.core.eventlog.service.EventLogStart;
import com.digithink.zsretail.core.eventlog.service.EventLogWriter;
import com.digithink.zsretail.core.eventlog.service.NewEvent;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;

/**
 * 3.4.0, event log step 1: the helper (off: nothing; on: the catalogue's importance and subject, the server's clock and
 * UUID, the logged user, the texts cut), the numbering per importance (event_log_sequence), the caller never broken (a
 * failure is a WARN), and the caller's transaction (written after its commit, nothing after a rollback; writeNow at
 * once). The tables in memory, the repositories answering as their queries do (EventLogTableTest runs them on H2).
 */
class EventLogTest {

	static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 23, 10, 0);

	final List<EventLogEntry> rows = new ArrayList<>();
	final Map<Integer, Long> numbers = new LinkedHashMap<>();
	boolean on = true;
	RuntimeException saveFails;
	int sequenceRowRaces;
	UserAccount logged = user(7L, "cashier1", "Sana Ben Ali", "POS_USER");

	@AfterEach
	void noSynchronizationLeft() {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.clearSynchronization();
		}
		TransactionSynchronizationManager.setActualTransactionActive(false);
	}

	// ─── The catalogue ───────────────────────────────────────────────

	@Test
	@DisplayName("Catalogue: every code has a subject, one of the three importances and fits its column")
	void catalogue() {
		Set<Integer> named = new HashSet<>(Arrays.asList(EventImportance.STEP, EventImportance.NOTABLE, EventImportance.ALERT));
		for (EventCode code : EventCode.values()) {
			assertTrue(code.getSubject() != null, code.name());
			assertTrue(named.contains(code.getImportance()), code.name());
			assertTrue(code.name().length() <= EventCode.CODE_LENGTH, code.name());
		}
		assertEquals(EventImportance.ALERT, EventCode.RECEIPT_REPRINTED.getImportance()); // the drawer opens
		assertEquals("NOTABLE", EventImportance.nameOf(20));
		assertEquals("25", EventImportance.nameOf(25)); // a fourth importance, before it has a name
	}

	// ─── The switch ──────────────────────────────────────────────────

	@Test
	@DisplayName("Off (eventlog.enabled not true): nothing written, nothing numbered, not even the start")
	void offWritesNothing() {
		on = false;
		EventLog log = eventLog();
		log.write(NewEvent.of(EventCode.SALE_VALIDATED));
		log.writeNow(NewEvent.of(EventCode.TILL_RIGHT_REFUSED));
		new EventLogStart(log, "3.4.0").started();
		assertTrue(rows.isEmpty());
		assertTrue(numbers.isEmpty());
	}

	@Test
	@DisplayName("On: one row with the catalogue's importance and subject, the server's clock, a UUID, the logged user")
	void onWritesTheRow() {
		eventLog().write(NewEvent.of(EventCode.PAYMENT_CHANGED).subject(12L, "MAG261123001").change("CASH", "CARD")
				.amount(12.5).session(3L, "SESSION261123-1").machine(2L, "Caisse 1").details("Erreur de saisie"));
		assertEquals(1, rows.size());
		EventLogEntry row = rows.get(0);
		assertEquals("PAYMENT_CHANGED", row.getCode());
		assertEquals(EventImportance.ALERT, row.getImportance());
		assertEquals("TICKET", row.getSubjectType());
		assertEquals("12", row.getSubjectId());
		assertEquals("MAG261123001", row.getSubjectLabel());
		assertEquals("CASH", row.getOldValue());
		assertEquals("CARD", row.getNewValue());
		assertEquals(12.5, row.getAmount());
		assertEquals(NOW, row.getEventAt());
		assertNull(row.getFrontAt());
		assertEquals(36, row.getEventUuid().length());
		assertEquals(Long.valueOf(7), row.getUserId());
		assertEquals("cashier1", row.getUserLogin());
		assertEquals("Sana Ben Ali", row.getUserName());
		assertEquals("POS_USER", row.getUserRole());
		assertEquals(Long.valueOf(3), row.getSessionId());
		assertEquals("Caisse 1", row.getPosMachineName());
		assertNull(row.getAuthorisedById()); // nobody's badge
		assertEquals(false, row.isImported());
	}

	@Test
	@DisplayName("A badge's owner, a user named by the caller, a till's UUID and clock; long texts cut to their columns")
	void givenValues() {
		LocalDateTime tillClock = NOW.minusMinutes(3);
		String longText = String.join("", Collections.nCopies(300, "abcdefghij"));
		eventLog().write(NewEvent.of(EventCode.LINE_DISCOUNT).cart("c0ffee00-0000-4000-8000-000000000001")
				.user(user(9L, "cashier2", "Ali", "POS_USER"))
				.authorisedBy(user(4L, "resp", "Rim Responsable", "RESPONSIBLE"))
				.fromTill(tillClock, "f00d0000-0000-4000-8000-000000000002", 41L).details(longText).subject("1", longText));
		EventLogEntry row = rows.get(0);
		assertEquals("CART", row.getSubjectType());
		assertEquals("cashier2", row.getUserLogin()); // not the logged user
		assertEquals(Long.valueOf(4), row.getAuthorisedById());
		assertEquals("Rim Responsable", row.getAuthorisedByName());
		assertEquals("f00d0000-0000-4000-8000-000000000002", row.getEventUuid());
		assertEquals(tillClock, row.getFrontAt());
		assertEquals(NOW, row.getEventAt());
		assertEquals(Long.valueOf(41), row.getMachineSequenceNo());
		assertEquals(EventLogEntry.DETAILS_LENGTH, row.getDetails().length());
		assertEquals(EventLogEntry.LABEL_LENGTH, row.getSubjectLabel().length());
	}

	@Test
	@DisplayName("Nobody logged (a start, a job): the row without a user; a user lookup that fails changes nothing")
	void nobodyLogged() {
		logged = null;
		eventLog().writeNow(NewEvent.of(EventCode.EVENTLOG_STARTED));
		assertNull(rows.get(0).getUserId());
		EventLog failingLookup = new EventLog(() -> on, writer(), () -> {
			throw new IllegalStateException("no user");
		}, () -> NOW);
		failingLookup.writeNow(NewEvent.of(EventCode.EVENTLOG_STARTED));
		assertEquals(2, rows.size());
		assertNull(rows.get(1).getUserLogin());
	}

	@Test
	@DisplayName("The start writes one EVENTLOG_STARTED (Alert) naming the version")
	void startEvent() {
		new EventLogStart(eventLog(), "3.4.0").started();
		assertEquals(1, rows.size());
		assertEquals("EVENTLOG_STARTED", rows.get(0).getCode());
		assertEquals(EventImportance.ALERT, rows.get(0).getImportance());
		assertTrue(rows.get(0).getDetails().contains("3.4.0"));
	}

	// ─── The numbering ───────────────────────────────────────────────

	@Test
	@DisplayName("Numbers per importance: 1, 2, 3 in each, the first event of an importance creates its row")
	void numberedPerImportance() {
		EventLog log = eventLog();
		log.write(NewEvent.of(EventCode.CART_LINE_ADDED)); // STEP 1
		log.write(NewEvent.of(EventCode.TILL_RIGHT_REFUSED)); // ALERT 1
		log.write(NewEvent.of(EventCode.SALE_VALIDATED)); // STEP 2
		log.write(NewEvent.of(EventCode.CART_LINE_DELETED)); // NOTABLE 1
		log.write(NewEvent.of(EventCode.SESSION_OPENED)); // STEP 3
		log.write(NewEvent.of(EventCode.RECEIPT_REPRINTED)); // ALERT 2
		assertEquals(Arrays.asList(1L, 1L, 2L, 1L, 3L, 2L),
				rows.stream().map(EventLogEntry::getSequenceNo).collect(Collectors.toList()));
		assertEquals(Long.valueOf(3), numbers.get(EventImportance.STEP));
		assertEquals(Long.valueOf(1), numbers.get(EventImportance.NOTABLE));
		assertEquals(Long.valueOf(2), numbers.get(EventImportance.ALERT));
		assertEquals(6, rows.stream().map(EventLogEntry::getEventUuid).distinct().count());
	}

	@Test
	@DisplayName("Two first events of one importance at once: the second creates no second row, it takes number 2")
	void firstRowRace() {
		sequenceRowRaces = 1; // another writer creates the row between our increment and our insert
		eventLog().write(NewEvent.of(EventCode.SALE_VALIDATED));
		assertEquals(1, rows.size());
		assertEquals(Long.valueOf(2), rows.get(0).getSequenceNo());
	}

	// ─── The caller is never broken ──────────────────────────────────

	@Test
	@DisplayName("A write that fails (database down, a column refused) never reaches the caller: write and writeNow")
	void failureNeverThrows() {
		saveFails = new IllegalStateException("database down");
		EventLog log = eventLog();
		assertDoesNotThrow(() -> log.write(NewEvent.of(EventCode.SALE_VALIDATED)));
		assertDoesNotThrow(() -> log.writeNow(NewEvent.of(EventCode.TILL_RIGHT_REFUSED)));
		assertTrue(rows.isEmpty());
		saveFails = null;
		log.write(NewEvent.of(EventCode.SALE_VALIDATED));
		assertEquals(1, rows.size()); // the next one goes through
	}

	// ─── The caller's transaction ────────────────────────────────────

	@Test
	@DisplayName("In a transaction: write waits for its commit; after a rollback nothing; writeNow at once")
	void callersTransaction() {
		EventLog log = eventLog();
		inTransaction();
		log.write(NewEvent.of(EventCode.SALE_VALIDATED));
		assertTrue(rows.isEmpty()); // not before the commit
		log.writeNow(NewEvent.of(EventCode.TILL_RIGHT_REFUSED));
		assertEquals(Collections.singletonList("TILL_RIGHT_REFUSED"), codes()); // a refusal stays, whatever comes next
		commit();
		assertEquals(Arrays.asList("TILL_RIGHT_REFUSED", "SALE_VALIDATED"), codes());

		inTransaction();
		log.write(NewEvent.of(EventCode.RETURN_DONE));
		rollback();
		assertEquals(Arrays.asList("TILL_RIGHT_REFUSED", "SALE_VALIDATED"), codes()); // no return: it rolled back
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	EventLog eventLog() {
		return new EventLog(() -> on, writer(), () -> logged, () -> NOW);
	}

	EventLogWriter writer() {
		return new EventLogWriter(entries(), sequences(), TransactionOperations.withoutTransaction());
	}

	List<String> codes() {
		return rows.stream().map(EventLogEntry::getCode).collect(Collectors.toList());
	}

	static void inTransaction() {
		TransactionSynchronizationManager.initSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(true);
	}

	static void commit() {
		List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
		TransactionSynchronizationManager.clearSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(false);
		synchronizations.forEach(TransactionSynchronization::afterCommit);
		synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
	}

	static void rollback() {
		List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
		TransactionSynchronizationManager.clearSynchronization();
		TransactionSynchronizationManager.setActualTransactionActive(false);
		synchronizations.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
	}

	static UserAccount user(Long id, String login, String name, String role) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(login);
		user.setFullName(name);
		AppRole appRole = new AppRole();
		appRole.setName(role);
		user.setAppRole(appRole);
		return user;
	}

	/** event_log: save inserts (an id), or fails as told. */
	EventLogEntryRepository entries() {
		return stub(EventLogEntryRepository.class, (method, args) -> {
			if ("save".equals(method)) {
				if (saveFails != null) {
					throw saveFails;
				}
				EventLogEntry entry = (EventLogEntry) args[0];
				entry.setId((long) rows.size() + 1);
				rows.add(entry);
				return entry;
			}
			throw new UnsupportedOperationException(method);
		});
	}

	/** event_log_sequence: increment, lastValue and saveAndFlush as the queries and the unique key do. */
	EventLogSequenceRepository sequences() {
		return stub(EventLogSequenceRepository.class, (method, args) -> {
			switch (method) {
				case "increment": {
					Integer importance = (Integer) args[0];
					if (!numbers.containsKey(importance)) {
						return 0;
					}
					numbers.put(importance, numbers.get(importance) + 1);
					return 1;
				}
				case "lastValue":
					return Collections.singletonList(numbers.get((Integer) args[0]));
				case "saveAndFlush": {
					EventLogSequence row = (EventLogSequence) args[0];
					if (sequenceRowRaces > 0) {
						sequenceRowRaces--;
						numbers.put(row.getImportance(), 1L); // the other writer's row and its number 1
						throw new DataIntegrityViolationException("uk_event_log_sequence_importance");
					}
					if (numbers.containsKey(row.getImportance())) {
						throw new DataIntegrityViolationException("uk_event_log_sequence_importance");
					}
					numbers.put(row.getImportance(), row.getLastValue());
					return row;
				}
				default:
					throw new UnsupportedOperationException(method);
			}
		});
	}

	interface Answer {
		Object answer(String method, Object[] args);
	}

	@SuppressWarnings("unchecked")
	static <T> T stub(Class<T> type, Answer answer) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			if (method.getDeclaringClass() == Object.class) {
				switch (method.getName()) {
					case "hashCode":
						return System.identityHashCode(proxy);
					case "equals":
						return proxy == args[0];
					default:
						return type.getSimpleName();
				}
			}
			return answer.answer(method.getName(), args);
		});
	}
}
