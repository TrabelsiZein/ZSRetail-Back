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
import java.net.ConnectException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.digithink.zsretail.core.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberAnswerDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberEditDTO;
import com.digithink.zsretail.core.link.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.core.model.LoyaltyMember;
import com.digithink.zsretail.core.model.LoyaltyProgram;
import com.digithink.zsretail.core.model.MemberFunction;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.service.LoyaltyService;
import com.digithink.zsretail.core.support.InMemoryLoyalty;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;
import com.digithink.zsretail.headoffice.service.HoLoyaltyReceiver;
import com.digithink.zsretail.headoffice.service.HoLoyaltyService;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.headoffice.support.InMemoryHoLoyalty;
import com.digithink.zsretail.store.holink.client.HeadOfficeClient;
import com.digithink.zsretail.store.support.InMemoryStoreLink;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 3.3.0, communication step 5: the member's consent to receive messages across the shared loyalty, end to end, with
 * the harness of SharedLoyaltyRoundTripTest (a real store side and a real head office side over in-memory tables,
 * through MockRestServiceServer). Up at enrolment (LOYALTY_PUSH), down with the copies, through the head office edit;
 * a missing value (an older store or head office) changes nothing and never fails; a merge by phone keeps a yes given
 * at the new enrolment and never lets a no remove a yes. SharedLoyaltyRoundTripTest itself is unchanged.
 */
class SharedLoyaltyConsentTest {

	private static final String URL = "http://localhost:888/zsretail/api";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 21, 10, 0);
	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	// Head office
	private final InMemoryLoyalty ho = new InMemoryLoyalty(1);
	private final InMemoryHoLoyalty hoTables = new InMemoryHoLoyalty(ho);
	private CopiesDownFeed feed;
	private HoLoyaltyReceiver receiver;
	private LoyaltyService hoLoyalty;
	private final Map<String, Store> stores = new LinkedHashMap<>();

	// Store RS01
	private final InMemoryLoyalty db = new InMemoryLoyalty(100_000);
	private final InMemoryStoreLink link = new InMemoryStoreLink(db);
	private LoyaltyService storeLoyalty;
	private StoreLoyaltyNetwork network;
	private LoyaltyPushService push;
	private CopiesDownPuller puller;
	private LoyaltyCopyWriter writer;
	private MemberFunction storeClient;
	private final HeadOfficeLinkStatus linkStatus = new HeadOfficeLinkStatus();
	private final LoyaltyFreshness freshness = new LoyaltyFreshness(() -> NOW);

	private boolean headOfficeDown;

	@BeforeEach
	void setUp() {
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		HoLoyaltyService register = new HoLoyaltyService(ho.memberRepository(), ho.programRepository(),
				() -> holder[0]);
		feed = new InMemoryDownTables().feed(Collections.singletonList(register));
		holder[0] = feed;
		receiver = new HoLoyaltyReceiver(ho.memberRepository(), ho.programRepository(), ho.transactionRepository(),
				ho.functionRepository(), ho.customerRepository(), hoTables.aliasRepository(), hoTables.movementRepository(),
				register, () -> hoLoyalty, TransactionOperations.withoutTransaction());
		hoLoyalty = ho.loyaltyService(register);
		ho.function("CLIENT", "Client");
		Store rs01 = store(1L, "RS01");
		rs01.setCanEditMembers(true);
		stores.put("RS01", rs01);
		LoyaltyProgram program = new LoyaltyProgram();
		program.setProgramCode("FID");
		program.setName("Fidelity");
		program.setPointsPerDinar(1.0);
		hoLoyalty.activateNewProgram(program);

		RestTemplate rest = new RestTemplate();
		MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
		server.expect(ExpectedCount.manyTimes(), requestTo(startsWith(URL))).andRespond(this::headOffice);
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "rs01";
			}
		};
		HeadOfficeClient client = new HeadOfficeClient(rest, setup, URL, "key", "3.3.0");
		storeClient = db.function("CLIENT", "Client");
		StoreLoyaltyHooks hooks = new StoreLoyaltyHooks(client, db.memberRepository(), link.memberCopyRepository(),
				linkStatus, freshness);
		storeLoyalty = db.loyaltyService(hooks);
		writer = new LoyaltyCopyWriter(db.memberRepository(), db.programRepository(), db.functionRepository(),
				db.customerRepository(), link.memberCopyRepository(), link.movementCopyRepository());
		LoyaltyDownHandler handler = new LoyaltyDownHandler(writer, link.downRecordLog(),
				TransactionOperations.withoutTransaction());
		puller = new CopiesDownPuller(client, Collections.singletonList(handler), link.cursorRepository(),
				link.exchangeLog(), TransactionOperations.withoutTransaction());
		network = new StoreLoyaltyNetwork(client, storeLoyalty, db.memberRepository(), db.functionRepository(),
				db.customerRepository(), writer, linkStatus, freshness, TransactionOperations.withoutTransaction());
		push = new LoyaltyPushService(client, link.memberCopyRepository(), link.movementCopyRepository(),
				db.memberRepository(), writer, link.exchangeLog(), TransactionOperations.withoutTransaction(),
				() -> NOW, 60);
		pull();
	}

	// ─── Up, at enrolment ────────────────────────────────────────

	@Test
	@DisplayName("Up: a member enrolled with the tick has the consent here and at the head office (same date, by the store);"
			+ " enrolled without it, no consent on both sides")
	void upAtEnrolment() {
		String yes = network.enrol(request("SAMI", "29954290", true)).getCardNumber();
		String no = network.enrol(request("ALI", "22984935", null)).getCardNumber();
		LoyaltyMember here = db.card(yes).get();
		assertTrue(here.getMessageConsent());
		assertNotNull(here.getMessageConsentAt());
		assertEquals("System", here.getMessageConsentBy(), "no logged user in the test");
		assertFalse(db.card(no).get().getMessageConsent());
		assertNull(db.card(no).get().getMessageConsentAt());

		push.runCycle();
		LoyaltyMember there = ho.card(yes).get();
		assertTrue(there.getMessageConsent());
		assertEquals(here.getMessageConsentAt(), there.getMessageConsentAt(), "the date of the tick, not of the push");
		assertEquals("STORE:RS01/System", there.getMessageConsentBy());
		assertFalse(ho.card(no).get().getMessageConsent());
		assertNull(ho.card(no).get().getMessageConsentBy());

		pull();
		assertTrue(db.card(yes).get().getMessageConsent(), "the copy down keeps it");
	}

	// ─── Down, with the copies ───────────────────────────────────

	@Test
	@DisplayName("Down: a consent given or withdrawn at the head office reaches the store with its date and author")
	void downWithTheCopies() {
		hoLoyalty.createMember(hoRequest("SAMI", "29954290", null));
		pull();
		assertFalse(db.card("LYL-HO-000001").get().getMessageConsent());

		LoyaltyMember atHeadOffice = ho.card("LYL-HO-000001").get();
		hoLoyalty.updateMember(atHeadOffice.getId(), hoRequest("SAMI", "29954290", true));
		pull();
		LoyaltyMember here = db.card("LYL-HO-000001").get();
		assertTrue(here.getMessageConsent());
		assertEquals(atHeadOffice.getMessageConsentAt(), here.getMessageConsentAt());
		assertEquals(atHeadOffice.getMessageConsentBy(), here.getMessageConsentBy());

		hoLoyalty.updateMember(atHeadOffice.getId(), hoRequest("SAMI", "29954290", false));
		pull();
		assertFalse(db.card("LYL-HO-000001").get().getMessageConsent(), "withdrawn at the head office");
	}

	// ─── Through the head office edit ────────────────────────────

	@Test
	@DisplayName("Edit: the store's tick goes through the head office (by the store and its user) and comes back; no tick"
			+ " sent (hidden form, a deactivation) changes nothing")
	void throughTheHeadOfficeEdit() {
		hoLoyalty.createMember(hoRequest("SAMI", "29954290", null));
		pull();
		Long id = db.card("LYL-HO-000001").get().getId();

		network.edit(id, request("SAMI", "29954290", true));
		LoyaltyMember there = ho.card("LYL-HO-000001").get();
		assertTrue(there.getMessageConsent());
		assertEquals("STORE:RS01/System", there.getMessageConsentBy());
		assertTrue(db.card("LYL-HO-000001").get().getMessageConsent(), "the head office's answer saved here");
		LocalDateTime given = there.getMessageConsentAt();

		network.edit(id, request("SAMIR", "29954290", null)); // the tick not shown: nothing asked
		network.toggleActive(id); // sent "as it is", without consent
		assertTrue(ho.card("LYL-HO-000001").get().getMessageConsent());
		assertEquals(given, ho.card("LYL-HO-000001").get().getMessageConsentAt(), "unchanged: the date kept");
		assertEquals("SAMIR", ho.card("LYL-HO-000001").get().getFirstName());

		network.edit(id, request("SAMIR", "29954290", false));
		assertFalse(ho.card("LYL-HO-000001").get().getMessageConsent());
		assertFalse(db.card("LYL-HO-000001").get().getMessageConsent());
	}

	// ─── Version tolerance ───────────────────────────────────────

	@Test
	@DisplayName("Missing value: a copy of an older head office (no consent) keeps the store's; a member of an older store"
			+ " is created without consent; an unknown field never fails a read")
	void missingValue() throws Exception {
		hoLoyalty.createMember(hoRequest("SAMI", "29954290", true));
		pull();
		assertTrue(db.card("LYL-HO-000001").get().getMessageConsent());

		LoyaltyMemberCopyDTO older = LoyaltyMemberCopyDTO.of(ho.card("LYL-HO-000001").get());
		older.setFirstName("SAMIR");
		older.setMessageConsent(null);
		older.setMessageConsentAt(null);
		older.setMessageConsentBy(null);
		writer.saveMember(older);
		assertEquals("SAMIR", db.card("LYL-HO-000001").get().getFirstName(), "the rest of the copy applied");
		assertTrue(db.card("LYL-HO-000001").get().getMessageConsent(), "no value: the store's kept");

		LoyaltyMemberCopyDTO fromOlderStore = LoyaltyMemberCopyDTO.of(db.card("LYL-HO-000001").get());
		fromOlderStore.setCardNumber("LYL-RS09-000001");
		fromOlderStore.setPhone("22984935");
		fromOlderStore.setMessageConsent(null);
		fromOlderStore.setMessageConsentAt(null);
		fromOlderStore.setMessageConsentBy(null);
		List<LoyaltyMemberResultDTO> results = receiver.receiveMembers(stores.get("RS01"),
				Collections.singletonList(fromOlderStore));
		assertEquals(LoyaltyMemberResultDTO.CREATED, results.get(0).getOutcome());
		assertFalse(ho.card("LYL-RS09-000001").get().getMessageConsent());

		// A newer sender's field unknown to this reader: ignored, as an older reader ignores the consent
		ObjectMapper strict = new ObjectMapper().findAndRegisterModules();
		LoyaltyMemberCopyDTO read = strict.readValue("{\"cardNumber\":\"LYL-1\",\"messageConsent\":true,"
				+ "\"aFieldOfVersion99\":42}", LoyaltyMemberCopyDTO.class);
		assertTrue(read.getMessageConsent());
		assertNull(strict.readValue("{\"cardNumber\":\"LYL-1\"}", LoyaltyMemberCopyDTO.class).getMessageConsent());
		assertNull(strict.readValue("{\"firstName\":\"A\",\"aFieldOfVersion99\":1}", LoyaltyMemberEditDTO.class)
				.getMessageConsent());
	}

	// ─── Merge by phone ──────────────────────────────────────────

	@Test
	@DisplayName("Merge: a yes given at the new enrolment is kept on the surviving card with its date, and reaches the store")
	void mergeYes() {
		hoLoyalty.createMember(hoRequest("SAMI", "29954290", null));
		headOfficeDown = true;
		String card = network.enrol(request("SAMI", "29954290", true)).getCardNumber();
		LocalDateTime given = db.card(card).get().getMessageConsentAt();
		headOfficeDown = false;
		push.runCycle();
		assertEquals(LoyaltyMemberResultDTO.MERGED, link.memberCopyOf(card).getOutcome());
		LoyaltyMember survivor = ho.card("LYL-HO-000001").get();
		assertTrue(survivor.getMessageConsent());
		assertEquals(given, survivor.getMessageConsentAt());
		assertEquals("STORE:RS01/System", survivor.getMessageConsentBy());
		pull();
		assertTrue(db.card("LYL-HO-000001").get().getMessageConsent(), "the surviving card here");
	}

	@Test
	@DisplayName("Merge: a no at the new enrolment never removes the surviving card's yes")
	void mergeNoKeepsYes() {
		hoLoyalty.createMember(hoRequest("SAMI", "29954290", true));
		LocalDateTime given = ho.card("LYL-HO-000001").get().getMessageConsentAt();
		headOfficeDown = true;
		String card = network.enrol(request("SAMI", "29954290", false)).getCardNumber();
		headOfficeDown = false;
		push.runCycle();
		assertEquals(LoyaltyMemberResultDTO.MERGED, link.memberCopyOf(card).getOutcome());
		assertTrue(ho.card("LYL-HO-000001").get().getMessageConsent());
		assertEquals(given, ho.card("LYL-HO-000001").get().getMessageConsentAt());
	}

	@Test
	@DisplayName("Merge: a yes older than a withdrawal recorded at the head office does not undo it")
	void mergeOlderYesLosesToWithdrawal() {
		hoLoyalty.createMember(hoRequest("SAMI", "29954290", null));
		headOfficeDown = true;
		String card = network.enrol(request("SAMI", "29954290", true)).getCardNumber();
		LoyaltyMember survivor = ho.card("LYL-HO-000001").get();
		survivor.setMessageConsentAt(db.card(card).get().getMessageConsentAt().plusMinutes(5)); // withdrawn after
		survivor.setMessageConsentBy("admin");
		headOfficeDown = false;
		push.runCycle();
		assertEquals(LoyaltyMemberResultDTO.MERGED, link.memberCopyOf(card).getOutcome());
		assertFalse(ho.card("LYL-HO-000001").get().getMessageConsent());
		assertEquals("admin", ho.card("LYL-HO-000001").get().getMessageConsentBy());
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private void pull() {
		assertNotNull(puller.runCycle());
	}

	private CreateLoyaltyMemberRequestDTO request(String first, String phone, Boolean consent) {
		CreateLoyaltyMemberRequestDTO r = new CreateLoyaltyMemberRequestDTO();
		r.setFirstName(first);
		r.setLastName("BEN");
		r.setPhone(phone);
		r.setMemberFunctionId(storeClient.getId());
		r.setMessageConsent(consent);
		return r;
	}

	private CreateLoyaltyMemberRequestDTO hoRequest(String first, String phone, Boolean consent) {
		CreateLoyaltyMemberRequestDTO r = request(first, phone, consent);
		r.setMemberFunctionId(ho.functions.values().iterator().next().getId());
		return r;
	}

	private static Store store(long id, String code) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		store.setName("Store " + code);
		return store;
	}

	/** The head office behind /ho/**, as SharedLoyaltyRoundTripTest's: the calling store, the real services. */
	private ClientHttpResponse headOffice(ClientHttpRequest request) throws IOException {
		if (headOfficeDown) {
			throw new ConnectException("Connection refused");
		}
		MockClientHttpRequest call = (MockClientHttpRequest) request;
		Store store = stores.get(call.getHeaders().getFirst("X-Store-Code").trim().toUpperCase());
		String path = call.getURI().getPath().substring("/zsretail/api".length());
		MultiValueMap<String, String> query = UriComponentsBuilder.fromUri(call.getURI()).build().getQueryParams();
		Object answer;
		if (call.getMethod() == HttpMethod.GET && path.equals("/ho/down/loyalty")) {
			answer = feed.pull(store, "loyalty", decode(query.getFirst("cursor")),
					Integer.valueOf(query.getFirst("limit")));
		} else if (call.getMethod() == HttpMethod.GET && path.equals("/ho/loyalty/members/by-phone")) {
			answer = receiver.checkPhone(decode(query.getFirst("phone")));
		} else if (call.getMethod() == HttpMethod.POST && path.equals("/ho/loyalty/members")) {
			answer = new LoyaltyMemberAnswerDTO(receiver.receiveMembers(store,
					Arrays.asList(MAPPER.readValue(call.getBodyAsString(), LoyaltyMemberCopyDTO[].class))));
		} else if (call.getMethod() == HttpMethod.POST && path.equals("/ho/loyalty/movements")) {
			return error(request, HttpStatus.NOT_FOUND, "no movement in these cases");
		} else if (call.getMethod() == HttpMethod.GET && path.startsWith("/ho/loyalty/members/")) {
			try {
				answer = receiver.findMember(decode(path.substring("/ho/loyalty/members/".length())));
			} catch (NoSuchElementException e) {
				return error(request, HttpStatus.NOT_FOUND, e.getMessage());
			}
		} else if (call.getMethod() == HttpMethod.PUT && path.startsWith("/ho/loyalty/members/")) {
			String card = decode(path.substring("/ho/loyalty/members/".length()));
			try {
				answer = receiver.editMember(store, card,
						MAPPER.readValue(call.getBodyAsString(), LoyaltyMemberEditDTO.class));
			} catch (HoLoyaltyReceiver.NoRightException e) {
				return error(request, HttpStatus.FORBIDDEN, e.getMessage());
			} catch (NoSuchElementException e) {
				return error(request, HttpStatus.NOT_FOUND, e.getMessage());
			} catch (IllegalArgumentException e) {
				return error(request, HttpStatus.BAD_REQUEST, e.getMessage());
			} catch (IllegalStateException e) {
				return error(request, HttpStatus.CONFLICT, e.getMessage());
			}
		} else {
			return error(request, HttpStatus.NOT_FOUND, "no route " + call.getMethod() + " " + path);
		}
		return withStatus(HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
				.body(HoLoyaltyService.COPY_MAPPER.writeValueAsString(answer)).createResponse(request);
	}

	private static ClientHttpResponse error(ClientHttpRequest request, HttpStatus status, String message)
			throws IOException {
		return withStatus(status).contentType(MediaType.APPLICATION_JSON)
				.body(MAPPER.writeValueAsString(Collections.singletonMap("error", message))).createResponse(request);
	}

	private static String decode(String value) {
		return value == null ? null : URLDecoder.decode(value, StandardCharsets.UTF_8);
	}
}
