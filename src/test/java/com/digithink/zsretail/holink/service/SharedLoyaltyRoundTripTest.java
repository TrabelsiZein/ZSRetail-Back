package com.digithink.zsretail.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.hamcrest.Matchers.startsWith;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

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

import com.digithink.zsretail.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.dto.LoyaltyMemberDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberAnswerDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberEditDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMovementCopyDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyAnswerDTO;
import com.digithink.zsretail.headoffice.model.HoLoyaltyMovement;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;
import com.digithink.zsretail.headoffice.service.HoLoyaltyReceiver;
import com.digithink.zsretail.headoffice.service.HoLoyaltyService;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.HeadOfficeCallResult;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.model.LoyaltyMemberCopy;
import com.digithink.zsretail.holink.model.LoyaltyMovementCopy;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.LoyaltyTransaction;
import com.digithink.zsretail.model.MemberFunction;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.LoyaltyService;
import com.digithink.zsretail.support.InMemoryLoyalty;
import com.digithink.zsretail.support.InMemoryStoreLink;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, step 4: shared loyalty end to end. A real store side (LoyaltyService with StoreLoyaltyHooks,
 * StoreLoyaltyNetwork, LoyaltyPushService, CopiesDownPuller with LoyaltyDownHandler, the real HeadOfficeClient) talks
 * through MockRestServiceServer to a real head office side (HoLoyaltyReceiver, HoLoyaltyService, CopiesDownFeed), each
 * over its own in-memory tables, with different ids. The head office can be stopped (connection refused) or lose an
 * answer after applying it. Enrol online and offline, merge with points moved, movements applied once, the balance
 * never backwards and never below zero, local members switched off, the store's rights. No Spring context.
 */
class SharedLoyaltyRoundTripTest {

	private static final String URL = "http://localhost:888/zsretail/api";
	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 12, 0);
	private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	// Head office
	private final InMemoryLoyalty ho = new InMemoryLoyalty(1);
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
	private MemberFunction storeClient;
	private String storeCode = "rs01";

	// The line between them
	private boolean headOfficeDown;
	private boolean loseNextMovementAnswer;
	private final List<String> calls = new ArrayList<>();

	@BeforeEach
	void setUp() {
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		HoLoyaltyService register = new HoLoyaltyService(ho.memberRepository(), ho.programRepository(),
				() -> holder[0]);
		feed = new InMemoryDownTables().feed(Collections.singletonList(register));
		holder[0] = feed;
		receiver = new HoLoyaltyReceiver(ho.memberRepository(), ho.programRepository(), ho.transactionRepository(),
				ho.functionRepository(), ho.customerRepository(), ho.aliasRepository(), ho.movementRepository(),
				register, () -> hoLoyalty, TransactionOperations.withoutTransaction());
		hoLoyalty = ho.loyaltyService(register);
		ho.function("CLIENT", "Client");
		stores.put("RS01", store(1L, "RS01"));
		stores.put("RS02", store(2L, "RS02"));
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
				return storeCode;
			}
		};
		HeadOfficeClient client = new HeadOfficeClient(rest, setup, URL, "key", "2.1.0");
		storeClient = db.function("CLIENT", "Client");
		StoreLoyaltyHooks hooks = new StoreLoyaltyHooks(client, db.memberRepository(), link.memberCopyRepository());
		storeLoyalty = db.loyaltyService(hooks);
		LoyaltyCopyWriter writer = new LoyaltyCopyWriter(db.memberRepository(), db.programRepository(),
				db.functionRepository(), db.customerRepository(), link.memberCopyRepository(),
				link.movementCopyRepository());
		LoyaltyDownHandler handler = new LoyaltyDownHandler(writer, link.downRecordLog(),
				TransactionOperations.withoutTransaction());
		puller = new CopiesDownPuller(client, Collections.singletonList(handler), link.cursorRepository(),
				link.exchangeLog(), TransactionOperations.withoutTransaction());
		network = new StoreLoyaltyNetwork(client, storeLoyalty, db.memberRepository(), db.functionRepository(),
				db.customerRepository(), writer, new HeadOfficeLinkStatus(), TransactionOperations.withoutTransaction());
		push = new LoyaltyPushService(client, link.memberCopyRepository(), link.movementCopyRepository(),
				db.memberRepository(), writer, link.exchangeLog(), TransactionOperations.withoutTransaction(),
				() -> NOW, 60);
	}

	// ─── Enrol ───────────────────────────────────────────────────

	@Test
	@DisplayName("Enrol online, new phone: created here LYL-RS01-000001 (store code uppercase), sent up, created there")
	void enrolOnlineNewPhone() {
		pull();
		LoyaltyMemberDTO created = network.enrol(request("SAMI", "29 954 290"));
		assertEquals("LYL-RS01-000001", created.getCardNumber());
		assertTrue(calls.contains("GET by-phone 29954290"), calls.toString());
		LoyaltyMember local = db.card("LYL-RS01-000001").get();
		assertEquals(RecordOrigin.HEAD_OFFICE, local.getOrigin());
		assertEquals(SalesCopyStatus.PENDING, link.memberCopyOf("LYL-RS01-000001").getStatus());
		assertFalse(ho.card("LYL-RS01-000001").isPresent(), "sent by the job, not at enrol");

		push.runCycle();
		LoyaltyMemberCopy row = link.memberCopyOf("LYL-RS01-000001");
		assertEquals(SalesCopyStatus.SENT, row.getStatus());
		assertEquals(LoyaltyMemberResultDTO.CREATED, row.getOutcome());
		LoyaltyMember atHeadOffice = ho.card("LYL-RS01-000001").get();
		assertEquals("29954290", atHeadOffice.getPhone());
		assertEquals("CLIENT", atHeadOffice.getMemberFunction().getCode());
		assertEquals("LYL-RS01-000002", network.enrol(request("ALI", "22984935")).getCardNumber());
	}

	@Test
	@DisplayName("Enrol online, known phone: refused with today's message naming the network card, that member saved here")
	void enrolOnlineKnownPhone() {
		hoLoyalty.createMember(hoRequest("SAMI", "29954290"));
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> network.enrol(request("Sami", "+216 29 954 290")));
		assertEquals("Ce numéro est déjà utilisé par la carte LYL-HO-000001 (SAMI BEN)", e.getMessage());
		LoyaltyMember saved = db.card("LYL-HO-000001").get();
		assertEquals(RecordOrigin.HEAD_OFFICE, saved.getOrigin());
		assertEquals(storeClient.getId(), saved.getMemberFunction().getId(), "the store's own function of that code");
		assertTrue(link.memberCopies.isEmpty(), "no card created here");
		assertEquals(1, storeLoyalty.searchMembers("2995").size(), "the cashier finds it");
	}

	@Test
	@DisplayName("Enrol offline: created here, checked here only (a switched-off local card does not count); sent when back")
	void enrolOffline() {
		db.member("LYL-000004", "OLD", "LOCAL", "29954290", false, null);
		headOfficeDown = true;
		LoyaltyMemberDTO created = network.enrol(request("SAMI", "29954290"));
		assertEquals("LYL-RS01-000001", created.getCardNumber());
		IllegalStateException again = assertThrows(IllegalStateException.class,
				() -> network.enrol(request("SAMI", "29954290")));
		assertEquals("Ce numéro est déjà utilisé par la carte LYL-RS01-000001 (SAMI BEN)", again.getMessage());

		LoyaltyPushService.Cycle down = push.runCycle();
		assertFalse(down.isDelivered());
		LoyaltyMemberCopy row = link.memberCopyOf("LYL-RS01-000001");
		assertEquals(SalesCopyStatus.PENDING, row.getStatus());
		assertEquals(0, row.getAttempts(), "an unreachable head office is not an attempt");
		assertEquals(LinkJobResult.ERROR, link.exchanges.get(link.exchanges.size() - 1).getResult());

		headOfficeDown = false;
		push.runCycle();
		assertEquals(SalesCopyStatus.SENT, row.getStatus());
		assertTrue(ho.card("LYL-RS01-000001").isPresent());
	}

	// ─── Merge ───────────────────────────────────────────────────

	@Test
	@DisplayName("Upload then merge: the local card deactivated, the surviving card here with its points plus ours; ours moved there")
	void mergeMovesPoints() {
		pull();
		hoLoyalty.createMember(hoRequest("SAMI", "29954290"));
		ho.card("LYL-HO-000001").get().setLoyaltyPoints(300);
		headOfficeDown = true;
		String card = network.enrol(request("SAMI", "29954290")).getCardNumber();
		assertEquals("LYL-RS01-000001", card);
		LoyaltyTransaction earned = earn(card, 100.0, "RS01-20261003-0001");
		assertEquals(100, db.card(card).get().getLoyaltyPoints());

		headOfficeDown = false;
		LoyaltyPushService.Cycle cycle = push.runCycle();
		assertEquals(1, cycle.getMerged());
		LoyaltyMemberCopy row = link.memberCopyOf(card);
		assertEquals(LoyaltyMemberResultDTO.MERGED, row.getOutcome());
		assertEquals("LYL-HO-000001", row.getSurvivingCardNumber());
		assertFalse(db.card(card).get().getActive(), "the local duplicate is deactivated");
		// The movement was sent in the same cycle, after the member, to the alias: applied to the surviving member
		assertEquals(SalesCopyStatus.SENT, link.movementOf(earned).getStatus());
		assertEquals(400, ho.card("LYL-HO-000001").get().getLoyaltyPoints());
		assertTrue(ho.transactions.get(ho.transactions.size() - 1).getDescription().contains("card " + card));
		LoyaltyMember surviving = db.card("LYL-HO-000001").get();
		assertEquals(RecordOrigin.HEAD_OFFICE, surviving.getOrigin());

		pull();
		assertEquals(400, db.card("LYL-HO-000001").get().getLoyaltyPoints(), "300 + 100, counted once");
		assertFalse(db.card(card).get().getActive());
	}

	@Test
	@DisplayName("Merge answer saved before our movement is applied: the surviving card here counts it (300 + 100), never 300")
	void mergeBeforeMovementApplied() {
		pull(); // the program; the member below is created at the head office after it, not pulled yet
		hoLoyalty.createMember(hoRequest("SAMI", "29954290"));
		ho.card("LYL-HO-000001").get().setLoyaltyPoints(300);
		headOfficeDown = true;
		String card = network.enrol(request("SAMI", "29954290")).getCardNumber();
		earn(card, 100.0, "S1");
		headOfficeDown = false;
		loseNextMovementAnswer = true; // the movements of this cycle are not delivered
		push.runCycle();
		assertEquals("LYL-HO-000001", link.memberCopyOf(card).getSurvivingCardNumber());
		assertEquals(400, db.card("LYL-HO-000001").get().getLoyaltyPoints());
		pull();
		assertEquals(400 + 100, db.card("LYL-HO-000001").get().getLoyaltyPoints(),
				"applied there (answer lost) and still pending here: counted twice until the next send");
		push.runCycle();
		assertEquals(400, ho.card("LYL-HO-000001").get().getLoyaltyPoints(), "applied once at the head office");
		pull();
		assertEquals(400, db.card("LYL-HO-000001").get().getLoyaltyPoints(), "the head office sends the member again");
	}

	// ─── Movements ───────────────────────────────────────────────

	@Test
	@DisplayName("A movement sent twice (answer lost) is applied once; members are sent before their movements")
	void movementOnce() {
		pull();
		String card = network.enrol(request("SAMI", "29954290")).getCardNumber();
		LoyaltyTransaction earned = earn(card, 50.0, "S1");
		loseNextMovementAnswer = true;
		push.runCycle();
		assertEquals(SalesCopyStatus.SENT, link.memberCopyOf(card).getStatus(), "the member went first");
		assertEquals(SalesCopyStatus.PENDING, link.movementOf(earned).getStatus(), "answer lost: nothing changes");
		assertEquals(50, ho.card(card).get().getLoyaltyPoints());

		push.runCycle();
		assertEquals(SalesCopyStatus.SENT, link.movementOf(earned).getStatus());
		assertEquals(50, ho.card(card).get().getLoyaltyPoints());
		assertEquals(1, ho.movements.size());
		assertEquals(String.valueOf(earned.getId()), ho.movements.get(0).getStoreKey());
		assertEquals(1, ho.transactions.stream().filter(t -> t.getLoyaltyMember().getCardNumber().equals(card)).count());
		pull();
		assertEquals(50, db.card(card).get().getLoyaltyPoints());
		assertTrue(push.runCycle().isIdle());
	}

	@Test
	@DisplayName("A member rejected by the head office: its movements wait, the others go")
	void movementsWaitForTheirMember() {
		pull();
		CreateLoyaltyMemberRequestDTO noLastName = request("SAMI", "29954290");
		noLastName.setLastName(" ");
		String rejected = network.enrol(noLastName).getCardNumber();
		String accepted = network.enrol(request("ALI", "22984935")).getCardNumber();
		LoyaltyTransaction waiting = earn(rejected, 10.0, "S1");
		LoyaltyTransaction going = earn(accepted, 20.0, "S2");
		push.runCycle();
		LoyaltyMemberCopy row = link.memberCopyOf(rejected);
		assertEquals(SalesCopyStatus.ERROR, row.getStatus());
		assertEquals("lastName is required", row.getLastError());
		assertEquals(1, row.getAttempts());
		assertEquals(SalesCopyStatus.PENDING, link.movementOf(waiting).getStatus());
		assertEquals(0, link.movementOf(waiting).getAttempts());
		assertEquals(SalesCopyStatus.SENT, link.movementOf(going).getStatus());
	}

	@Test
	@DisplayName("Balance never backwards: pulled before our push it is head office + ours; after the push and the pull, the same")
	void balanceNeverBackwards() {
		LoyaltyMember atHo = ho.member("LYL-HO-000009", "SAMI", "BEN", "29954290", true, null);
		feed.initialise();
		pull();
		assertEquals(0, db.card("LYL-HO-000009").get().getLoyaltyPoints());
		earn("LYL-HO-000009", 120.0, "S1");
		assertEquals(120, db.card("LYL-HO-000009").get().getLoyaltyPoints());

		// Another store earns 30 on the same member: the head office has 30, not our 120 yet
		receiver.receiveMovements(stores.get("RS02"), Collections.singletonList(movement("7", "LYL-HO-000009", 30)));
		assertEquals(30, atHo.getLoyaltyPoints());
		pull(); // pulled before our push
		assertEquals(150, db.card("LYL-HO-000009").get().getLoyaltyPoints(), "30 there + 120 ours, never back to 30");

		push.runCycle();
		assertEquals(150, atHo.getLoyaltyPoints());
		assertEquals(150, db.card("LYL-HO-000009").get().getLoyaltyPoints(), "the push does not touch the balance");
		pull();
		assertEquals(150, db.card("LYL-HO-000009").get().getLoyaltyPoints(), "ours is counted once");
		assertEquals(150, db.card("LYL-HO-000009").get().getTotalPointsEarned());
	}

	@Test
	@DisplayName("Never below zero: spent here and elsewhere, the head office floors at 0 and records the overspend; here 0")
	void neverBelowZero() {
		LoyaltyMember atHo = ho.member("LYL-HO-000009", "SAMI", "BEN", "29954290", true, null);
		atHo.setLoyaltyPoints(100);
		atHo.setTotalPointsEarned(100);
		feed.initialise();
		pull();
		assertEquals(100, db.card("LYL-HO-000009").get().getLoyaltyPoints());
		SalesHeader sale = sale(101.0, "S1");
		storeLoyalty.redeemPoints(db.card("LYL-HO-000009").get().getId(), 100, sale, null);
		assertEquals(0, db.card("LYL-HO-000009").get().getLoyaltyPoints());

		LoyaltyMovementCopyDTO elsewhere = movement("8", "LYL-HO-000009", -80);
		elsewhere.setType("REDEEMED");
		elsewhere.setPoints(80);
		receiver.receiveMovements(stores.get("RS02"), Collections.singletonList(elsewhere));
		assertEquals(20, atHo.getLoyaltyPoints());

		push.runCycle();
		assertEquals(0, atHo.getLoyaltyPoints());
		HoLoyaltyMovement ours = ho.movements.get(ho.movements.size() - 1);
		assertEquals(80, ours.getOverspendPoints());
		pull();
		assertEquals(0, db.card("LYL-HO-000009").get().getLoyaltyPoints());
	}

	// ─── Local records ───────────────────────────────────────────

	@Test
	@DisplayName("At the first pull, the local members and program are switched off (kept), one exchange row; never sent up")
	void localSwitchedOff() {
		LoyaltyMember old1 = db.member("LYL-000001", "OLD", "ONE", "20000001", true, null);
		LoyaltyMember old2 = db.member("LYL-000002", "OLD", "TWO", "20000002", true, RecordOrigin.LOCAL);
		db.member("LYL-000003", "OLD", "THREE", "20000003", false, null);
		LoyaltyProgram oldProgram = db.program("LOCAL-P", 2.0, true, null);
		LoyaltyTransaction oldTx = new LoyaltyTransaction();
		oldTx.setLoyaltyMember(old1);
		oldTx.setType(com.digithink.zsretail.model.enumeration.LoyaltyTransactionType.EARNED);
		oldTx.setPoints(10);
		oldTx.setBalanceBefore(0);
		oldTx.setBalanceAfter(10);
		db.transactionRepository().save(oldTx);

		pull();
		assertFalse(old1.getActive());
		assertFalse(old2.getActive());
		assertFalse(oldProgram.getActive());
		assertEquals(3, db.members.size(), "kept");
		LoyaltyProgram received = db.programs.values().stream().filter(p -> "FID".equals(p.getProgramCode()))
				.findFirst().get();
		assertTrue(received.getActive());
		assertEquals(RecordOrigin.HEAD_OFFICE, received.getOrigin());
		List<LinkExchange> warnings = link.exchanges.stream().filter(x -> x.getResult() == LinkJobResult.WARNING)
				.collect(Collectors.toList());
		assertEquals(1, warnings.size());
		assertEquals(3, warnings.get(0).getRecordCount());
		assertTrue(warnings.get(0).getError().startsWith("LOYALTY: 3 local records set inactive"), warnings.get(0).getError());

		pull();
		assertEquals(1, link.exchanges.stream().filter(x -> x.getResult() == LinkJobResult.WARNING).count());
		assertEquals(0, push.runCycle().getFound(), "a local member's movements are never sent");
		assertEquals(storeLoyalty.getActiveProgram().get().getProgramCode(), "FID");
	}

	@Test
	@DisplayName("The program received is the only active one here and earning uses it; closed at the head office, inactive here")
	void programConsultOnly() {
		pull();
		String card = network.enrol(request("SAMI", "29954290")).getCardNumber();
		LoyaltyTransaction earned = earn(card, 40.0, "S1");
		assertEquals("FID", earned.getLoyaltyProgram().getProgramCode());
		LoyaltyProgram next = new LoyaltyProgram();
		next.setProgramCode("FID-2");
		next.setName("Fidelity 2");
		next.setPointsPerDinar(2.0);
		hoLoyalty.activateNewProgram(next);
		pull();
		assertEquals("FID-2", storeLoyalty.getActiveProgram().get().getProgramCode());
		assertEquals(1, db.programs.values().stream().filter(p -> Boolean.TRUE.equals(p.getActive())).count());
		assertEquals(80, earn(card, 40.0, "S2").getPoints());
	}

	// ─── Rights ──────────────────────────────────────────────────

	@Test
	@DisplayName("Member change: refused by the head office without the right; applied there and here with it")
	void editRights() {
		hoLoyalty.createMember(hoRequest("SAMI", "29954290"));
		pull();
		Long id = db.card("LYL-HO-000001").get().getId();
		CreateLoyaltyMemberRequestDTO change = request("SAMI", "29954290");
		change.setLastName("BEN SALAH");

		StoreLoyaltyNetwork.NetworkException refused = assertThrows(StoreLoyaltyNetwork.NetworkException.class,
				() -> network.edit(id, change));
		assertEquals(403, refused.getStatus());
		assertEquals("This store may not change loyalty members: the head office gives the right on its Stores page.",
				refused.getMessage());
		assertEquals("BEN", ho.card("LYL-HO-000001").get().getLastName());

		stores.get("RS01").setCanEditMembers(true);
		assertEquals("BEN SALAH", network.edit(id, change).getLastName());
		assertEquals("BEN SALAH", ho.card("LYL-HO-000001").get().getLastName());
		assertEquals("BEN SALAH", db.card("LYL-HO-000001").get().getLastName());

		assertFalse(network.toggleActive(id).getActive());
		assertFalse(ho.card("LYL-HO-000001").get().getActive());
	}

	@Test
	@DisplayName("Member change: refused when unreachable, for a local card, for a card the head office does not know yet")
	void editRefusals() {
		stores.get("RS01").setCanEditMembers(true);
		hoLoyalty.createMember(hoRequest("SAMI", "29954290"));
		pull();
		Long id = db.card("LYL-HO-000001").get().getId();
		headOfficeDown = true;
		StoreLoyaltyNetwork.NetworkException down = assertThrows(StoreLoyaltyNetwork.NetworkException.class,
				() -> network.edit(id, request("SAMI", "29954290")));
		assertEquals(503, down.getStatus());
		assertEquals(StoreLoyaltyNetwork.UNREACHABLE, down.getMessage());

		LoyaltyMember local = db.member("LYL-000001", "OLD", "LOCAL", "20000001", true, null);
		StoreLoyaltyNetwork.NetworkException notInRegister = assertThrows(StoreLoyaltyNetwork.NetworkException.class,
				() -> network.toggleActive(local.getId()));
		assertEquals(409, notInRegister.getStatus());

		Long fresh = network.enrol(request("ALI", "22984935")).getId();
		headOfficeDown = false;
		StoreLoyaltyNetwork.NetworkException unknown = assertThrows(StoreLoyaltyNetwork.NetworkException.class,
				() -> network.edit(fresh, request("ALI", "22984935")));
		assertEquals(409, unknown.getStatus());
		assertEquals(StoreLoyaltyNetwork.NOT_KNOWN_YET, unknown.getMessage());

		hoLoyalty.createMember(hoRequest("ZED", "23333999"));
		StoreLoyaltyNetwork.NetworkException taken = assertThrows(StoreLoyaltyNetwork.NetworkException.class,
				() -> network.edit(id, request("SAMI", "23333999")));
		assertEquals(409, taken.getStatus(), "the phone of another card of the network, checked there");
		assertEquals("Ce numéro est déjà utilisé par la carte LYL-HO-000002 (ZED BEN)", taken.getMessage());
	}

	// ─── Link page ───────────────────────────────────────────────

	@Test
	@DisplayName("Link page: counts and lists of members and movements, errors first; a bad kind or status refused")
	void linkPage() {
		pull();
		CreateLoyaltyMemberRequestDTO noLastName = request("SAMI", "29954290");
		noLastName.setLastName(" ");
		String rejected = network.enrol(noLastName).getCardNumber();
		String accepted = network.enrol(request("ALI", "22984935")).getCardNumber();
		earn(accepted, 20.0, "S2");
		earn(rejected, 10.0, "S1");
		push.runCycle();
		Map<String, Map<String, Long>> counts = push.counts();
		assertEquals("{PENDING=0, SENT=1, ERROR=1}", counts.get("members").toString());
		assertEquals("{PENDING=1, SENT=1, ERROR=0}", counts.get("movements").toString());

		Map<String, Object> members = push.list("members", null, null, null);
		@SuppressWarnings("unchecked")
		List<Map<String, Object>> records = (List<Map<String, Object>>) members.get("records");
		assertEquals(rejected, records.get(0).get("cardNumber"));
		assertEquals(SalesCopyStatus.ERROR, records.get(0).get("status"));
		assertEquals("lastName is required", records.get(0).get("lastError"));
		assertEquals(2L, members.get("totalElements"));
		Map<String, Object> pending = push.list("Movements", "pending", 0, 10);
		assertEquals(1L, pending.get("totalElements"));
		assertThrows(IllegalArgumentException.class, () -> push.list("tickets", null, null, null));
		assertThrows(IllegalArgumentException.class, () -> push.list("members", "LOST", null, null));
	}

	@Test
	@DisplayName("Heartbeat rights: kept from the last answer that carried them, through failures and older answers")
	void heartbeatRights() {
		HeadOfficeLinkStatus status = new HeadOfficeLinkStatus();
		assertNull(status.get().getCanEditMembers());
		status.record(HeadOfficeCallResult.online("t1", true, false), NOW);
		status.record(HeadOfficeCallResult.failure(HeadOfficeLinkState.OFFLINE, "down"), NOW);
		assertEquals(Boolean.TRUE, status.get().getCanEditMembers());
		assertEquals(Boolean.FALSE, status.get().getCanAdjustPoints());
		status.record(HeadOfficeCallResult.online("t2"), NOW);
		assertEquals(Boolean.TRUE, status.get().getCanEditMembers());
		status.record(HeadOfficeCallResult.online("t3", false, false), NOW);
		assertEquals(Boolean.FALSE, status.get().getCanEditMembers());
	}

	@Test
	@DisplayName("Card numbers: the store's sequence follows its highest card, also one received; no store code: 409")
	void cardNumbers() {
		ho.member("LYL-RS01-000007", "SEVEN", "X", "20000007", true, null);
		feed.initialise();
		pull();
		assertEquals("LYL-RS01-000008", network.enrol(request("A", "21000001")).getCardNumber());
		storeCode = "  ";
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> storeLoyalty.createMember(request("B", "21000002")));
		assertEquals(StoreLoyaltyHooks.NO_STORE_CODE, e.getMessage());
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private void pull() {
		CopiesDownPuller.Cycle cycle = puller.runCycle();
		assertNotNull(cycle);
	}

	private LoyaltyTransaction earn(String card, double total, String salesNumber) {
		int before = db.transactions.size();
		storeLoyalty.earnPoints(db.card(card).get().getId(), sale(total, salesNumber), null);
		assertEquals(before + 1, db.transactions.size(), "one movement written");
		return db.transactions.get(db.transactions.size() - 1);
	}

	private SalesHeader sale(double total, String number) {
		SalesHeader sale = new SalesHeader();
		sale.setId(db.nextId());
		sale.setSalesNumber(number);
		sale.setTotalAmount(total);
		return sale;
	}

	private CreateLoyaltyMemberRequestDTO request(String first, String phone) {
		CreateLoyaltyMemberRequestDTO r = new CreateLoyaltyMemberRequestDTO();
		r.setFirstName(first);
		r.setLastName("BEN");
		r.setPhone(phone);
		r.setMemberFunctionId(storeClient.getId());
		return r;
	}

	private CreateLoyaltyMemberRequestDTO hoRequest(String first, String phone) {
		CreateLoyaltyMemberRequestDTO r = request(first, phone);
		r.setMemberFunctionId(ho.functions.values().iterator().next().getId());
		return r;
	}

	private static LoyaltyMovementCopyDTO movement(String key, String card, int delta) {
		LoyaltyMovementCopyDTO m = new LoyaltyMovementCopyDTO();
		m.setKey(key);
		m.setCardNumber(card);
		m.setType("EARNED");
		m.setPoints(Math.abs(delta));
		m.setDelta(delta);
		m.setSalesNumber("RS02-" + key);
		return m;
	}

	private static Store store(long id, String code) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		store.setName("Store " + code);
		return store;
	}

	/** The head office behind /ho/**: the calling store from X-Store-Code, the real services. */
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
			calls.add("GET by-phone " + decode(query.getFirst("phone")));
			answer = receiver.checkPhone(decode(query.getFirst("phone")));
		} else if (call.getMethod() == HttpMethod.POST && path.equals("/ho/loyalty/members")) {
			answer = new LoyaltyMemberAnswerDTO(receiver.receiveMembers(store,
					Arrays.asList(MAPPER.readValue(call.getBodyAsString(), LoyaltyMemberCopyDTO[].class))));
		} else if (call.getMethod() == HttpMethod.POST && path.equals("/ho/loyalty/movements")) {
			answer = new SalesCopyAnswerDTO(receiver.receiveMovements(store,
					Arrays.asList(MAPPER.readValue(call.getBodyAsString(), LoyaltyMovementCopyDTO[].class))));
			if (loseNextMovementAnswer) {
				loseNextMovementAnswer = false;
				throw new SocketTimeoutException("Read timed out"); // applied there, the answer never arrives
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
