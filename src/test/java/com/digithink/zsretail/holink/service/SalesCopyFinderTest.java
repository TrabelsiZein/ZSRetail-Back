package com.digithink.zsretail.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.holink.dto.SalesDocumentRef;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.model.SalesCopy;
import com.digithink.zsretail.holink.model.SalesCopyCursor;
import com.digithink.zsretail.holink.repository.SalesCopyCursorRepository;
import com.digithink.zsretail.holink.repository.SalesCopyRepository;
import com.digithink.zsretail.holink.service.SalesCopyFinder.Discovery;
import com.digithink.zsretail.model.enumeration.SessionStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;

/**
 * Head office plan, task 2.1: the query that finds the documents to copy to the head office. A new finished document
 * is found; a document changed after it was sent is found again; an unfinished one is not; from-date is respected; a
 * cycle with nothing new reads nothing. Plain JUnit: the store's documents are an in-memory list read with the same
 * rules as the JPQL query, the two hol_ tables are in-memory maps, no Spring context.
 */
class SalesCopyFinderTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 12, 0);

	/** The store's documents, as the query sees them. */
	private final List<Doc> documents = new ArrayList<>();

	/** hol_sales_copy and hol_sales_cursor. */
	private final Map<Long, SalesCopy> copyTable = new LinkedHashMap<>();
	private final Map<SalesCopyType, SalesCopyCursor> cursorTable = new EnumMap<>(SalesCopyType.class);

	private final List<String> repositoryCalls = new ArrayList<>();
	private final Map<SalesCopyType, LocalDateTime> fromAsked = new EnumMap<>(SalesCopyType.class);
	private int rowsRead;
	private SalesCopyType failingType;
	private String fromDate = "";

	@BeforeEach
	void setUp() {
		copyTable.clear();
		cursorTable.clear();
	}

	private SalesCopyFinder finder() {
		SalesDocumentSource source = new SalesDocumentSource() {
			@Override
			public List<SalesDocumentRef> findChanged(SalesCopyType type, LocalDateTime from,
					LocalDateTime afterChangedAt, long afterId, LocalDateTime until, int limit) {
				if (type == failingType) {
					throw new IllegalStateException("The query has timed out.");
				}
				fromAsked.put(type, from);
				List<SalesDocumentRef> page = documents.stream()
						.filter(d -> d.type == type)
						.filter(d -> d.date != null && !d.date.isBefore(from))
						.filter(d -> !d.changedAt().isAfter(until))
						.filter(d -> d.changedAt().isAfter(afterChangedAt)
								|| d.changedAt().isEqual(afterChangedAt) && d.id > afterId)
						.sorted(Comparator.comparing(Doc::changedAt).thenComparing(d -> d.id))
						.limit(limit)
						.map(Doc::ref)
						.collect(Collectors.toList());
				rowsRead += page.size();
				return page;
			}

			@Override
			public Object loadCopy(SalesCopyType type, Long localId) {
				throw new UnsupportedOperationException("not used by the search");
			}
		};
		SalesCopyRepository copies = stub(SalesCopyRepository.class, (method, args) -> {
			repositoryCalls.add(method);
			switch (method) {
				case "findByDocumentTypeAndLocalIdIn":
					Collection<?> ids = (Collection<?>) args[1];
					return copyTable.values().stream()
							.filter(c -> c.getDocumentType() == args[0] && ids.contains(c.getLocalId()))
							.collect(Collectors.toList());
				case "saveAll":
					List<SalesCopy> saved = new ArrayList<>();
					for (Object o : (Iterable<?>) args[0]) {
						SalesCopy copy = (SalesCopy) o;
						if (copy.getId() == null) {
							copy.setId((long) copyTable.size() + 1);
						}
						copyTable.put(copy.getId(), copy);
						saved.add(copy);
					}
					return saved;
				default:
					return UNHANDLED;
			}
		});
		SalesCopyCursorRepository cursors = stub(SalesCopyCursorRepository.class, (method, args) -> {
			switch (method) {
				case "findByDocumentType":
					return Optional.ofNullable(cursorTable.get(args[0]));
				case "save":
					SalesCopyCursor cursor = (SalesCopyCursor) args[0];
					cursorTable.put(cursor.getDocumentType(), cursor);
					return cursor;
				default:
					return UNHANDLED;
			}
		});
		return new SalesCopyFinder(source, copies, cursors, new SalesPushSettings(fromDate, 50, 60),
				TransactionOperations.withoutTransaction());
	}

	// --- Documents ---

	private static final class Doc {
		final SalesCopyType type;
		final long id;
		final String number;
		LocalDateTime date;
		String status;
		LocalDateTime updatedAt;

		Doc(SalesCopyType type, long id, String number, LocalDateTime date, String status, LocalDateTime updatedAt) {
			this.type = type;
			this.id = id;
			this.number = number;
			this.date = date;
			this.status = status;
			this.updatedAt = updatedAt;
		}

		LocalDateTime changedAt() {
			return updatedAt != null ? updatedAt : date;
		}

		SalesDocumentRef ref() {
			return new SalesDocumentRef(type, id, number, date, status, changedAt());
		}
	}

	private Doc add(SalesCopyType type, long id, LocalDateTime date, Enum<?> status, LocalDateTime updatedAt) {
		Doc doc = new Doc(type, id, type.name().charAt(0) + "-" + id, date, status.name(), updatedAt);
		documents.add(doc);
		return doc;
	}

	private Doc ticket(long id, TransactionStatus status, LocalDateTime updatedAt) {
		return add(SalesCopyType.TICKET, id, updatedAt.minusMinutes(1), status, updatedAt);
	}

	private SalesCopy row(SalesCopyType type, long localId) {
		return copyTable.values().stream().filter(c -> c.getDocumentType() == type && c.getLocalId() == localId)
				.findFirst().orElse(null);
	}

	private int rows(SalesCopyType type) {
		return (int) copyTable.values().stream().filter(c -> c.getDocumentType() == type).count();
	}

	/** The row as the push would leave it after an accepted push. */
	private static void markSent(SalesCopy copy) {
		copy.setStatus(SalesCopyStatus.SENT);
		copy.setAttempts(1);
		copy.setContentHash("aa11");
		copy.setLastPushDate(NOW);
	}

	// --- Finished statuses ---

	@Test
	@DisplayName("Finished: ticket COMPLETED or REFUNDED, return COMPLETED, session CLOSED or TERMINATED; names exist in the enums")
	void finishedStatuses() {
		assertEquals(new LinkedHashSet<>(Arrays.asList("COMPLETED", "REFUNDED")),
				SalesCopyType.TICKET.getFinishedStatuses());
		assertEquals(new LinkedHashSet<>(Arrays.asList("COMPLETED")), SalesCopyType.RETURN.getFinishedStatuses());
		assertEquals(new LinkedHashSet<>(Arrays.asList("CLOSED", "TERMINATED")),
				SalesCopyType.SESSION.getFinishedStatuses());
		for (TransactionStatus s : TransactionStatus.values()) {
			assertEquals(s == TransactionStatus.COMPLETED || s == TransactionStatus.REFUNDED,
					SalesCopyType.TICKET.isFinished(s.name()), s.name());
		}
		for (SessionStatus s : SessionStatus.values()) {
			assertEquals(s != SessionStatus.OPENED, SalesCopyType.SESSION.isFinished(s.name()), s.name());
		}
		assertFalse(SalesCopyType.TICKET.isFinished(null));
	}

	// --- L1 of the task ---

	@Test
	@DisplayName("A new finished ticket is found: one PENDING row with its number and date, no attempt yet")
	void newFinishedTicketFound() {
		Doc doc = ticket(7, TransactionStatus.COMPLETED, NOW.minusMinutes(5));

		Discovery discovery = finder().discover(NOW);

		assertEquals(1, discovery.getFound());
		assertEquals(0, discovery.getChanged());
		assertNull(discovery.getFailure());
		SalesCopy row = row(SalesCopyType.TICKET, 7);
		assertNotNull(row);
		assertEquals(SalesCopyStatus.PENDING, row.getStatus());
		assertEquals("T-7", row.getDocumentNumber());
		assertEquals(doc.date, row.getDocumentDate());
		assertEquals(0, row.getAttempts());
		assertNull(row.getLastError());
		assertNull(row.getContentHash());
		assertNull(row.getLastPushDate());
	}

	@Test
	@DisplayName("A ticket changed after it was sent is found again: PENDING, attempts and error reset, accepted hash kept")
	void changedAfterSendingFoundAgain() {
		Doc doc = ticket(1, TransactionStatus.COMPLETED, NOW.minusMinutes(5));
		SalesCopyFinder finder = finder();
		finder.discover(NOW);
		markSent(row(SalesCopyType.TICKET, 1));

		finder.discover(NOW.plusMinutes(1));
		assertEquals(SalesCopyStatus.SENT, row(SalesCopyType.TICKET, 1).getStatus(), "no change, no new push");

		doc.updatedAt = NOW.plusMinutes(2); // e.g. its payment method changed
		Discovery discovery = finder.discover(NOW.plusMinutes(3));

		assertEquals(0, discovery.getFound());
		assertEquals(1, discovery.getChanged());
		SalesCopy row = row(SalesCopyType.TICKET, 1);
		assertEquals(SalesCopyStatus.PENDING, row.getStatus());
		assertEquals(0, row.getAttempts());
		assertNull(row.getLastError());
		assertEquals("aa11", row.getContentHash(), "kept: the push skips a copy equal to the one accepted");
		assertEquals(1, rows(SalesCopyType.TICKET), "the same row, not a second one");
	}

	@Test
	@DisplayName("An unfinished ticket is not found (parked, cancelled while parked); once completed it is")
	void unfinishedNotFound() {
		Doc parked = ticket(1, TransactionStatus.PENDING, NOW.minusMinutes(5));
		ticket(2, TransactionStatus.CANCELLED, NOW.minusMinutes(4));
		SalesCopyFinder finder = finder();

		Discovery discovery = finder.discover(NOW);
		assertEquals(0, discovery.getFound());
		assertEquals(0, rows(SalesCopyType.TICKET));

		parked.status = TransactionStatus.COMPLETED.name();
		parked.updatedAt = NOW.plusMinutes(1);
		finder.discover(NOW.plusMinutes(2));
		assertEquals(SalesCopyStatus.PENDING, row(SalesCopyType.TICKET, 1).getStatus());
		assertNull(row(SalesCopyType.TICKET, 2));
	}

	@Test
	@DisplayName("from-date: older documents are ignored, the day itself is included; the query gets the start of the day")
	void fromDateRespected() {
		add(SalesCopyType.TICKET, 1, LocalDateTime.of(2026, 8, 31, 23, 59, 59), TransactionStatus.COMPLETED,
				NOW.minusMinutes(5));
		add(SalesCopyType.TICKET, 2, LocalDateTime.of(2026, 9, 1, 0, 0), TransactionStatus.COMPLETED,
				NOW.minusMinutes(5));
		add(SalesCopyType.SESSION, 3, LocalDateTime.of(2026, 8, 31, 22, 0), SessionStatus.TERMINATED,
				NOW.minusMinutes(5));
		fromDate = " 2026-09-01 ";

		finder().discover(NOW);

		assertNull(row(SalesCopyType.TICKET, 1));
		assertNotNull(row(SalesCopyType.TICKET, 2));
		assertNull(row(SalesCopyType.SESSION, 3), "a session closed before the date is ignored too");
		for (SalesCopyType type : SalesCopyType.values()) {
			assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), fromAsked.get(type), type.name());
		}
	}

	@Test
	@DisplayName("Without from-date the whole history is read")
	void noFromDate() {
		add(SalesCopyType.TICKET, 1, LocalDateTime.of(2020, 1, 2, 10, 0), TransactionStatus.COMPLETED,
				LocalDateTime.of(2020, 1, 2, 10, 0));

		finder().discover(NOW);

		assertNotNull(row(SalesCopyType.TICKET, 1));
		assertEquals(SalesCopyFinder.BEGINNING, fromAsked.get(SalesCopyType.TICKET));
	}

	@Test
	@DisplayName("A cycle with nothing new reads no document and writes no row: the query starts after the cursor")
	void nothingNewReadsNothing() {
		ticket(1, TransactionStatus.COMPLETED, NOW.minusMinutes(9));
		ticket(2, TransactionStatus.COMPLETED, NOW.minusMinutes(8));
		add(SalesCopyType.RETURN, 3, NOW.minusMinutes(7), TransactionStatus.COMPLETED, NOW.minusMinutes(7));
		add(SalesCopyType.SESSION, 4, NOW.minusMinutes(6), SessionStatus.CLOSED, NOW.minusMinutes(6));
		SalesCopyFinder finder = finder();
		finder.discover(NOW);
		assertEquals(4, rowsRead);
		assertEquals(NOW.minusMinutes(8), cursorTable.get(SalesCopyType.TICKET).getLastChangedAt());
		assertEquals(2L, cursorTable.get(SalesCopyType.TICKET).getLastLocalId());

		rowsRead = 0;
		repositoryCalls.clear();
		Discovery discovery = finder.discover(NOW.plusHours(1));

		assertEquals(0, rowsRead);
		assertEquals(0, discovery.getFound() + discovery.getChanged());
		assertFalse(discovery.isMore());
		assertTrue(repositoryCalls.isEmpty(), "no tracking row read or written: " + repositoryCalls);
	}

	@Test
	@DisplayName("A change younger than 30 s waits for a later cycle (exactly 30 s is read)")
	void settleDelay() {
		ticket(1, TransactionStatus.COMPLETED, NOW.minusSeconds(10));
		ticket(2, TransactionStatus.COMPLETED, NOW.minusSeconds(30));
		SalesCopyFinder finder = finder();

		finder.discover(NOW);
		assertNull(row(SalesCopyType.TICKET, 1));
		assertNotNull(row(SalesCopyType.TICKET, 2));

		finder.discover(NOW.plusSeconds(20));
		assertNotNull(row(SalesCopyType.TICKET, 1));
	}

	@Test
	@DisplayName("A sent ticket cancelled later is found again, although CANCELLED is not a finished status")
	void sentThenCancelled() {
		Doc doc = ticket(1, TransactionStatus.COMPLETED, NOW.minusMinutes(5));
		SalesCopyFinder finder = finder();
		finder.discover(NOW);
		markSent(row(SalesCopyType.TICKET, 1));

		doc.status = TransactionStatus.CANCELLED.name();
		doc.updatedAt = NOW.plusMinutes(1);
		finder.discover(NOW.plusMinutes(2));

		assertEquals(SalesCopyStatus.PENDING, row(SalesCopyType.TICKET, 1).getStatus());
	}

	@Test
	@DisplayName("A rejected document that changes is PENDING again with attempts back to 0; a PENDING one is not rewritten")
	void errorAndPendingRows() {
		Doc rejected = ticket(1, TransactionStatus.COMPLETED, NOW.minusMinutes(5));
		Doc waiting = ticket(2, TransactionStatus.COMPLETED, NOW.minusMinutes(4));
		SalesCopyFinder finder = finder();
		finder.discover(NOW);
		SalesCopy error = row(SalesCopyType.TICKET, 1);
		error.setStatus(SalesCopyStatus.ERROR);
		error.setAttempts(3);
		error.setLastError("line 1: itemCode is required");

		rejected.updatedAt = NOW.plusMinutes(1);
		waiting.updatedAt = NOW.plusMinutes(1);
		repositoryCalls.clear();
		Discovery discovery = finder.discover(NOW.plusMinutes(2));

		assertEquals(1, discovery.getChanged());
		assertEquals(SalesCopyStatus.PENDING, error.getStatus());
		assertEquals(0, error.getAttempts());
		assertNull(error.getLastError());
		assertEquals(SalesCopyStatus.PENDING, row(SalesCopyType.TICKET, 2).getStatus());
		assertEquals(2, rows(SalesCopyType.TICKET));
	}

	@Test
	@DisplayName("A long history is read 500 per type and per cycle, oldest change first; equal change times are not skipped")
	void pages() {
		LocalDateTime sameTime = NOW.minusDays(1);
		for (long id = 1; id <= 1001; id++) {
			// documents 301 to 1001 share one change time, across both page boundaries
			ticket(id, TransactionStatus.COMPLETED, id <= 300 ? sameTime.minusHours(1) : sameTime);
		}
		SalesCopyFinder finder = finder();

		Discovery first = finder.discover(NOW);
		assertTrue(first.isMore());
		assertEquals(500, rows(SalesCopyType.TICKET));
		assertNotNull(row(SalesCopyType.TICKET, 500));
		assertNull(row(SalesCopyType.TICKET, 501));

		assertTrue(finder.discover(NOW).isMore());
		Discovery last = finder.discover(NOW);
		assertFalse(last.isMore());
		assertEquals(1001, rows(SalesCopyType.TICKET));
		assertEquals(1001, rowsRead, "each document read once");
	}

	@Test
	@DisplayName("Returns: COMPLETED found. Sessions: CLOSED and TERMINATED found, OPENED not (no closing date yet)")
	void returnsAndSessions() {
		add(SalesCopyType.RETURN, 1, NOW.minusHours(1), TransactionStatus.COMPLETED, NOW.minusHours(1));
		add(SalesCopyType.SESSION, 2, NOW.minusHours(2), SessionStatus.CLOSED, NOW.minusHours(2));
		add(SalesCopyType.SESSION, 3, NOW.minusHours(3), SessionStatus.TERMINATED, NOW.minusHours(3));
		Doc open = add(SalesCopyType.SESSION, 4, null, SessionStatus.OPENED, NOW.minusHours(4));

		Discovery discovery = finder().discover(NOW);

		assertEquals(3, discovery.getFound());
		assertNotNull(row(SalesCopyType.RETURN, 1));
		assertNotNull(row(SalesCopyType.SESSION, 2));
		assertNotNull(row(SalesCopyType.SESSION, 3));
		assertNull(row(SalesCopyType.SESSION, 4));
		assertNull(open.date);
	}

	@Test
	@DisplayName("A type whose search fails is reported; the other types are searched and its cursor does not move")
	void failingTypeDoesNotStopOthers() {
		ticket(1, TransactionStatus.COMPLETED, NOW.minusMinutes(5));
		add(SalesCopyType.RETURN, 2, NOW.minusMinutes(5), TransactionStatus.COMPLETED, NOW.minusMinutes(5));
		add(SalesCopyType.SESSION, 3, NOW.minusMinutes(5), SessionStatus.CLOSED, NOW.minusMinutes(5));
		failingType = SalesCopyType.RETURN;

		Discovery discovery = finder().discover(NOW);

		assertEquals(2, discovery.getFound());
		assertEquals("RETURN: IllegalStateException: The query has timed out.", discovery.getFailure());
		assertNull(cursorTable.get(SalesCopyType.RETURN));

		failingType = null;
		assertEquals(1, finder().discover(NOW).getFound(), "found at the next cycle");
		assertNotNull(row(SalesCopyType.RETURN, 2));
	}

	// --- Stubs ---

	private interface Handler {
		Object handle(String method, Object[] args);
	}

	private static final Object UNHANDLED = new Object();

	@SuppressWarnings("unchecked")
	private static <T> T stub(Class<T> type, Handler handler) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			switch (method.getName()) {
				case "hashCode": return System.identityHashCode(proxy);
				case "equals": return proxy == args[0];
				case "toString": return type.getSimpleName() + "Stub";
				default: break;
			}
			Object result = handler.handle(method.getName(), args == null ? new Object[0] : args);
			if (result == UNHANDLED) {
				throw new UnsupportedOperationException("Unexpected call: " + type.getSimpleName() + "." + method.getName());
			}
			return result;
		});
	}
}
