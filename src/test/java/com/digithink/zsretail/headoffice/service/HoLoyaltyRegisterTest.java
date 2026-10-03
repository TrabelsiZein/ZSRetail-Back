package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberEditDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyMovementCopyDTO;
import com.digithink.zsretail.headoffice.dto.LoyaltyPhoneCheckDTO;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.headoffice.model.HoLoyaltyMovement;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.model.LoyaltyEarningTier;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.LoyaltyTransaction;
import com.digithink.zsretail.model.MemberFunction;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;
import com.digithink.zsretail.service.LoyaltyService;
import com.digithink.zsretail.support.InMemoryLoyalty;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Head office plan, step 4: the head office side of shared loyalty. Cards made at the head office are numbered
 * LYL-HO-000001; the active program and every member reach every store through the copies down feed; a member enrolled
 * at a store is created with its card, or merged into the card that has the phone; each movement is applied once, to
 * the member of its card or alias, never below zero (the overspend is recorded); a store's rights are enforced. Real
 * LoyaltyService, HoLoyaltyService, HoLoyaltyReceiver and CopiesDownFeed over in-memory tables; no Spring context.
 */
class HoLoyaltyRegisterTest {

	private final InMemoryDownTables tables = new InMemoryDownTables();
	private final InMemoryLoyalty db = new InMemoryLoyalty(1000);
	private CopiesDownFeed feed;
	private HoLoyaltyService register;
	private HoLoyaltyReceiver receiver;
	private LoyaltyService loyaltyService;
	private MemberFunction client;
	private Store rs01;
	private Store rs02;

	@BeforeEach
	void setUp() {
		CopiesDownFeed[] holder = new CopiesDownFeed[1];
		register = new HoLoyaltyService(db.memberRepository(), db.programRepository(), () -> holder[0]);
		feed = tables.feed(Collections.singletonList(register));
		holder[0] = feed;
		receiver = new HoLoyaltyReceiver(db.memberRepository(), db.programRepository(), db.transactionRepository(),
				db.functionRepository(), db.customerRepository(), db.aliasRepository(), db.movementRepository(),
				register, TransactionOperations.withoutTransaction());
		loyaltyService = db.loyaltyService(register);
		client = db.function("CLIENT", "Client");
		rs01 = store(1L, "RS01");
		rs02 = store(2L, "RS02");
	}

	// ─── Card numbers and copies down ────────────────────────────

	@Test
	@DisplayName("Cards created at the head office: LYL-HO-000001, its own sequence, today's LYL-000007 ignored")
	void headOfficeCardNumbers() {
		db.member("LYL-000007", "OLD", "CARD", "20000007", true, null);
		db.member("LYL-RS01-000009", "STORE", "CARD", "20000009", true, null);
		assertEquals("LYL-HO-000001", loyaltyService.createMember(request("A", "22111111")).getCardNumber());
		assertEquals("LYL-HO-000002", loyaltyService.createMember(request("B", "22111112")).getCardNumber());
		db.member("LYL-HO-000041", "HIGH", "CARD", "20000041", true, null);
		assertEquals("LYL-HO-000042", loyaltyService.createMember(request("C", "22111113")).getCardNumber());
	}

	@Test
	@DisplayName("Phone across the network at the head office: a store's card holds the number too (409, names it)")
	void headOfficePhoneAcrossNetwork() {
		receiver.receiveMembers(rs01, Collections.singletonList(upload("LYL-RS01-000001", "SAMI", "29954290")));
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> loyaltyService.createMember(request("X", "29 954 290")));
		assertEquals("Ce numéro est déjà utilisé par la carte LYL-RS01-000001 (SAMI BEN)", e.getMessage());
	}

	@Test
	@DisplayName("Every store receives the active program with its tiers and every member with the head office balance")
	void copiesDown() {
		LoyaltyProgram program = new LoyaltyProgram();
		program.setProgramCode("FID-2026");
		program.setName("Fidelity 2026");
		program.setEarningTiers(new ArrayList<>(Arrays.asList(new LoyaltyEarningTier(500.0, 2.0),
				new LoyaltyEarningTier(200.0, 1.5))));
		loyaltyService.activateNewProgram(program);
		String card = loyaltyService.createMember(request("A", "22111111")).getCardNumber();

		for (Store store : Arrays.asList(rs01, rs02)) {
			CopiesDownAnswerDTO page = feed.pull(store, "loyalty", "", 100);
			assertEquals("LOYALTY", page.getDomain());
			assertEquals(Arrays.asList("PROGRAM", "MEMBER"), kinds(page));
			JsonNode copy = page.getRecords().get(0);
			assertEquals("FID-2026", copy.get("programCode").asText());
			assertEquals(200.0, copy.get("earningTiers").get(0).get("thresholdAmount").asDouble());
			assertEquals(2, copy.get("earningTiers").size());
			JsonNode member = page.getRecords().get(1);
			assertEquals(card, member.get("cardNumber").asText());
			assertEquals("CLIENT", member.get("memberFunctionCode").asText());
			assertEquals(0, member.get("loyaltyPoints").asInt());
			assertFalse(member.has("id"));
		}
	}

	@Test
	@DisplayName("A program closed by a new one: the stores get the old code as removed, the new one as a record")
	void programReplaced() {
		LoyaltyProgram first = new LoyaltyProgram();
		first.setProgramCode("P1");
		first.setName("One");
		loyaltyService.activateNewProgram(first);
		String cursor = feed.pull(rs01, "LOYALTY", "", 100).getCursor();

		LoyaltyProgram second = new LoyaltyProgram();
		second.setProgramCode("P2");
		second.setName("Two");
		loyaltyService.activateNewProgram(second);
		CopiesDownAnswerDTO page = feed.pull(rs01, "LOYALTY", cursor, 100);
		assertEquals(Collections.singletonList("PROGRAM:P1"), page.getRemoved());
		assertEquals("P2", page.getRecords().get(0).get("programCode").asText());
	}

	@Test
	@DisplayName("Startup backfill: members and the active program made before step 4 reach every store")
	void backfill() {
		db.member("LYL-000001", "OLD", "MEMBER", "20000001", true, null);
		db.program("OLD-P", 1.0, true, null);
		db.program("CLOSED-P", 1.0, false, null);
		feed.initialise();
		CopiesDownAnswerDTO page = feed.pull(rs02, "LOYALTY", "", 100);
		assertEquals(2, page.getRecords().size());
		assertTrue(page.getRemoved().isEmpty());
	}

	// ─── Members up ──────────────────────────────────────────────

	@Test
	@DisplayName("New phone: created with the store's card number, balance 0, known to every store")
	void uploadNewPhone() {
		LoyaltyMemberResultDTO result = receiver
				.receiveMembers(rs01, Collections.singletonList(upload("LYL-RS01-000001", "SAMI", "29954290"))).get(0);
		assertTrue(result.isAccepted());
		assertEquals(LoyaltyMemberResultDTO.CREATED, result.getOutcome());
		assertNull(result.getSurvivingCardNumber());
		LoyaltyMember created = db.card("LYL-RS01-000001").get();
		assertEquals(0, created.getLoyaltyPoints());
		assertEquals("STORE:RS01", created.getCreatedBy());
		assertEquals("CLIENT", created.getMemberFunction().getCode());
		assertEquals(LocalDateTime.of(2026, 10, 3, 9, 30), created.getCreatedAt());
		assertEquals(Collections.singletonList("MEMBER:LYL-RS01-000001"), codes(feed.pull(rs02, "LOYALTY", "", 100)));
	}

	@Test
	@DisplayName("Phone already on a card: merged, the store's card an alias of it; sent again, the same answer")
	void uploadKnownPhoneMerges() {
		LoyaltyMember holder = db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		holder.setLoyaltyPoints(300);
		LoyaltyMemberResultDTO result = receiver
				.receiveMembers(rs02, Collections.singletonList(upload("LYL-RS02-000004", "Sami", "29 954 290"))).get(0);
		assertTrue(result.isAccepted());
		assertEquals(LoyaltyMemberResultDTO.MERGED, result.getOutcome());
		assertEquals("LYL-HO-000001", result.getSurvivingCardNumber());
		assertEquals(300, result.getMember().getLoyaltyPoints());
		assertFalse(db.card("LYL-RS02-000004").isPresent(), "the card is an alias, not a member");
		assertEquals(1, db.aliases.size());
		assertEquals(holder.getId(), db.aliases.get(0).getMemberId());
		assertEquals(Long.valueOf(2L), db.aliases.get(0).getStoreId());

		LoyaltyMemberResultDTO again = receiver
				.receiveMembers(rs02, Collections.singletonList(upload("LYL-RS02-000004", "Sami", "29954290"))).get(0);
		assertEquals(LoyaltyMemberResultDTO.MERGED, again.getOutcome());
		assertEquals("LYL-HO-000001", again.getSurvivingCardNumber());
		assertEquals(1, db.aliases.size());
	}

	@Test
	@DisplayName("A card already known (answer lost, sent again): EXISTS, nothing created")
	void uploadKnownCard() {
		receiver.receiveMembers(rs01, Collections.singletonList(upload("LYL-RS01-000001", "SAMI", "29954290")));
		int saves = db.memberSaves;
		LoyaltyMemberResultDTO again = receiver
				.receiveMembers(rs01, Collections.singletonList(upload("LYL-RS01-000001", "SAMI", "29954290"))).get(0);
		assertEquals(LoyaltyMemberResultDTO.EXISTS, again.getOutcome());
		assertEquals(saves, db.memberSaves);
		assertEquals(1, db.members.size());
	}

	@Test
	@DisplayName("A bad member is rejected with its reason; the next ones are saved")
	void uploadRejected() {
		LoyaltyMemberCopyDTO noCard = upload(null, "A", "21000001");
		LoyaltyMemberCopyDTO noName = upload("LYL-RS01-000002", "", "21000002");
		List<LoyaltyMemberResultDTO> results = receiver.receiveMembers(rs01,
				Arrays.asList(noCard, noName, upload("LYL-RS01-000003", "C", "21000003")));
		assertEquals("cardNumber is required", results.get(0).getMessage());
		assertEquals("firstName is required", results.get(1).getMessage());
		assertTrue(results.get(2).isAccepted());
		assertEquals(1, db.members.size());
	}

	// ─── Movements up ────────────────────────────────────────────

	@Test
	@DisplayName("A movement applied twice changes nothing: one ledger row, the balance moved once")
	void movementOnce() {
		LoyaltyMember member = db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		db.program("FID", 1.0, true, null);
		LoyaltyMovementCopyDTO earned = movement("101", "LYL-HO-000001", "EARNED", 150, 150, "RS01-20261003-0001");
		assertTrue(receiver.receiveMovements(rs01, Collections.singletonList(earned)).get(0).isAccepted());
		SalesCopyResultDTO again = receiver.receiveMovements(rs01, Collections.singletonList(earned)).get(0);
		assertTrue(again.isAccepted());
		assertEquals("already applied", again.getMessage());
		assertEquals(150, member.getLoyaltyPoints());
		assertEquals(150, member.getTotalPointsEarned());
		assertEquals(1, db.transactions.size());
		LoyaltyTransaction tx = db.transactions.get(0);
		assertEquals(LoyaltyTransactionType.EARNED, tx.getType());
		assertEquals("FID", tx.getLoyaltyProgram().getProgramCode());
		assertEquals("STORE:RS01", tx.getCreatedBy());
		assertTrue(tx.getDescription().startsWith("Store RS01, sale #RS01-20261003-0001"), tx.getDescription());

		// The same key from another store is another movement
		assertTrue(receiver.receiveMovements(rs02, Collections.singletonList(
				movement("101", "LYL-HO-000001", "EARNED", 10, 10, "RS02-20261003-0001"))).get(0).isAccepted());
		assertEquals(160, member.getLoyaltyPoints());
	}

	@Test
	@DisplayName("A movement for an alias card is applied to the surviving member")
	void movementForAlias() {
		LoyaltyMember holder = db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		holder.setLoyaltyPoints(100);
		receiver.receiveMembers(rs02, Collections.singletonList(upload("LYL-RS02-000004", "SAMI", "29954290")));
		assertTrue(receiver.receiveMovements(rs02, Collections.singletonList(
				movement("7", "LYL-RS02-000004", "EARNED", 50, 50, "RS02-1"))).get(0).isAccepted());
		assertEquals(150, holder.getLoyaltyPoints());
		HoLoyaltyMovement row = db.movements.get(0);
		assertEquals("LYL-RS02-000004", row.getCardNumber());
		assertEquals(holder.getId(), row.getMemberId());
		assertTrue(db.transactions.get(0).getDescription().contains("card LYL-RS02-000004"));
	}

	@Test
	@DisplayName("The balance never goes below zero: the part that could not be removed is the overspend")
	void neverBelowZero() {
		LoyaltyMember member = db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		member.setLoyaltyPoints(30);
		member.setTotalPointsEarned(30);
		SalesCopyResultDTO result = receiver.receiveMovements(rs01, Collections.singletonList(
				movement("9", "LYL-HO-000001", "REDEEMED", 100, -100, "RS01-2"))).get(0);
		assertTrue(result.isAccepted());
		assertEquals(0, member.getLoyaltyPoints());
		assertEquals(30, member.getTotalPointsRedeemed());
		HoLoyaltyMovement row = db.movements.get(0);
		assertEquals(70, row.getOverspendPoints());
		LoyaltyTransaction tx = db.transactions.get(0);
		assertEquals(30, tx.getBalanceBefore());
		assertEquals(0, tx.getBalanceAfter());
		assertEquals(100, tx.getPoints());
		assertTrue(tx.getDescription().contains("overspend: 70 points"), tx.getDescription());
	}

	@Test
	@DisplayName("In order, each on its own: an unknown card is rejected, the movements after it are applied")
	void inOrderEachOnItsOwn() {
		LoyaltyMember member = db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		List<SalesCopyResultDTO> results = receiver.receiveMovements(rs01, Arrays.asList(
				movement("1", "LYL-HO-000001", "EARNED", 100, 100, "S1"),
				movement("2", "LYL-RS01-000077", "EARNED", 10, 10, "S2"),
				movement("3", "LYL-HO-000001", "REVERSED", 40, -40, "S1"),
				movement("4", "LYL-HO-000001", "WHATEVER", 1, 1, "S3")));
		assertTrue(results.get(0).isAccepted());
		assertEquals("unknown card LYL-RS01-000077: the member has not reached the head office yet",
				results.get(1).getMessage());
		assertTrue(results.get(2).isAccepted());
		assertEquals("unknown type 'WHATEVER'", results.get(3).getMessage());
		assertEquals(Arrays.asList("1", "2", "3", "4"),
				results.stream().map(SalesCopyResultDTO::getDocumentNumber).collect(Collectors.toList()));
		assertEquals(60, member.getLoyaltyPoints());
		assertEquals(60, member.getTotalPointsEarned());
		assertEquals(Arrays.asList(LoyaltyTransactionType.EARNED, LoyaltyTransactionType.REVERSED),
				db.transactions.stream().map(LoyaltyTransaction::getType).collect(Collectors.toList()));
	}

	@Test
	@DisplayName("Rights: a manual adjustment needs canAdjustPoints; points given back after a return do not")
	void adjustRight() {
		LoyaltyMember member = db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		member.setLoyaltyPoints(100);
		member.setTotalPointsRedeemed(80);
		SalesCopyResultDTO manual = receiver.receiveMovements(rs01, Collections.singletonList(
				movement("1", "LYL-HO-000001", "ADJUSTED", 20, 20, null))).get(0);
		assertFalse(manual.isAccepted());
		assertEquals(HoLoyaltyReceiver.NO_ADJUST_RIGHT, manual.getMessage());
		assertEquals(100, member.getLoyaltyPoints());

		SalesCopyResultDTO givenBack = receiver.receiveMovements(rs01, Collections.singletonList(
				movement("2", "LYL-HO-000001", "ADJUSTED", 20, 20, "S1"))).get(0);
		assertTrue(givenBack.isAccepted());
		assertEquals(120, member.getLoyaltyPoints());
		assertEquals(60, member.getTotalPointsRedeemed());

		rs01.setCanAdjustPoints(true);
		assertTrue(receiver.receiveMovements(rs01, Collections.singletonList(
				movement("1", "LYL-HO-000001", "ADJUSTED", 20, -20, null))).get(0).isAccepted());
		assertEquals(100, member.getLoyaltyPoints());
	}

	@Test
	@DisplayName("Each applied movement sends the member to every store again, with the new balance")
	void movementReachesStores() {
		db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		feed.initialise();
		String cursor = feed.pull(rs02, "LOYALTY", "", 100).getCursor();
		receiver.receiveMovements(rs01, Collections.singletonList(movement("1", "LYL-HO-000001", "EARNED", 75, 75, "S1")));
		CopiesDownAnswerDTO page = feed.pull(rs02, "LOYALTY", cursor, 100);
		assertEquals(75, page.getRecords().get(0).get("loyaltyPoints").asInt());
	}

	// ─── Live questions ──────────────────────────────────────────

	@Test
	@DisplayName("Phone check: the network's card (the active one first), none, a blank number")
	void phoneCheck() {
		db.member("LYL-HO-000001", "OLD", "CARD", "29954290", false, null);
		db.member("LYL-RS01-000002", "SAMI", "BEN", "29954290", true, null);
		LoyaltyPhoneCheckDTO found = receiver.checkPhone("+216 29 954 290");
		assertTrue(found.isFound());
		assertEquals("LYL-RS01-000002", found.getMember().getCardNumber());
		assertFalse(receiver.checkPhone("21000000").isFound());
		assertFalse(receiver.checkPhone("  ").isFound());
		assertNull(receiver.checkPhone(null).getMember());
	}

	@Test
	@DisplayName("Rights: a store without canEditMembers is refused; with it the change is applied and sent to all")
	void editRight() {
		LoyaltyMember member = db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		member.setMemberFunction(client);
		LoyaltyMemberEditDTO edit = edit("SAMI", "BEN SALAH", "29954290");
		HoLoyaltyReceiver.NoRightException refused = assertThrows(HoLoyaltyReceiver.NoRightException.class,
				() -> receiver.editMember(rs01, "LYL-HO-000001", edit));
		assertEquals(HoLoyaltyReceiver.NO_EDIT_RIGHT, refused.getMessage());
		assertEquals("BEN", member.getLastName());

		rs01.setCanEditMembers(true);
		LoyaltyMemberCopyDTO answer = receiver.editMember(rs01, "LYL-HO-000001", edit);
		assertEquals("BEN SALAH", answer.getLastName());
		assertEquals("STORE:RS01", member.getUpdatedBy());
		assertEquals(Collections.singletonList("MEMBER:LYL-HO-000001"), codes(feed.pull(rs02, "LOYALTY", "", 100)));

		edit.setActive(false);
		assertFalse(receiver.editMember(rs01, "LYL-HO-000001", edit).getActive());
	}

	@Test
	@DisplayName("Member edit from a store: the phone is unique across the network; unknown card; via an alias")
	void editRules() {
		rs01.setCanEditMembers(true);
		db.member("LYL-HO-000001", "SAMI", "BEN", "29954290", true, null);
		db.member("LYL-RS02-000001", "ALI", "KHARAT", "22984935", true, null);
		IllegalStateException taken = assertThrows(IllegalStateException.class,
				() -> receiver.editMember(rs01, "LYL-HO-000001", edit("SAMI", "BEN", "22 984 935")));
		assertEquals("Ce numéro est déjà utilisé par la carte LYL-RS02-000001 (ALI KHARAT)", taken.getMessage());
		assertThrows(IllegalArgumentException.class,
				() -> receiver.editMember(rs01, "LYL-HO-000001", edit("SAMI", "BEN", "2995429")));
		assertThrows(NoSuchElementException.class,
				() -> receiver.editMember(rs01, "LYL-XX-000001", edit("SAMI", "BEN", "29954290")));

		receiver.receiveMembers(rs01, Collections.singletonList(upload("LYL-RS01-000003", "SAMI", "29954290")));
		assertEquals("LYL-HO-000001",
				receiver.editMember(rs01, "LYL-RS01-000003", edit("SAMI", "B.", "29954290")).getCardNumber());
	}

	// ─── Helpers ─────────────────────────────────────────────────

	private CreateLoyaltyMemberRequestDTO request(String first, String phone) {
		CreateLoyaltyMemberRequestDTO r = new CreateLoyaltyMemberRequestDTO();
		r.setFirstName(first);
		r.setLastName("TEST");
		r.setPhone(phone);
		r.setMemberFunctionId(client.getId());
		return r;
	}

	private static LoyaltyMemberCopyDTO upload(String card, String first, String phone) {
		LoyaltyMemberCopyDTO copy = new LoyaltyMemberCopyDTO();
		copy.setCardNumber(card);
		copy.setFirstName(first);
		copy.setLastName("BEN");
		copy.setPhone(phone);
		copy.setMemberFunctionCode("CLIENT");
		copy.setMemberFunctionName("Client");
		copy.setLoyaltyPoints(0);
		copy.setActive(true);
		copy.setEnrolledAt(LocalDateTime.of(2026, 10, 3, 9, 30));
		return copy;
	}

	private static LoyaltyMovementCopyDTO movement(String key, String card, String type, int points, int delta,
			String salesNumber) {
		LoyaltyMovementCopyDTO m = new LoyaltyMovementCopyDTO();
		m.setKey(key);
		m.setCardNumber(card);
		m.setType(type);
		m.setPoints(points);
		m.setDelta(delta);
		m.setSalesNumber(salesNumber);
		m.setProgramCode("FID");
		m.setDate(LocalDateTime.of(2026, 10, 3, 10, 0));
		m.setDescription("Points earned from sale #" + salesNumber);
		return m;
	}

	private static LoyaltyMemberEditDTO edit(String first, String last, String phone) {
		LoyaltyMemberEditDTO edit = new LoyaltyMemberEditDTO();
		edit.setFirstName(first);
		edit.setLastName(last);
		edit.setPhone(phone);
		edit.setMemberFunctionCode("CLIENT");
		return edit;
	}

	private static Store store(long id, String code) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		store.setName("Store " + code);
		return store;
	}

	private static List<String> kinds(CopiesDownAnswerDTO page) {
		return page.getRecords().stream().map(r -> r.get("kind").asText()).collect(Collectors.toList());
	}

	private static List<String> codes(CopiesDownAnswerDTO page) {
		return page.getRecords().stream().map(r -> r.get("kind").asText() + ":"
				+ (r.has("cardNumber") ? r.get("cardNumber").asText() : r.get("programCode").asText()))
				.collect(Collectors.toList());
	}
}
