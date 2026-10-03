package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.headoffice.model.HoDownSequence;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoDownChangeRepository;
import com.digithink.zsretail.headoffice.repository.HoDownSequenceRepository;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 3.1: the head office side of the copies down mechanism. The cursor is the head office's
 * change number, never a clock of the store; a change committed while a pull runs comes with the next pull; targets
 * (every store, a list, a store taken off), deletions, pages, a cursor after the last change, the startup backfill.
 * In-memory ho_down_change and ho_down_sequence that apply the rules of the JPQL queries; no Spring context.
 */
class CopiesDownFeedTest {

	private static final DataDomain D = DataDomain.PROMOTIONS;

	private final ObjectMapper mapper = new ObjectMapper();

	private final List<HoDownChange> changes = new ArrayList<>();
	private final Map<DataDomain, Long> sequences = new EnumMap<>(DataDomain.class);

	/** The head office records of the stub domain: code to targets; a code absent here is deleted. */
	private final Map<String, StoreTargets> records = new LinkedHashMap<>();

	/** Called inside load(), to simulate a change committed while the pull runs. */
	private Runnable duringLoad;

	private final Store a = store(1L, "RS01");
	private final Store b = store(2L, "RS02");

	private CopiesDownFeed feed;

	@BeforeEach
	void setUp() {
		changes.clear();
		sequences.clear();
		records.clear();
		duringLoad = null;
		feed = new CopiesDownFeed(changeRepository(), sequenceRepository(), Collections.singletonList(provider()),
				TransactionOperations.withoutTransaction(), TransactionOperations.withoutTransaction());
	}

	/** A head office change: the record saved with its targets, and its change recorded for old and new targets. */
	private void save(String code, StoreTargets targets) {
		StoreTargets old = records.get(code);
		records.put(code, targets);
		feed.recordChange(D, code, old == null ? targets : old.union(targets));
	}

	private void delete(String code) {
		StoreTargets old = records.remove(code);
		feed.recordChange(D, code, old);
	}

	private CopiesDownAnswerDTO pull(Store store, String cursor) {
		return feed.pull(store, "promotions", cursor, null);
	}

	private static List<String> codes(CopiesDownAnswerDTO answer) {
		return answer.getRecords().stream().map(r -> r.get("code").asText()).collect(Collectors.toList());
	}

	@Test
	@DisplayName("The cursor is the head office's change number: blank first, then sent back; a store clock is refused")
	void cursorFromHeadOffice() {
		save("P1", StoreTargets.all());
		save("P2", StoreTargets.all());
		CopiesDownAnswerDTO first = pull(a, "");
		assertEquals("PROMOTIONS", first.getDomain());
		assertEquals(Arrays.asList("P1", "P2"), codes(first));
		assertEquals("2", first.getCursor(), "the domain's last change number");
		assertFalse(first.isMore());

		CopiesDownAnswerDTO again = pull(a, first.getCursor());
		assertTrue(again.getRecords().isEmpty() && again.getRemoved().isEmpty(), "nothing new after the cursor");
		assertEquals("2", again.getCursor());

		save("P1", StoreTargets.all()); // edited
		CopiesDownAnswerDTO edited = pull(a, again.getCursor());
		assertEquals(Collections.singletonList("P1"), codes(edited));
		assertEquals("3", edited.getCursor());

		CopiesDownAnswerDTO restart = pull(a, null);
		assertEquals(Arrays.asList("P2", "P1"), codes(restart), "absent cursor: from the beginning");
		assertEquals("3", restart.getCursor());
		for (String clock : new String[] { "2026-10-03T12:00:00", "1759492800000x", "-1", "abc" }) {
			assertThrows(IllegalArgumentException.class, () -> pull(a, clock), clock);
		}
	}

	@Test
	@DisplayName("A change committed while a pull runs is not in that answer and comes with the next pull")
	void changeDuringPull() {
		save("P1", StoreTargets.all());
		duringLoad = () -> {
			duringLoad = null;
			save("P2", StoreTargets.all()); // committed after the horizon was read
		};
		CopiesDownAnswerDTO first = pull(a, "");
		assertEquals(Collections.singletonList("P1"), codes(first));
		assertEquals("1", first.getCursor(), "the horizon read before the changes");

		CopiesDownAnswerDTO next = pull(a, first.getCursor());
		assertEquals(Collections.singletonList("P2"), codes(next));
		assertEquals("2", next.getCursor());
	}

	@Test
	@DisplayName("A change numbered above the committed number (writer not committed yet) is not read until it is")
	void uncommittedChangeNotRead() {
		save("P1", StoreTargets.all());
		// A writer holds number 2: its change row exists for it, the committed number is still 1
		records.put("P2", StoreTargets.all());
		HoDownChange pending = new HoDownChange();
		pending.setDomain(D);
		pending.setRecordCode("P2");
		pending.setChangeVersion(2);
		changes.add(pending);

		CopiesDownAnswerDTO first = pull(a, "");
		assertEquals(Collections.singletonList("P1"), codes(first));
		assertEquals("1", first.getCursor());

		sequences.put(D, 2L); // the writer commits
		assertEquals(Collections.singletonList("P2"), codes(pull(a, first.getCursor())));
	}

	@Test
	@DisplayName("Targets: every store gets an all-stores record, also a store created later; a list reaches only its stores")
	void targets() {
		save("ALL", StoreTargets.all());
		save("ONLY_A", StoreTargets.of(Collections.singletonList(a.getId())));

		CopiesDownAnswerDTO forA = pull(a, "");
		assertEquals(Arrays.asList("ALL", "ONLY_A"), codes(forA));
		assertTrue(forA.getRemoved().isEmpty());

		CopiesDownAnswerDTO forB = pull(b, "");
		assertEquals(Collections.singletonList("ALL"), codes(forB));
		assertTrue(forB.getRemoved().isEmpty(), "a store outside the list receives nothing");

		CopiesDownAnswerDTO later = pull(store(9L, "RS09"), "");
		assertEquals(Collections.singletonList("ALL"), codes(later), "a store created later");

		save("ONLY_A", StoreTargets.of(Collections.singletonList(a.getId()))); // edited, same list
		CopiesDownAnswerDTO bAfterEdit = pull(b, forB.getCursor());
		assertTrue(bAfterEdit.getRecords().isEmpty() && bAfterEdit.getRemoved().isEmpty(),
				"an edit of a record for another store brings nothing");
	}

	@Test
	@DisplayName("Targets changed: a store taken off gets a removal, a store added gets the record; back to all reaches everyone")
	void targetsChanged() {
		save("P1", StoreTargets.of(Collections.singletonList(a.getId())));
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();

		save("P1", StoreTargets.of(Collections.singletonList(b.getId()))); // A taken off, B added
		CopiesDownAnswerDTO forA = pull(a, cursorA);
		assertTrue(forA.getRecords().isEmpty());
		assertEquals(Collections.singletonList("P1"), forA.getRemoved());
		CopiesDownAnswerDTO forB = pull(b, cursorB);
		assertEquals(Collections.singletonList("P1"), codes(forB));
		assertTrue(forB.getRemoved().isEmpty());

		save("P1", StoreTargets.all()); // back to every store
		assertEquals(Collections.singletonList("P1"), codes(pull(a, forA.getCursor())));

		save("P1", StoreTargets.of(Collections.singletonList(b.getId()))); // from every store to a list
		assertEquals(Collections.singletonList("P1"), pull(store(9L, "RS09"), "").getRemoved(),
				"a store that had it through every store gets a removal");
	}

	@Test
	@DisplayName("Deleted at the head office: removed for its stores, nothing for the others")
	void deleted() {
		save("P1", StoreTargets.of(Collections.singletonList(a.getId())));
		save("P2", StoreTargets.all());
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();

		delete("P1");
		delete("P2");
		CopiesDownAnswerDTO forA = pull(a, cursorA);
		assertEquals(Arrays.asList("P1", "P2"), forA.getRemoved());
		assertTrue(forA.getRecords().isEmpty());
		assertEquals(Collections.singletonList("P2"), pull(b, cursorB).getRemoved());
	}

	@Test
	@DisplayName("Pages: limit codes per answer, more and the cursor of the last code; the last page has the domain's number")
	void pages() {
		for (int i = 1; i <= 5; i++) {
			save("P" + i, StoreTargets.all());
		}
		save("P1", StoreTargets.all()); // P1 now last (change 6)
		CopiesDownAnswerDTO first = feed.pull(a, "PROMOTIONS", "", 2);
		assertEquals(Arrays.asList("P2", "P3"), codes(first));
		assertTrue(first.isMore());
		assertEquals("3", first.getCursor());
		CopiesDownAnswerDTO second = feed.pull(a, "promotions", first.getCursor(), 2);
		assertEquals(Arrays.asList("P4", "P5"), codes(second));
		assertTrue(second.isMore());
		CopiesDownAnswerDTO third = feed.pull(a, " Promotions ", second.getCursor(), 2);
		assertEquals(Collections.singletonList("P1"), codes(third));
		assertFalse(third.isMore());
		assertEquals("6", third.getCursor());
	}

	@Test
	@DisplayName("A cursor after the domain's last change (head office database restored) starts from the beginning")
	void cursorAfterHorizon() {
		save("P1", StoreTargets.all());
		CopiesDownAnswerDTO answer = pull(a, "999");
		assertEquals(Collections.singletonList("P1"), codes(answer));
		assertEquals("1", answer.getCursor());
	}

	@Test
	@DisplayName("Limits, unknown domain, domain without copies down, empty domain")
	void requests() {
		for (int i = 1; i <= 3; i++) {
			save("P" + i, StoreTargets.all());
		}
		assertEquals(3, feed.pull(a, "PROMOTIONS", "", 0).getRecords().size(), "0 or less: the default");
		assertEquals(3, feed.pull(a, "PROMOTIONS", "", 10_000).getRecords().size(), "capped at the maximum");
		assertThrows(NoSuchElementException.class, () -> feed.pull(a, "SHOES", "", null));
		assertThrows(NoSuchElementException.class, () -> feed.pull(a, "LOYALTY", "", null), "no provider yet");
		assertThrows(NoSuchElementException.class, () -> feed.pull(a, null, "", null));

		setUp();
		CopiesDownAnswerDTO empty = pull(a, "");
		assertTrue(empty.getRecords().isEmpty() && empty.getRemoved().isEmpty());
		assertEquals("0", empty.getCursor());
		assertFalse(empty.isMore());
	}

	@Test
	@DisplayName("Change rows: one per (code, store) and one for every store, moved at each change; a blank code is ignored")
	void changeRows() {
		save("P1", StoreTargets.of(Arrays.asList(a.getId(), b.getId())));
		save("P1", StoreTargets.of(Arrays.asList(a.getId(), b.getId())));
		assertEquals(2, changes.size());
		assertTrue(changes.stream().allMatch(c -> c.getChangeVersion() == 2));
		save("P1", StoreTargets.all());
		assertEquals(3, changes.size());
		feed.recordChange(D, " ", StoreTargets.all());
		feed.recordChange(D, null, StoreTargets.all());
		assertEquals(3L, sequences.get(D), "no number taken for a blank code");
	}

	@Test
	@DisplayName("Startup backfill: the sequence row, and a change for every record without one; the next start adds nothing")
	void backfill() {
		records.put("OLD1", StoreTargets.all());
		records.put("OLD2", StoreTargets.of(Collections.singletonList(a.getId())));
		feed.initialise();
		assertEquals(2L, sequences.get(D));
		assertEquals(Arrays.asList("OLD1", "OLD2"), codes(pull(a, "")));
		assertEquals(Collections.singletonList("OLD1"), codes(pull(b, "")));

		feed.initialise();
		assertEquals(2L, sequences.get(D), "nothing added");

		setUp();
		feed.initialise();
		assertEquals(0L, sequences.get(D), "an empty domain gets its row at 0");
	}

	// ─── Stubs ────────────────────────────────────────────────────

	private DownDomainProvider provider() {
		return new DownDomainProvider() {
			@Override
			public DataDomain getDomain() {
				return D;
			}

			@Override
			public Map<String, JsonNode> load(Store store, List<String> codes) {
				if (duringLoad != null) {
					duringLoad.run();
				}
				Map<String, JsonNode> copies = new LinkedHashMap<>();
				for (String code : codes) {
					StoreTargets targets = records.get(code);
					if (targets != null && targets.includes(store.getId())) {
						copies.put(code, mapper.createObjectNode().put("code", code));
					}
				}
				return copies;
			}

			@Override
			public Map<String, StoreTargets> currentTargets() {
				return new LinkedHashMap<>(records);
			}
		};
	}

	private HoDownChangeRepository changeRepository() {
		return stub(HoDownChangeRepository.class, (method, args) -> {
			switch (method) {
				case "findByDomainAndRecordCodeAndStoreId":
					return changes.stream().filter(c -> c.getDomain() == args[0] && c.getRecordCode().equals(args[1])
							&& Objects.equals(c.getStoreId(), args[2])).findFirst();
				case "findByDomainAndRecordCodeAndStoreIdIsNull":
					return changes.stream().filter(c -> c.getDomain() == args[0] && c.getRecordCode().equals(args[1])
							&& c.getStoreId() == null).findFirst();
				case "save":
					HoDownChange change = (HoDownChange) args[0];
					if (changes.stream().noneMatch(c -> c == change)) {
						change.setId((long) changes.size() + 1);
						changes.add(change);
					}
					return change;
				case "findChanged": {
					long storeId = (Long) args[1];
					long after = (Long) args[2];
					long upTo = (Long) args[3];
					Map<String, Long> max = new LinkedHashMap<>();
					for (HoDownChange c : changes) {
						if (c.getDomain() == args[0] && (c.getStoreId() == null || c.getStoreId() == storeId)
								&& c.getChangeVersion() > after && c.getChangeVersion() <= upTo) {
							max.merge(c.getRecordCode(), c.getChangeVersion(), Math::max);
						}
					}
					return max.entrySet().stream().sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
							.limit(((Pageable) args[4]).getPageSize())
							.map(e -> new Object[] { e.getKey(), e.getValue() }).collect(Collectors.toList());
				}
				case "findCodes":
					return changes.stream().filter(c -> c.getDomain() == args[0]).map(HoDownChange::getRecordCode)
							.distinct().collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		});
	}

	private HoDownSequenceRepository sequenceRepository() {
		return stub(HoDownSequenceRepository.class, (method, args) -> {
			switch (method) {
				case "increment":
					if (!sequences.containsKey(args[0])) {
						return 0;
					}
					sequences.merge((DataDomain) args[0], 1L, Long::sum);
					return 1;
				case "lastVersion":
					Long value = sequences.get(args[0]);
					return value == null ? new ArrayList<>() : new ArrayList<>(Collections.singletonList(value));
				case "save":
					HoDownSequence sequence = (HoDownSequence) args[0];
					sequences.put(sequence.getDomain(), sequence.getLastVersion());
					return sequence;
				default:
					return UNHANDLED;
			}
		});
	}

	private static Store store(Long id, String code) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		return store;
	}

	// ─── Stub helper ──────────────────────────────────────────────

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
