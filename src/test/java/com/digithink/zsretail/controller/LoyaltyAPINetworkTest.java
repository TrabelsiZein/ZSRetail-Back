package com.digithink.zsretail.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.dto.LoyaltyAdjustmentRequestDTO;
import com.digithink.zsretail.dto.LoyaltyMemberDTO;
import com.digithink.zsretail.dto.LoyaltyProgramDTO;
import com.digithink.zsretail.holink.service.StoreLoyaltyNetwork;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.digithink.zsretail.model.MemberFunction;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.support.InMemoryLoyalty;

/**
 * Head office plan, step 4: LoyaltyAPI on a store whose loyalty is owned by its head office (StoreLoyaltyNetwork
 * present): the program and the manual point adjustments are refused (409) before LoyaltyService; member writes go
 * through the network and its refusal reaches the caller with its status. Without it (loyalty LOCAL), the same
 * requests reach LoyaltyService exactly as before (card LYL-000001, program created, adjustment applied).
 */
class LoyaltyAPINetworkTest {

	private final InMemoryLoyalty db = new InMemoryLoyalty(1);
	private LoyaltyAPI api;
	private MemberFunction client;

	@BeforeEach
	void setUp() {
		api = new LoyaltyAPI();
		InMemoryLoyalty.inject(api, "loyaltyService", db.loyaltyService(null));
		InMemoryLoyalty.inject(api, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return null;
			}
		});
		client = db.function("CLIENT", "Client");
	}

	@Test
	@DisplayName("Loyalty owned by the head office: program writes answer 409; an adjustment goes through the network only")
	void headOfficeOnlyWrites() {
		InMemoryLoyalty.inject(api, "network",
				network(new StoreLoyaltyNetwork.NetworkException(403, "This store may not adjust points.")));
		LoyaltyProgram program = db.program("P1", 1.0, true, null);
		LoyaltyMember member = db.member("LYL-HO-000001", "A", "B", "21000000", true, null);
		assertRefused(api.createProgram(new LoyaltyProgram()), StoreLoyaltyNetwork.PROGRAM_AT_HEAD_OFFICE);
		assertRefused(api.updateProgram(program.getId(), new LoyaltyProgram()), StoreLoyaltyNetwork.PROGRAM_AT_HEAD_OFFICE);
		assertRefused(api.deleteProgram(program.getId()), StoreLoyaltyNetwork.PROGRAM_AT_HEAD_OFFICE);
		assertRefused(api.deactivateProgram(program.getId()), StoreLoyaltyNetwork.PROGRAM_AT_HEAD_OFFICE);
		LoyaltyAdjustmentRequestDTO adjust = new LoyaltyAdjustmentRequestDTO();
		adjust.setDelta(50);
		adjust.setReason("gift");
		ResponseEntity<?> refused = api.adjustPoints(member.getId(), adjust);
		assertEquals(403, refused.getStatusCodeValue());
		assertEquals(Map.of("error", "This store may not adjust points."), refused.getBody());
		adjust.setDelta(0);
		assertEquals(400, api.adjustPoints(member.getId(), adjust).getStatusCodeValue(), "checked before the call");
		assertEquals(1, db.programs.size());
		assertTrue(program.getActive());
		assertEquals(0, member.getLoyaltyPoints());
		assertTrue(db.transactions.isEmpty());
	}

	@Test
	@DisplayName("Loyalty owned by the head office: a member change refused by (or without) the head office keeps its status")
	void networkRefusals() {
		InMemoryLoyalty.inject(api, "network",
				network(new StoreLoyaltyNetwork.NetworkException(503, "The head office cannot be reached")));
		ResponseEntity<?> edit = api.updateMember(1L, new CreateLoyaltyMemberRequestDTO());
		assertEquals(503, edit.getStatusCodeValue());
		assertEquals(Collections.singletonMap("error", "The head office cannot be reached"), edit.getBody());
		assertEquals(503, api.toggleMemberActive(1L).getStatusCodeValue());
		assertEquals(503, api.linkCustomer(1L, Collections.singletonMap("customerId", 3L)).getStatusCodeValue());
	}

	@Test
	@DisplayName("Loyalty LOCAL (no network bean): LoyaltyService as before: LYL-000001, program created, adjustment applied")
	void localAsBefore() {
		CreateLoyaltyMemberRequestDTO request = new CreateLoyaltyMemberRequestDTO();
		request.setFirstName("SAMI");
		request.setLastName("BEN");
		request.setPhone("29954290");
		request.setMemberFunctionId(client.getId());
		ResponseEntity<?> created = api.createMember(request);
		assertEquals(200, created.getStatusCodeValue());
		LoyaltyMemberDTO member = (LoyaltyMemberDTO) created.getBody();
		assertEquals("LYL-000001", member.getCardNumber());
		assertEquals(null, db.card("LYL-000001").get().getOrigin(), "no origin with loyalty LOCAL");

		LoyaltyProgram program = new LoyaltyProgram();
		program.setName("Local");
		program.setProgramCode("LOCAL-1");
		ResponseEntity<?> programAnswer = api.createProgram(program);
		assertEquals(201, programAnswer.getStatusCodeValue());
		assertEquals("LOCAL-1", ((LoyaltyProgramDTO) programAnswer.getBody()).getProgramCode());

		LoyaltyAdjustmentRequestDTO adjust = new LoyaltyAdjustmentRequestDTO();
		adjust.setDelta(50);
		adjust.setReason("gift");
		assertEquals(200, api.adjustPoints(member.getId(), adjust).getStatusCodeValue());
		assertEquals(50, db.card("LYL-000001").get().getLoyaltyPoints());
	}

	@Test
	@DisplayName("Enrol 409 with loyalty owned by the head office: the message plus existingCardNumber and existingCardActive")
	void enrolConflictFields() {
		InMemoryLoyalty.inject(api, "network", network(null));
		ResponseEntity<?> answer = api.createMember(request("29954290"));
		assertEquals(409, answer.getStatusCodeValue());
		assertEquals(Map.of("error", "Ce numéro est déjà utilisé par la carte LYL-STORE-B-000001 (SAMI BEN), carte désactivée",
				"existingCardNumber", "LYL-STORE-B-000001", "existingCardActive", false), answer.getBody());
	}

	@Test
	@DisplayName("Enrol 409 with loyalty LOCAL: exactly {error} as before")
	void enrolConflictLocal() {
		db.member("LYL-000090", "ALI", "KHARAT", "22984935", true, null);
		ResponseEntity<?> answer = api.createMember(request("22984935"));
		assertEquals(409, answer.getStatusCodeValue());
		assertEquals(Map.of("error", "Ce numéro est déjà utilisé par la carte LYL-000090 (ALI KHARAT)"), answer.getBody());
	}

	private CreateLoyaltyMemberRequestDTO request(String phone) {
		CreateLoyaltyMemberRequestDTO request = new CreateLoyaltyMemberRequestDTO();
		request.setFirstName("SAMI");
		request.setLastName("BEN");
		request.setPhone(phone);
		request.setMemberFunctionId(client.getId());
		return request;
	}

	private static void assertRefused(ResponseEntity<?> answer, String message) {
		assertEquals(409, answer.getStatusCodeValue());
		assertEquals(Map.of("error", message), answer.getBody());
	}

	/** A network whose member changes all fail with this refusal (null: never called). */
	private static StoreLoyaltyNetwork network(StoreLoyaltyNetwork.NetworkException refusal) {
		return new StoreLoyaltyNetwork(null, null, null, null, null, null, null, null,
				org.springframework.transaction.support.TransactionOperations.withoutTransaction()) {
			@Override
			public LoyaltyMemberDTO enrol(CreateLoyaltyMemberRequestDTO request) {
				throw new StoreLoyaltyNetwork.PhoneTakenException("LYL-STORE-B-000001", "SAMI", "BEN", false);
			}

			@Override
			public LoyaltyMemberDTO edit(Long id, CreateLoyaltyMemberRequestDTO request) {
				throw refusal;
			}

			@Override
			public LoyaltyMemberDTO toggleActive(Long id) {
				throw refusal;
			}

			@Override
			public LoyaltyMemberDTO adjust(Long id, int delta, String reason, String adjustedBy) {
				throw refusal;
			}

			@Override
			public LoyaltyMemberDTO linkCustomer(Long id, Long customerId) {
				throw refusal;
			}
		};
	}
}
