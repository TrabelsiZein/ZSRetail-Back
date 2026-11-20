package com.digithink.zsretail.store.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationState;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.communication.repository.CommunicationStateRepository;
import com.digithink.zsretail.core.communication.service.CommunicationStates;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;

/**
 * 3.3.0, communication module, step 2: the store's two finders. Plain JUnit: the store's tickets and members are
 * in-memory lists read with the same rules as the JPQL of JpaStoreEventSource (its member queries run on H2 in
 * StoreMemberEventsQueryTest), comm_event and comm_state are in-memory maps, no Spring context. That the module off
 * has no finder, no scheduler and no thread: InstallationBeansProofTest (no configuration names the switch).
 */
class CommunicationEventFindersTest {

	private static final LocalDateTime ACTIVE_SINCE = LocalDateTime.of(2026, 11, 20, 10, 0);

	private LocalDateTime now = ACTIVE_SINCE.plusHours(1);

	private final List<Ticket> tickets = new ArrayList<>();
	private final List<Member> members = new ArrayList<>();
	private final Map<Long, CommunicationEvent> eventTable = new LinkedHashMap<>();
	private final Map<String, CommunicationState> stateTable = new LinkedHashMap<>();
	private final Map<Long, Integer> balances = new LinkedHashMap<>();
	private String storeCode = "STORE-B";

	// --- The store, as the queries read it ---

	private static final class Ticket {
		long id;
		LocalDateTime changedAt;
		TransactionStatus status = TransactionStatus.COMPLETED;
		LocalDateTime completedDate;
		String card;
		Integer earned;
		Integer redeemed;
	}

	private static final class Member {
		long id;
		String card;
		LocalDateTime createdAt;
		boolean active = true;
		RecordOrigin origin;
		boolean copyRow;
		String copyOutcome;
	}

	private Ticket ticket(long id, LocalDateTime changedAt, String card) {
		Ticket t = new Ticket();
		t.id = id;
		t.changedAt = changedAt;
		t.completedDate = changedAt;
		t.card = card;
		t.earned = 12;
		tickets.add(t);
		return t;
	}

	private Member member(long id, String card, LocalDateTime createdAt) {
		Member m = new Member();
		m.id = id;
		m.card = card;
		m.createdAt = createdAt;
		members.add(m);
		return m;
	}

	private boolean hasEvent(String sourceType, long sourceId) {
		return eventTable.values().stream()
				.anyMatch(e -> e.getSourceType().equals(sourceType) && e.getSourceId() == sourceId);
	}

	private final StoreEventSource source = new StoreEventSource() {
		@Override
		public List<TicketRow> completedTicketsWithMember(LocalDateTime completedFrom, LocalDateTime afterChangedAt,
				long afterId, LocalDateTime until, int limit) {
			return tickets.stream()
					.filter(t -> t.status == TransactionStatus.COMPLETED && t.card != null)
					.filter(t -> !t.completedDate.isBefore(completedFrom) && !t.changedAt.isAfter(until))
					.filter(t -> t.changedAt.isAfter(afterChangedAt) || (t.changedAt.equals(afterChangedAt) && t.id > afterId))
					.sorted(Comparator.comparing((Ticket t) -> t.changedAt).thenComparingLong(t -> t.id))
					.limit(limit)
					.map(t -> new TicketRow(t.id, t.changedAt, "T" + t.id, t.completedDate.minusMinutes(5), t.completedDate,
							25.5, t.earned, t.redeemed, null, t.card, "Ali", "Ben Salah", 40))
					.collect(Collectors.toList());
		}

		@Override
		public Integer balanceAfterSale(long salesHeaderId) {
			return balances.get(salesHeaderId);
		}

		@Override
		public List<MemberRow> membersEnrolledHere(LocalDateTime createdFrom, boolean loyaltyFromHeadOffice, int limit) {
			return members.stream()
					.filter(m -> !m.createdAt.isBefore(createdFrom) && m.active)
					.filter(m -> loyaltyFromHeadOffice ? m.copyRow && !"MERGED".equals(m.copyOutcome)
							: m.origin != RecordOrigin.HEAD_OFFICE)
					.filter(m -> !hasEvent(CommunicationTypes.LOYALTY_MEMBER, m.id))
					.sorted(Comparator.comparingLong(m -> m.id))
					.limit(limit)
					.map(m -> new MemberRow(m.id, m.card, "Sana", "Trabelsi", 0, m.createdAt))
					.collect(Collectors.toList());
		}

		@Override
		public StoreLabel storeLabel() {
			return new StoreLabel(storeCode, "Magasin B", "Happyness");
		}
	};

	// --- comm_event and comm_state ---

	private final CommunicationEventRepository events = stub(CommunicationEventRepository.class, (method, args) -> {
		switch (method) {
			case "findSourceIds":
				Collection<?> ids = (Collection<?>) args[1];
				return eventTable.values().stream()
						.filter(e -> e.getSourceType().equals(args[0]) && ids.contains(e.getSourceId()))
						.map(CommunicationEvent::getSourceId).collect(Collectors.toList());
			case "saveAll":
				for (Object o : (Iterable<?>) args[0]) {
					CommunicationEvent event = (CommunicationEvent) o;
					// uk_comm_event_dedup
					assertTrue(eventTable.values().stream().noneMatch(e -> e.getDedupKey().equals(event.getDedupKey())),
							"dedup key written twice: " + event.getDedupKey());
					event.setId((long) eventTable.size() + 1);
					eventTable.put(event.getId(), event);
				}
				return args[0];
			default:
				return UNHANDLED;
		}
	});

	private final CommunicationStateRepository stateRepository = stub(CommunicationStateRepository.class, (method, args) -> {
		switch (method) {
			case "findByCode":
				return Optional.ofNullable(stateTable.get(args[0]));
			case "save":
				CommunicationState state = (CommunicationState) args[0];
				stateTable.put(state.getCode(), state);
				return state;
			default:
				return UNHANDLED;
		}
	});

	private CommunicationStates states() {
		return new CommunicationStates(stateRepository, () -> ACTIVE_SINCE);
	}

	private String runTickets() {
		return new TicketEventFinder(source, events, states(), TransactionOperations.withoutTransaction(), () -> now)
				.run(Instant.now().plusSeconds(20));
	}

	private String runMembers(boolean loyaltyFromHeadOffice) {
		return new MemberEventFinder(source, events, states(), TransactionOperations.withoutTransaction(),
				loyaltyFromHeadOffice).run(Instant.now().plusSeconds(20));
	}

	private List<CommunicationEvent> events(String type) {
		return eventTable.values().stream().filter(e -> e.getEventType().equals(type)).collect(Collectors.toList());
	}

	// --- Activation ---

	@Test
	@DisplayName("The activation instant is written at the first call and never moves, whatever the clock says later")
	void activationNeverMoves() {
		assertEquals(ACTIVE_SINCE, states().activeSince());
		assertEquals(ACTIVE_SINCE, new CommunicationStates(stateRepository, () -> now.plusDays(3)).activeSince());
		assertEquals(1, stateTable.size());
	}

	// --- Tickets ---

	@Test
	@DisplayName("A completed ticket with a member: one PENDING event, its key, recipient, store and payload")
	void ticketEvent() {
		ticket(7, ACTIVE_SINCE.plusMinutes(10), "LYL-000012");
		balances.put(7L, 52);
		assertEquals("1 ticket event(s) written", runTickets());
		CommunicationEvent event = events(CommunicationTypes.TICKET_VALIDATED).get(0);
		assertEquals("TICKET_VALIDATED:STORE-B:7", event.getDedupKey());
		assertEquals(CommunicationTypes.SALES_HEADER, event.getSourceType());
		assertEquals(7L, event.getSourceId());
		assertEquals(CommunicationTypes.LOYALTY_MEMBER, event.getRecipientType());
		assertEquals("LYL-000012", event.getRecipientRef());
		assertEquals("STORE-B", event.getOriginStoreCode());
		assertEquals(ACTIVE_SINCE.plusMinutes(10), event.getOccurredAt());
		assertEquals(CommunicationEventStatus.PENDING, event.getStatus());
		assertEquals("{\"ticketNumber\":\"T7\",\"ticketDate\":\"2026-11-20T10:10:00\",\"total\":25.5,\"pointsEarned\":12,"
				+ "\"pointsSpent\":0,\"pointsDeduction\":0.0,\"balanceAfter\":52,\"cardNumber\":\"LYL-000012\","
				+ "\"firstName\":\"Ali\",\"lastName\":\"Ben Salah\",\"storeCode\":\"STORE-B\",\"storeName\":\"Magasin B\","
				+ "\"companyName\":\"Happyness\"}", event.getPayload());
	}

	@Test
	@DisplayName("Completed before the activation instant (even changed after it): ignored")
	void ticketBeforeActivation() {
		Ticket old = ticket(3, ACTIVE_SINCE.minusDays(2), "LYL-000001");
		old.changedAt = ACTIVE_SINCE.plusMinutes(5); // the ERP export moved its updated_at after the activation
		assertNull(runTickets());
		assertTrue(eventTable.isEmpty());
	}

	@Test
	@DisplayName("The same ticket read again (updated_at moved by the ERP export or the head office push): one event")
	void sameTicketTwice() {
		Ticket t = ticket(7, ACTIVE_SINCE.plusMinutes(10), "LYL-000012");
		runTickets();
		t.changedAt = ACTIVE_SINCE.plusMinutes(40);
		assertNull(runTickets());
		assertEquals(1, eventTable.size());
		// Even with the store code changed since: the event is found by its source, not by its key
		t.changedAt = ACTIVE_SINCE.plusMinutes(50);
		storeCode = "STORE-B2";
		assertNull(runTickets());
		assertEquals(1, eventTable.size());
	}

	@Test
	@DisplayName("A parked ticket completed later (an old id, behind the cursor) is found")
	void parkedTicket() {
		ticket(20, ACTIVE_SINCE.plusMinutes(5), "LYL-000002");
		runTickets(); // the cursor is now past id 20
		Ticket parked = ticket(4, ACTIVE_SINCE.plusMinutes(30), "LYL-000003");
		parked.status = TransactionStatus.PENDING;
		assertNull(runTickets());
		parked.status = TransactionStatus.COMPLETED; // paid later: completed and updated now
		assertEquals("1 ticket event(s) written", runTickets());
		assertEquals(2, eventTable.size());
		assertTrue(hasEvent(CommunicationTypes.SALES_HEADER, 4));
	}

	@Test
	@DisplayName("A ticket with a member and 0 points still gives its event, 0 points and the member's balance")
	void zeroPointTicket() {
		Ticket t = ticket(9, ACTIVE_SINCE.plusMinutes(10), "LYL-000005");
		t.earned = null;
		runTickets();
		String payload = events(CommunicationTypes.TICKET_VALIDATED).get(0).getPayload();
		assertTrue(payload.contains("\"pointsEarned\":0,"), payload);
		assertTrue(payload.contains("\"balanceAfter\":40,"), payload); // no loyalty row: the member's balance
	}

	@Test
	@DisplayName("A ticket without a member, cancelled or still parked: no event")
	void noEvent() {
		ticket(1, ACTIVE_SINCE.plusMinutes(10), null);
		ticket(2, ACTIVE_SINCE.plusMinutes(11), "LYL-000006").status = TransactionStatus.CANCELLED;
		ticket(3, ACTIVE_SINCE.plusMinutes(12), "LYL-000007").status = TransactionStatus.PENDING;
		assertNull(runTickets());
		assertTrue(eventTable.isEmpty());
	}

	@Test
	@DisplayName("A ticket changed less than 30 s ago is read at a later cycle")
	void settleDelay() {
		ticket(5, now.minusSeconds(10), "LYL-000008");
		assertNull(runTickets());
		now = now.plusSeconds(30);
		assertEquals("1 ticket event(s) written", runTickets());
	}

	@Test
	@DisplayName("DEFAULT_LOCATION empty: no event, nothing lost (written once the code is set)")
	void noStoreCode() {
		ticket(5, ACTIVE_SINCE.plusMinutes(10), "LYL-000008");
		member(1, "LYL-000008", ACTIVE_SINCE.plusMinutes(1));
		storeCode = null;
		assertNull(runTickets());
		assertNull(runMembers(false));
		assertTrue(eventTable.isEmpty());
		storeCode = "STORE-B";
		assertEquals("1 ticket event(s) written", runTickets());
		assertEquals("1 member event(s) written", runMembers(false));
	}

	// --- Members ---

	@Test
	@DisplayName("Loyalty LOCAL: a member enrolled here gives one event, once; before the activation, none")
	void memberLocal() {
		member(1, "LYL-000040", ACTIVE_SINCE.minusDays(1));
		member(2, "LYL-000041", ACTIVE_SINCE.plusMinutes(3));
		assertEquals("1 member event(s) written", runMembers(false));
		assertNull(runMembers(false));
		CommunicationEvent event = events(CommunicationTypes.MEMBER_CREATED).get(0);
		assertEquals("MEMBER_CREATED:LYL-000041", event.getDedupKey());
		assertEquals(2L, event.getSourceId());
		assertEquals("LYL-000041", event.getRecipientRef());
		assertEquals("{\"cardNumber\":\"LYL-000041\",\"firstName\":\"Sana\",\"lastName\":\"Trabelsi\",\"pointsBalance\":0,"
				+ "\"enrolledAt\":\"2026-11-20T10:03:00\",\"storeCode\":\"STORE-B\",\"storeName\":\"Magasin B\","
				+ "\"companyName\":\"Happyness\"}", event.getPayload());
	}

	@Test
	@DisplayName("Loyalty LOCAL: a card of the network register left from before (origin HEAD_OFFICE): no event")
	void memberLocalLeftFromNetwork() {
		member(1, "LYL-HO-000001", ACTIVE_SINCE.plusMinutes(3)).origin = RecordOrigin.HEAD_OFFICE;
		assertNull(runMembers(false));
	}

	@Test
	@DisplayName("Loyalty owned by the head office: enrolled here (copy row) gives one event; a copy received from the"
			+ " head office, a merged duplicate or an inactive member: none")
	void memberFromHeadOffice() {
		Member enrolled = member(1, "LYL-STORE-B-000001", ACTIVE_SINCE.plusMinutes(3));
		enrolled.origin = RecordOrigin.HEAD_OFFICE;
		enrolled.copyRow = true;
		Member received = member(2, "LYL-HO-000007", ACTIVE_SINCE.plusMinutes(4));
		received.origin = RecordOrigin.HEAD_OFFICE; // created_by HEAD_OFFICE, no copy row
		Member merged = member(3, "LYL-STORE-B-000002", ACTIVE_SINCE.plusMinutes(5));
		merged.copyRow = true;
		merged.copyOutcome = "MERGED";
		merged.active = false;
		Member inactive = member(4, "LYL-STORE-B-000003", ACTIVE_SINCE.plusMinutes(6));
		inactive.copyRow = true;
		inactive.active = false;
		assertEquals("1 member event(s) written", runMembers(true));
		assertNull(runMembers(true));
		assertEquals("LYL-STORE-B-000001", events(CommunicationTypes.MEMBER_CREATED).get(0).getRecipientRef());
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
