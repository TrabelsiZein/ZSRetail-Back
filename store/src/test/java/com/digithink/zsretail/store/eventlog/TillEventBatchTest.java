package com.digithink.zsretail.store.eventlog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.digithink.zsretail.core.eventlog.EventCode;
import com.digithink.zsretail.core.eventlog.EventImportance;
import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.support.InMemoryEventLog;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.4.0, event log step 4: the till's groups (POST /event-log/batch). Only the codes the till may send, a money event
 * refused; a UUID stored already or twice in the group answered as a duplicate; the numbers of the group consecutive per
 * importance, after the server's own; the till's claims kept and what the server cannot confirm marked (doubt), never
 * refused; who sent, the address and the server's clock; a group sent twice at once; 404 off, 400 too many, 503 when
 * not stored.
 */
class TillEventBatchTest {

	final InMemoryEventLog log = new InMemoryEventLog();
	final UserAccount amine = TillEventsTest.user(7L, "amine", "Amine K.", "POS_USER");
	final UserAccount sarra = TillEventsTest.user(2L, "sarra", "Sarra B.", "RESPONSIBLE");
	final Map<Long, UserAccount> users = new HashMap<>();
	final Map<Long, CashierSession> sessions = new HashMap<>();
	final Map<Long, PosMachine> machines = new HashMap<>();
	final PosMachine till1 = machine(1L, "Caisse 1");
	final PosMachine till2 = machine(2L, "Caisse 2");
	final LocalDateTime now = LocalDateTime.of(2026, 11, 23, 18, 0);
	int storedLookups;

	{
		users.put(7L, amine);
		users.put(2L, sarra);
		sessions.put(3L, session(3L, "SESSION261123001", amine));
		sessions.put(4L, session(4L, "SESSION261123002", sarra));
		machines.put(1L, till1);
		machines.put(2L, till2);
		log.numbers.put(EventImportance.STEP, 10L); // the server's own events came first
		log.numbers.put(EventImportance.NOTABLE, 4L);
		log.numbers.put(EventImportance.ALERT, 0L);
	}

	TillEventBatch batch() {
		return new TillEventBatch(log.eventLog(), log.writer(), uuids -> {
			storedLookups++;
			return log.rows.stream().map(EventLogEntry::getEventUuid).filter(uuids::contains).collect(Collectors.toList());
		}, ids -> ids.stream().map(users::get).filter(java.util.Objects::nonNull).collect(Collectors.toList()),
				login -> users.values().stream().filter(u -> u.getUsername().equalsIgnoreCase(login)).findFirst(),
				ids -> ids.stream().map(sessions::get).filter(java.util.Objects::nonNull).collect(Collectors.toList()),
				ids -> ids.stream().map(machines::get).filter(java.util.Objects::nonNull).collect(Collectors.toList()),
				address -> "192.168.1.22".equals(address) ? Optional.of(till2) : Optional.empty(), () -> now);
	}

	@Test
	@DisplayName("A group: each event stored with the catalogue's importance and subject, the till's clock, number and"
			+ " claims, the sender, the address and the server's clock; numbers consecutive per importance in the till's order")
	void stored() {
		TillEventBatch.Answer answer = batch().receive(request(
				event("u-3", "CART_LINE_DELETED", 3L), event("u-1", "CART_LINE_ADDED", 1L), event("u-2", "CART_QTY_RAISED", 2L),
				event("u-4", "RECEIPT_REPRINTED", 4L)), amine, "192.168.1.21");
		assertEquals(Arrays.asList("u-1", "u-2", "u-3", "u-4"), answer.getStored());
		assertTrue(answer.getDuplicates().isEmpty());
		assertTrue(answer.getRefused().isEmpty());
		EventLogEntry added = log.only("CART_LINE_ADDED");
		assertEquals(EventImportance.STEP, added.getImportance());
		assertEquals("CART", added.getSubjectType());
		assertEquals(Long.valueOf(11), added.getSequenceNo());
		assertEquals(Long.valueOf(12), log.only("CART_QTY_RAISED").getSequenceNo());
		assertEquals(Long.valueOf(5), log.only("CART_LINE_DELETED").getSequenceNo(), "Notable after the server's 4");
		assertEquals(Long.valueOf(1), log.only("RECEIPT_REPRINTED").getSequenceNo());
		assertEquals(Long.valueOf(12), log.numbers.get(EventImportance.STEP));
		assertEquals(now, added.getEventAt());
		assertEquals(LocalDateTime.of(2026, 11, 23, 17, 59, 1), added.getFrontAt());
		assertEquals(Long.valueOf(1), added.getMachineSequenceNo());
		assertEquals("i-1", added.getInstallId());
		assertEquals("amine", added.getUserLogin());
		assertEquals("Amine K.", added.getUserName());
		assertEquals("SESSION261123001", added.getSessionNumber());
		assertEquals("Caisse 1", added.getPosMachineName());
		assertEquals("c-1", added.getCartId());
		assertEquals("Article A", added.getSubjectLabel());
		assertEquals(Long.valueOf(7), added.getSentById());
		assertEquals("192.168.1.21", added.getClientAddress());
		assertNull(added.getDoubt());
		assertEquals("Ligne ajoutée.", added.getDetails());
	}

	@Test
	@DisplayName("Sent again: a UUID stored already, or twice in the group, is a duplicate (answered, not stored again)")
	void duplicates() {
		batch().receive(request(event("u-1", "CART_LINE_ADDED", 1L)), amine, "192.168.1.21");
		TillEventBatch.Answer again = batch().receive(request(event("u-1", "CART_LINE_ADDED", 1L),
				event("u-2", "CART_CLEARED", 2L), event("u-2", "CART_CLEARED", 2L)), amine, "192.168.1.21");
		assertEquals(Arrays.asList("u-2"), again.getStored());
		assertEquals(Arrays.asList("u-2", "u-1"), again.getDuplicates());
		assertEquals(2, log.rows.size());
	}

	@Test
	@DisplayName("Refused: a money event (written by the server only), an unknown code, no UUID; the rest of the group stored")
	void refusedCodes() {
		TillEventBatch.Answer answer = batch().receive(request(event("m-1", "SALE_VALIDATED", 1L),
				event("m-2", "PAYMENT_CHANGED", 2L), event("m-3", "CASH_MOVEMENT", 3L), event("x-1", "NOT_A_CODE", 4L),
				event(null, "CART_CLEARED", 5L), event("ok-1", "TICKET_X_PRINTED", 6L)), amine, "192.168.1.21");
		assertEquals(Arrays.asList("ok-1"), answer.getStored());
		Map<String, String> refused = new HashMap<>();
		answer.getRefused().forEach(r -> refused.put(String.valueOf(r.getUuid()), r.getReason()));
		assertEquals("NOT_FROM_TILL", refused.get("m-1"));
		assertEquals("NOT_FROM_TILL", refused.get("m-2"));
		assertEquals("NOT_FROM_TILL", refused.get("m-3"));
		assertEquals("UNKNOWN_CODE", refused.get("x-1"));
		assertEquals("NO_UUID", refused.get("null"));
		assertEquals(Arrays.asList("TICKET_X_PRINTED"), log.codes());
	}

	@Test
	@DisplayName("The catalogue: the till may send the cart, the discounts, a pending taken back, the reprints, the X / Z and"
			+ " session tickets and its losses; never a money event")
	void tillCodes() {
		for (EventCode money : Arrays.asList(EventCode.SALE_VALIDATED, EventCode.PAYMENT_CHANGED, EventCode.RETURN_DONE,
				EventCode.CASH_MOVEMENT, EventCode.PENDING_SAVED, EventCode.PENDING_DELETED, EventCode.SESSION_OPENED,
				EventCode.SESSION_CLOSED, EventCode.SESSION_CLOSED_WITH_DIFFERENCE, EventCode.SESSION_VERIFIED,
				EventCode.TILL_RIGHT_USED, EventCode.LOGIN_OK, EventCode.EVENTLOG_CLEANUP)) {
			assertFalse(EventCode.FROM_TILL.contains(money), money.name());
		}
		assertEquals(19, EventCode.FROM_TILL.size()); // step 5: + CART_LEFT_AT_LOGOUT, CART_LOST_ON_RELOAD; 5b: + PROMO_CODE_ADDED, PROMO_CODE_REMOVED, CART_CUSTOMER_CHANGED
		assertTrue(EventCode.FROM_TILL.contains(EventCode.CART_LOST_ON_RELOAD));
		assertTrue(EventCode.FROM_TILL.contains(EventCode.EVENTLOG_EVENTS_DROPPED));
		assertEquals(EventImportance.ALERT, EventCode.EVENTLOG_EVENTS_DROPPED.getImportance());
	}

	@Test
	@DisplayName("The claims not confirmed are kept and marked, never refused: another user's session, a user, a session or"
			+ " a machine unknown, a till time unreadable")
	void doubts() {
		TillEventBatch.Event other = event("d-1", "CART_LINE_DELETED", 1L);
		other.setSessionId(4L); // Sarra's session, claimed by Amine
		TillEventBatch.Event unknown = event("d-2", "CART_CLEARED", 2L);
		unknown.setUserId(99L);
		unknown.setSessionId(98L);
		unknown.setPosMachineId(97L);
		unknown.setAt("hier");
		TillEventBatch.Answer answer = batch().receive(request(other, unknown), amine, "192.168.1.21");
		assertEquals(Arrays.asList("d-1", "d-2"), answer.getStored());
		EventLogEntry mismatch = log.only("CART_LINE_DELETED");
		assertEquals("SESSION_OF_ANOTHER_USER", mismatch.getDoubt());
		assertEquals(Long.valueOf(4), mismatch.getSessionId());
		assertEquals("amine", mismatch.getUserLogin());
		assertEquals("Ligne ajoutée. À vérifier : la session SESSION261123002 est celle de Sarra B., pas de Amine K.",
				mismatch.getDetails());
		EventLogEntry doubtful = log.only("CART_CLEARED");
		assertEquals("FRONT_TIME_INVALID,USER_UNKNOWN,SESSION_UNKNOWN,MACHINE_UNKNOWN", doubtful.getDoubt());
		assertEquals(Long.valueOf(99), doubtful.getUserId());
		assertEquals(Long.valueOf(98), doubtful.getSessionId());
		assertEquals(Long.valueOf(97), doubtful.getPosMachineId());
		assertNull(doubtful.getFrontAt());
		assertTrue(doubtful.getDetails().contains("utilisateur n° 99 inconnu"), doubtful.getDetails());
	}

	@Test
	@DisplayName("The user claimed by his login (the till's userData has no id), any case; a login of nobody: kept, marked")
	void userByLogin() {
		TillEventBatch.Event byLogin = event("l-1", "CART_LINE_ADDED", 1L);
		byLogin.setUserId(null);
		byLogin.setUserLogin("Amine");
		byLogin.setSessionId(null);
		TillEventBatch.Event nobody = event("l-2", "CART_CLEARED", 2L);
		nobody.setUserId(null);
		nobody.setUserLogin("ghost");
		nobody.setSessionId(null);
		batch().receive(request(byLogin, nobody), sarra, "192.168.1.21"); // sent by Sarra after Amine's logout
		EventLogEntry amines = log.only("CART_LINE_ADDED");
		assertEquals(Long.valueOf(7), amines.getUserId());
		assertEquals("amine", amines.getUserLogin());
		assertEquals("sarra", amines.getSentByLogin());
		assertNull(amines.getDoubt());
		EventLogEntry ghost = log.only("CART_CLEARED");
		assertEquals("ghost", ghost.getUserLogin());
		assertEquals("USER_UNKNOWN", ghost.getDoubt());
		assertTrue(ghost.getDetails().contains("utilisateur « ghost » inconnu"), ghost.getDetails());
	}

	@Test
	@DisplayName("Without the till's claims: the user who sent, the POS machine at the request's address")
	void withoutClaims() {
		TillEventBatch.Event bare = new TillEventBatch.Event();
		bare.setUuid("b-1");
		bare.setCode("SESSION_TICKET_PRINTED");
		batch().receive(request(bare), sarra, "192.168.1.22");
		EventLogEntry row = log.only("SESSION_TICKET_PRINTED");
		assertEquals("sarra", row.getUserLogin());
		assertEquals("Caisse 2", row.getPosMachineName());
		assertNull(row.getDoubt());
		assertNull(row.getDetails());
	}

	@Test
	@DisplayName("A group sent twice at once: the database refuses the second insert (a UUID stored meanwhile), the group is"
			+ " tried again without it, the event answered as a duplicate")
	void race() {
		TillEventBatch.Event first = event("r-1", "CART_LINE_ADDED", 1L);
		TillEventBatch.Event second = event("r-2", "CART_LINE_ADDED", 2L);
		// Meanwhile: r-1 stored by the other request after this one checked
		TillEventBatch racing = new TillEventBatch(log.eventLog(), log.writer(), uuids -> {
			if (storedLookups++ == 0) {
				EventLogEntry meanwhile = new EventLogEntry();
				meanwhile.setEventUuid("r-1");
				meanwhile.setCode("CART_LINE_ADDED");
				log.rows.add(meanwhile);
				return Collections.emptyList();
			}
			return log.rows.stream().map(EventLogEntry::getEventUuid).filter(uuids::contains).collect(Collectors.toList());
		}, ids -> new ArrayList<>(users.values()), login -> Optional.empty(), ids -> new ArrayList<>(), ids -> new ArrayList<>(), address -> Optional.empty(),
				() -> now);
		TillEventBatch.Answer answer = racing.receive(request(first, second), amine, "10.0.0.5");
		assertEquals(Arrays.asList("r-2"), answer.getStored());
		assertEquals(Arrays.asList("r-1"), answer.getDuplicates());
		assertEquals(2, log.rows.size());
	}

	@Test
	@DisplayName("POST /event-log/batch: 404 off, 400 above 100 events, 200 with the answer, 503 when not stored")
	void endpoint() throws Exception {
		EventLogBatchAPI api = new EventLogBatchAPI();
		set(api, "batch", batch());
		set(api, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return amine;
			}
		});
		MockMvc mvc = MockMvcBuilders.standaloneSetup(api).build();
		ObjectMapper json = new ObjectMapper();

		MockHttpServletResponse ok = send(mvc, json, request(event("e-1", "CART_LINE_ADDED", 1L),
				event("e-2", "SALE_VALIDATED", 2L)));
		assertEquals(200, ok.getStatus());
		JsonNode body = json.readTree(ok.getContentAsString());
		assertEquals("e-1", body.path("stored").get(0).asText());
		assertEquals("NOT_FROM_TILL", body.path("refused").get(0).path("reason").asText());

		List<TillEventBatch.Event> many = new ArrayList<>();
		for (long i = 1; i <= 101; i++) {
			many.add(event("m-" + i, "CART_LINE_ADDED", i));
		}
		assertEquals(400, send(mvc, json, request(many.toArray(new TillEventBatch.Event[0]))).getStatus());

		// The database refuses the group (no repository answers): 503, the till keeps it
		set(api, "batch", new TillEventBatch(log.eventLog(), new com.digithink.zsretail.core.eventlog.service.EventLogWriter(
				null, null, org.springframework.transaction.support.TransactionOperations.withoutTransaction(), group -> {
					throw new IllegalStateException("database down");
				}), uuids -> Collections.emptyList(), ids -> Collections.emptyList(), login -> Optional.empty(),
				ids -> Collections.emptyList(),
				ids -> Collections.emptyList(), address -> Optional.empty(), () -> now));
		assertEquals(503, send(mvc, json, request(event("e-3", "CART_CLEARED", 3L))).getStatus());

		log.on = false;
		assertEquals(404, send(mvc, json, request(event("e-4", "CART_CLEARED", 4L))).getStatus());
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private static MockHttpServletResponse send(MockMvc mvc, ObjectMapper json, TillEventBatch.Request request)
			throws Exception {
		return mvc.perform(post("/event-log/batch").contentType(MediaType.APPLICATION_JSON)
				.content(json.writeValueAsString(request))).andReturn().getResponse();
	}

	private static TillEventBatch.Request request(TillEventBatch.Event... events) {
		TillEventBatch.Request request = new TillEventBatch.Request();
		request.setInstallId("i-1");
		request.setEvents(new ArrayList<>(Arrays.asList(events)));
		return request;
	}

	private TillEventBatch.Event event(String uuid, String code, Long seq) {
		TillEventBatch.Event event = new TillEventBatch.Event();
		event.setUuid(uuid);
		event.setCode(code);
		event.setSeq(seq);
		event.setAt(java.time.ZonedDateTime.of(2026, 11, 23, 17, 59, (int) (seq % 60), 0, java.time.ZoneId.systemDefault())
				.toOffsetDateTime().toString());
		event.setUserId(7L);
		event.setSessionId(3L);
		event.setPosMachineId(1L);
		event.setCartId("c-1");
		event.setSubjectId("42");
		event.setSubjectLabel("Article A");
		event.setDetails("Ligne ajoutée.");
		return event;
	}

	private static CashierSession session(Long id, String number, UserAccount cashier) {
		CashierSession session = new CashierSession();
		session.setId(id);
		session.setSessionNumber(number);
		session.setCashier(cashier);
		return session;
	}

	private static PosMachine machine(Long id, String name) {
		PosMachine machine = new PosMachine();
		machine.setId(id);
		machine.setName(name);
		return machine;
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = EventLogBatchAPI.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
