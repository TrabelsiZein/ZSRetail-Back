package com.digithink.zsretail.store.holink.service;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.enumeration.CommunicationMessageStatus;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.model.CommunicationMessage;
import com.digithink.zsretail.core.communication.provider.TestCommunicationProvider;
import com.digithink.zsretail.core.communication.service.CommunicationEventProcessor;
import com.digithink.zsretail.core.communication.service.CommunicationSender;
import com.digithink.zsretail.core.communication.service.CommunicationSettings;
import com.digithink.zsretail.core.communication.service.CommunicationStates;
import com.digithink.zsretail.core.link.dto.CommunicationEventCopyDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.support.InMemoryLoyalty;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.HoCommunicationMembers;
import com.digithink.zsretail.headoffice.service.HoCommunicationReceiver;
import com.digithink.zsretail.headoffice.service.HoLoyaltyReceiver;
import com.digithink.zsretail.headoffice.service.HoLoyaltyService;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.headoffice.support.InMemoryHoLoyalty;
import com.digithink.zsretail.store.holink.client.HeadOfficeClient;
import com.digithink.zsretail.store.holink.scheduler.CommunicationPushJob;
import com.digithink.zsretail.store.support.InMemoryStoreLink;
import com.digithink.zsretail.support.InMemoryCommunication;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.3.0, communication step 6: a store whose loyalty is the head office's pushes its comm_event rows up
 * (CommunicationPushService, the real HeadOfficeClient through MockRestServiceServer), the head office stores them
 * (HoCommunicationReceiver) and makes and sends the messages (the same processor and sender as a store alone, with
 * HoCommunicationMembers), each side over its own in-memory tables. Covers: a duplicate push, an event before its member,
 * a merged (alias) card for both event types, the head office's activation instant, a head office with the module off
 * and an older head office without the endpoint, "Run now" lifting the pause, events that end at the store. No Spring
 * context.
 */
class SharedCommunicationRoundTripTest {

	private static final String URL = "http://localhost:888/zsretail/api";
	private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 21, 10, 0);
	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	private LocalDateTime clock = T0;

	// Head office
	private final InMemoryLoyalty ho = new InMemoryLoyalty(1);
	private final InMemoryHoLoyalty hoTables = new InMemoryHoLoyalty(ho);
	private final InMemoryCommunication hoComm = new InMemoryCommunication();
	private final Store rs01 = store(1L, "RS01");
	private HoLoyaltyReceiver loyaltyReceiver;
	private HoCommunicationReceiver receiver;
	private CommunicationEventProcessor processor;
	private CommunicationSender sender;
	private boolean moduleOffAtHeadOffice;
	private boolean olderHeadOffice;
	private int pushCalls;

	// Store RS01
	private final InMemoryLoyalty db = new InMemoryLoyalty(100_000);
	private final InMemoryStoreLink link = new InMemoryStoreLink(db);
	private final InMemoryCommunication storeComm = new InMemoryCommunication();
	private CommunicationPushService push;

	@BeforeEach
	void setUp() {
		com.digithink.zsretail.headoffice.service.CopiesDownFeed[] feed = new com.digithink.zsretail.headoffice.service.CopiesDownFeed[1];
		HoLoyaltyService register = new HoLoyaltyService(ho.memberRepository(), ho.programRepository(), () -> feed[0]);
		feed[0] = new InMemoryDownTables().feed(Collections.singletonList(register));
		loyaltyReceiver = new HoLoyaltyReceiver(ho.memberRepository(), ho.programRepository(), ho.transactionRepository(),
				ho.functionRepository(), ho.customerRepository(), hoTables.aliasRepository(), hoTables.movementRepository(),
				register, () -> null, TransactionOperations.withoutTransaction());
		CommunicationStates states = new CommunicationStates(hoComm.stateRepository(), () -> T0.minusDays(1));
		states.activeSince(); // the head office's activation instant: the day before
		CommunicationSettings settings = new CommunicationSettings(new MockEnvironment()
				.withProperty("communication.sms.enabled", "true").withProperty("communication.sms.provider", "test"),
				Collections.singletonList("test")); // step 8e: no default provider
		receiver = new HoCommunicationReceiver(hoComm.eventRepository(), null, Optional.of(states),
				TransactionOperations.withoutTransaction()) {
			@Override
			public com.digithink.zsretail.core.link.dto.CommunicationEventAnswerDTO receive(Store store,
					List<CommunicationEventCopyDTO> copies) {
				return moduleOffAtHeadOffice ? off.receive(store, copies) : on.receive(store, copies);
			}
		};
		processor = new CommunicationEventProcessor(hoComm.eventRepository(), hoComm.ruleRepository(),
				hoComm.templateRepository(), hoComm.messageRepository(), ho.memberRepository(), settings,
				TransactionOperations.withoutTransaction(), () -> clock,
				new HoCommunicationMembers(ho.memberRepository(), hoTables.aliasRepository()));
		sender = new CommunicationSender(hoComm.messageRepository(), ho.memberRepository(), settings,
				Collections.singletonList(new TestCommunicationProvider()), () -> clock);
		hoComm.template("TICKET_THANKS_SMS_FR", "Merci {firstName} ({total} TND)");
		hoComm.template("MEMBER_CARD_SMS_FR", "Bienvenue {firstName}, carte {cardNumber}");
		hoComm.rule("TICKET_THANKS_SMS", CommunicationTypes.TICKET_VALIDATED, "TICKET_THANKS_SMS_FR", 24);
		hoComm.rule("MEMBER_CARD_SMS", CommunicationTypes.MEMBER_CREATED, "MEMBER_CARD_SMS_FR", 24);

		RestTemplate rest = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(URL))).andRespond(this::headOffice);
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "RS01";
			}
		};
		push = new CommunicationPushService(new HeadOfficeClient(rest, setup, URL, "key", "3.3.0"),
				storeComm.eventRepository(), link.exchangeLog(), TransactionOperations.withoutTransaction(), () -> clock);
	}

	/** The head office's receivers, module on and off (its ApplicationModeService from the properties). */
	private final HoCommunicationReceiver on = receiverWith("true");
	private final HoCommunicationReceiver off = receiverWith("false");

	private HoCommunicationReceiver receiverWith(String enabled) {
		CommunicationStates states = new CommunicationStates(hoComm.stateRepository(), () -> T0.minusDays(1));
		return new HoCommunicationReceiver(hoComm.eventRepository(), TestModes.of(new MockEnvironment()
				.withProperty("node.type", "HEAD_OFFICE").withProperty("communication.enabled", enabled)),
				Optional.of(states), TransactionOperations.withoutTransaction());
	}

	// ─── Duplicates ──────────────────────────────────────────────

	@Test
	@DisplayName("Pushed: stored once at the head office (origin the calling store, PENDING); the store's row PROCESSED;"
			+ " the same event sent again is accepted and stored nothing")
	void duplicatePush() {
		hoMember("LYL-RS01-000001", "29954290");
		CommunicationEvent ticket = storeComm.event(CommunicationTypes.TICKET_VALIDATED, "LYL-RS01-000001",
				T0.minusMinutes(2), "RS01", values("SAMI"));
		push.runCycle();
		assertEquals(CommunicationEventStatus.PROCESSED, ticket.getStatus());
		assertEquals(1, hoComm.events.size());
		CommunicationEvent there = hoComm.byKey(ticket.getDedupKey());
		assertEquals("RS01", there.getOriginStoreCode());
		assertEquals(CommunicationEventStatus.PENDING, there.getStatus());
		assertEquals(ticket.getOccurredAt(), there.getOccurredAt(), "the store's clock: the lifetime counts from it");

		ticket.setStatus(CommunicationEventStatus.PENDING); // sent again (an answer lost)
		push.runCycle();
		assertEquals(1, hoComm.events.size(), "harmless: once at the head office");
		assertEquals(CommunicationEventStatus.PROCESSED, ticket.getStatus());
		assertEquals(HoCommunicationReceiver.ALREADY_THERE, ticket.getLastError());

		process();
		send();
		CommunicationMessage message = hoComm.messagesOf(there.getId()).get(0);
		assertEquals(CommunicationMessageStatus.SENT, message.getStatus());
		assertEquals("+21629954290", message.getAddress(), "the head office's member");
		assertEquals("Merci SAMI (48.900 TND)", message.getBody());
	}

	// ─── Order of arrival ────────────────────────────────────────

	@Test
	@DisplayName("Event before its member: waits PENDING (no attempt) and is sent once the member arrives; a member that"
			+ " never arrives within the rules' lifetime: SKIPPED with the reason")
	void eventBeforeMember() {
		CommunicationEvent welcome = storeComm.event(CommunicationTypes.MEMBER_CREATED, "LYL-RS01-000001",
				T0.minusMinutes(1), "RS01", values("SAMI"));
		push.runCycle();
		CommunicationEvent there = hoComm.byKey(welcome.getDedupKey());
		process();
		assertEquals(CommunicationEventStatus.PENDING, there.getStatus(), "not a skip");
		assertEquals(0, there.getAttempts());
		assertEquals("member LYL-RS01-000001 not at the head office yet: tried again", there.getLastError());

		clock = T0.plusMinutes(10); // the member push arrives
		hoMember("LYL-RS01-000001", "29954290");
		process();
		assertEquals(CommunicationEventStatus.PROCESSED, there.getStatus());
		assertEquals(1, hoComm.messagesOf(there.getId()).size());

		CommunicationEvent lost = storeComm.event(CommunicationTypes.TICKET_VALIDATED, "LYL-RS01-000009",
				T0, "RS01", values("ALI"));
		push.runCycle();
		CommunicationEvent lostThere = hoComm.byKey(lost.getDedupKey());
		process();
		assertEquals(CommunicationEventStatus.PENDING, lostThere.getStatus());
		clock = T0.plusHours(25);
		process();
		assertEquals(CommunicationEventStatus.SKIPPED, lostThere.getStatus());
		assertEquals("member LYL-RS01-000009 never reached the head office within 24 h", lostThere.getLastError());
	}

	// ─── Merged cards ────────────────────────────────────────────

	@Test
	@DisplayName("Alias card: its MEMBER_CREATED is dropped (SKIPPED); its TICKET_VALIDATED goes to the surviving member"
			+ " (his card, his phone, his consent)")
	void aliasCard() {
		LoyaltyMember survivor = hoMember("LYL-HO-000001", "29954290");
		LoyaltyMemberCopyDTO enrolled = new LoyaltyMemberCopyDTO();
		enrolled.setCardNumber("LYL-RS01-000001");
		enrolled.setFirstName("SAMI");
		enrolled.setLastName("BEN");
		enrolled.setPhone("29954290");
		assertEquals(LoyaltyMemberResultDTO.MERGED,
				loyaltyReceiver.receiveMembers(rs01, Collections.singletonList(enrolled)).get(0).getOutcome());

		CommunicationEvent welcome = storeComm.event(CommunicationTypes.MEMBER_CREATED, "LYL-RS01-000001",
				T0.minusMinutes(3), "RS01", values("SAMI"));
		CommunicationEvent ticket = storeComm.event(CommunicationTypes.TICKET_VALIDATED, "LYL-RS01-000001",
				T0.minusMinutes(2), "RS01", values("SAMI"));
		push.runCycle();
		process();
		CommunicationEvent welcomeThere = hoComm.byKey(welcome.getDedupKey());
		assertEquals(CommunicationEventStatus.SKIPPED, welcomeThere.getStatus());
		assertEquals("card LYL-RS01-000001 merged into LYL-HO-000001: no welcome for a merged card",
				welcomeThere.getLastError());
		assertTrue(hoComm.messagesOf(welcomeThere.getId()).isEmpty());

		CommunicationEvent ticketThere = hoComm.byKey(ticket.getDedupKey());
		assertEquals(CommunicationEventStatus.PROCESSED, ticketThere.getStatus());
		CommunicationMessage message = hoComm.messagesOf(ticketThere.getId()).get(0);
		assertEquals("LYL-HO-000001", message.getRecipientRef(), "the surviving card: its consent read before sending");
		assertEquals("+21629954290", message.getAddress());

		survivor.setMessageConsent(false); // withdrawn at the head office before sending
		send();
		assertEquals(CommunicationMessageStatus.SKIPPED, message.getStatus());
	}

	// ─── Activation, module off, older head office ───────────────

	@Test
	@DisplayName("An event that happened before the head office's activation is stored SKIPPED: never messaged, never"
			+ " sent again")
	void beforeActivation() {
		hoMember("LYL-RS01-000001", "29954290");
		CommunicationEvent old = storeComm.event(CommunicationTypes.TICKET_VALIDATED, "LYL-RS01-000001",
				T0.minusDays(2), "RS01", values("SAMI"));
		push.runCycle();
		assertEquals(CommunicationEventStatus.PROCESSED, old.getStatus());
		CommunicationEvent there = hoComm.byKey(old.getDedupKey());
		assertEquals(CommunicationEventStatus.SKIPPED, there.getStatus());
		assertEquals(HoCommunicationReceiver.BEFORE_ACTIVATION, there.getLastError());
		process();
		assertTrue(hoComm.messages.isEmpty());
	}

	@Test
	@DisplayName("Head office with the module off, then an older one (404): the store pauses 1 h, nothing counted, one"
			+ " note; asked again after the pause; taken once the head office takes them")
	void headOfficeTakesNoEvents() {
		moduleOffAtHeadOffice = true;
		CommunicationEvent ticket = storeComm.event(CommunicationTypes.TICKET_VALIDATED, "LYL-RS01-000001",
				T0.minusMinutes(1), "RS01", values("SAMI"));
		CommunicationPushService.Cycle first = push.runCycle();
		assertTrue(first.isNotTaken());
		assertEquals(CommunicationEventStatus.PENDING, ticket.getStatus());
		assertEquals(0, ticket.getAttempts());
		assertTrue(hoComm.events.isEmpty());
		int calls = pushCalls;
		int notes = link.exchanges.size();
		clock = T0.plusMinutes(30);
		assertTrue(push.runCycle().isPaused());
		assertEquals(calls, pushCalls, "not asked during the pause");
		assertEquals(notes, link.exchanges.size(), "one note, not one per cycle");

		moduleOffAtHeadOffice = false;
		olderHeadOffice = true;
		clock = T0.plusMinutes(61);
		assertTrue(push.runCycle().isNotTaken(), "404: an older head office");
		assertEquals(CommunicationEventStatus.PENDING, ticket.getStatus());
		assertEquals(0, ticket.getAttempts());

		olderHeadOffice = false;
		clock = T0.plusMinutes(122);
		push.runCycle();
		assertEquals(CommunicationEventStatus.PROCESSED, ticket.getStatus());
		assertNotNull(hoComm.byKey(ticket.getDedupKey()));
	}

	@Test
	@DisplayName("Paused (the head office took no events): a scheduled run waits the hour; \"Run now\" from the Jobs page"
			+ " lifts the pause and pushes at once")
	void runNowLiftsThePause() {
		CommunicationPushJob job = new CommunicationPushJob(push);
		moduleOffAtHeadOffice = true;
		CommunicationEvent ticket = storeComm.event(CommunicationTypes.TICKET_VALIDATED, "LYL-RS01-000001",
				T0.minusMinutes(1), "RS01", values("SAMI"));
		assertEquals("the head office takes no events: paused", job.run().getMessage());
		moduleOffAtHeadOffice = false; // its module back on
		clock = T0.plusMinutes(5);
		int calls = pushCalls;
		job.run();
		assertEquals(calls, pushCalls, "a scheduled run keeps the pause");
		assertEquals(CommunicationEventStatus.PENDING, ticket.getStatus());
		assertEquals("1 taken, 0 refused", job.runNow().getMessage());
		assertEquals(CommunicationEventStatus.PROCESSED, ticket.getStatus());
		assertNotNull(hoComm.byKey(ticket.getDedupKey()));
	}

	@Test
	@DisplayName("Events never taken end quietly at the store 720 h after they happened (SKIPPED), not sent")
	void endQuietly() {
		olderHeadOffice = true;
		CommunicationEvent ticket = storeComm.event(CommunicationTypes.TICKET_VALIDATED, "LYL-RS01-000001",
				T0, "RS01", values("SAMI"));
		push.runCycle();
		clock = T0.plusHours(721);
		CommunicationPushService.Cycle cycle = push.runCycle();
		assertEquals(1, cycle.getEnded());
		assertEquals(CommunicationEventStatus.SKIPPED, ticket.getStatus());
		assertEquals("never taken by the head office within 720 h", ticket.getLastError());
		assertFalse(hoComm.events.containsKey(ticket.getId()));
		assertNull(hoComm.byKey(ticket.getDedupKey()));
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private LoyaltyMember hoMember(String card, String phone) {
		LoyaltyMember member = ho.member(card, "SAMI", "BEN", phone, true, null);
		member.setMessageConsent(true);
		return member;
	}

	private static Map<String, Object> values(String firstName) {
		Map<String, Object> values = new LinkedHashMap<>();
		values.put("firstName", firstName);
		values.put("total", 48.9);
		return values;
	}

	private void process() {
		processor.run(java.time.Instant.now().plusSeconds(20));
	}

	private void send() {
		sender.run(java.time.Instant.now().plusSeconds(20));
	}

	private static Store store(long id, String code) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		store.setName("Store " + code);
		return store;
	}

	/** The head office behind /ho/communication/events: the real receiver, module on or off, or an older version. */
	private ClientHttpResponse headOffice(ClientHttpRequest request) throws IOException {
		MockClientHttpRequest call = (MockClientHttpRequest) request;
		String path = call.getURI().getPath().substring("/zsretail/api".length());
		if (call.getMethod() != HttpMethod.POST || !path.equals("/ho/communication/events") || olderHeadOffice) {
			return withStatus(HttpStatus.NOT_FOUND).createResponse(request);
		}
		pushCalls++;
		List<CommunicationEventCopyDTO> copies = new ArrayList<>(Arrays.asList(
				MAPPER.readValue(call.getBodyAsString(), CommunicationEventCopyDTO[].class)));
		Object answer = receiver.receive(rs01, copies);
		return withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
				.body(HoLoyaltyService.COPY_MAPPER.writeValueAsString(answer)).createResponse(request);
	}
}
