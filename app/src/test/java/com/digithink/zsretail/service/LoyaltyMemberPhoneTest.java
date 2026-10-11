package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.dto.CreateLoyaltyMemberRequestDTO;
import com.digithink.zsretail.dto.LoyaltyMemberDTO;
import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.MemberFunction;
import com.digithink.zsretail.repository.LoyaltyMemberRepository;
import com.digithink.zsretail.repository.LoyaltyProgramRepository;
import com.digithink.zsretail.repository.MemberFunctionRepository;

/**
 * Member phone rules (client request 2026-09): required, 8 digits, unique across all
 * members; on edit they apply only when the number changes. Plain JUnit with in-memory
 * repository stubs (no Spring, no database, no mocking library), so it runs on any JDK.
 */
class LoyaltyMemberPhoneTest {

	private LoyaltyService service;
	private final Map<Long, LoyaltyMember> members = new LinkedHashMap<>();
	private int saves;
	private long nextId;

	@BeforeEach
	void setUp() throws Exception {
		members.clear();
		saves = 0;
		nextId = 1000;

		MemberFunction function = new MemberFunction();
		function.setId(1L);
		function.setName("Client");

		service = new LoyaltyService();
		inject("loyaltyMemberRepository", stub(LoyaltyMemberRepository.class, (method, a) -> {
			switch (method) {
				case "findById": return Optional.ofNullable(members.get(a[0]));
				case "findByPhone":
					return members.values().stream().filter(m -> a[0].equals(m.getPhone())).collect(Collectors.toList());
				case "findMaxCardSequence": return 250;
				case "save": {
					LoyaltyMember m = (LoyaltyMember) a[0];
					if (m.getId() == null) m.setId(nextId++);
					members.put(m.getId(), m);
					saves++;
					return m;
				}
				default: return UNHANDLED;
			}
		}));
		inject("memberFunctionRepository", stub(MemberFunctionRepository.class, (method, a) ->
				"findById".equals(method) ? Optional.of(function) : UNHANDLED));
		inject("loyaltyProgramRepository", stub(LoyaltyProgramRepository.class, (method, a) ->
				"findCurrentActivePrograms".equals(method) ? new ArrayList<>() : UNHANDLED));
	}

	// ─── Normalization ───────────────────────────────────────────────

	@Test
	@DisplayName("Normalize: separators and the +216 / 00216 prefix are removed")
	void normalize() {
		for (String raw : new String[] { "29954290", "29 954 290", "29.954.290", "29-954-290", "(29) 954/290",
				"+216 29 954 290", "+21629954290", "00216 29954290", " 29954290 " }) {
			assertEquals("29954290", LoyaltyService.normalizePhone(raw), raw);
		}
		assertEquals("", LoyaltyService.normalizePhone(null));
		assertEquals("", LoyaltyService.normalizePhone("   "));
	}

	// ─── Create ──────────────────────────────────────────────────────

	@Test
	@DisplayName("Create: the number is saved normalized")
	void createSavesNormalized() {
		LoyaltyMemberDTO created = service.createMember(request("+216 29 954 290"));
		assertEquals("29954290", created.getPhone());
		assertEquals("LYL-000251", created.getCardNumber());
	}

	@Test
	@DisplayName("Create: a missing number is refused (400)")
	void createRequiresPhone() {
		for (String raw : new String[] { null, "", "   " }) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> service.createMember(request(raw)));
			assertEquals("Le numéro de téléphone est obligatoire", e.getMessage());
		}
		assertEquals(0, saves);
	}

	@Test
	@DisplayName("Create: anything other than 8 digits is refused (400)")
	void createRequiresEightDigits() {
		for (String raw : new String[] { "2177127", "217712733", "2177127a", "+33 6 12 34 56 78", "216 29954290" }) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> service.createMember(request(raw)), raw);
			assertEquals("Le numéro de téléphone doit contenir 8 chiffres", e.getMessage());
		}
		assertEquals(0, saves);
	}

	@Test
	@DisplayName("Create: a number already used is refused (409) and names the card")
	void createRefusesDuplicate() {
		member(90L, "LYL-000090", "ALI", "KHARAT", "22984935", true);
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> service.createMember(request("22 984 935")));
		assertEquals("Ce numéro est déjà utilisé par la carte LYL-000090 (ALI KHARAT)", e.getMessage());
		assertEquals(0, saves);
	}

	@Test
	@DisplayName("Create: a number held by a deactivated card is refused too, and says so")
	void createRefusesNumberOfDeactivatedCard() {
		member(7L, "LYL-000007", "najib", "rekik", "23333999", false);
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> service.createMember(request("23333999")));
		assertEquals("Ce numéro est déjà utilisé par la carte LYL-000007 (najib rekik), carte désactivée",
				e.getMessage());
	}

	@Test
	@DisplayName("Create: with an old duplicate, the message names the active card")
	void createNamesActiveCardFirst() {
		member(7L, "LYL-000007", "najib", "rekik", "23333999", false);
		member(8L, "LYL-000008", "najib", "rekik", "23333999", true);
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> service.createMember(request("23333999")));
		assertEquals("Ce numéro est déjà utilisé par la carte LYL-000008 (najib rekik)", e.getMessage());
	}

	// ─── Edit ────────────────────────────────────────────────────────

	@Test
	@DisplayName("Edit: keeping the member's own number is not a duplicate")
	void editKeepsOwnNumber() {
		member(90L, "LYL-000090", "ALI", "KHARAT", "22984935", true);
		assertEquals("22984935", service.updateMember(90L, request("22984935")).getPhone());
	}

	@Test
	@DisplayName("Edit: the same number typed with spaces counts as unchanged")
	void editSameNumberFormatted() {
		member(90L, "LYL-000090", "ALI", "KHARAT", "22984935", true);
		member(102L, "LYL-000102", "ALI", "KHARRAT", "22984935", true);
		assertEquals("22984935", service.updateMember(90L, request("22 984 935")).getPhone());
	}

	@Test
	@DisplayName("Edit: an old duplicate can be edited while its number is left unchanged")
	void editOldDuplicateUnchanged() {
		member(90L, "LYL-000090", "ALI", "KHARAT", "22984935", true);
		member(102L, "LYL-000102", "ALI", "KHARRAT", "22984935", true);
		CreateLoyaltyMemberRequestDTO r = request("22984935");
		r.setLastName("KHARRAT");
		assertEquals("KHARRAT", service.updateMember(90L, r).getLastName());
	}

	@Test
	@DisplayName("Edit: an old 7-digit number can stay as long as it is not changed")
	void editOldSevenDigitsUnchanged() {
		member(5L, "LYL-000005", "A", "B", "2177127", true);
		assertEquals("2177127", service.updateMember(5L, request("2177127")).getPhone());
	}

	@Test
	@DisplayName("Edit: changing to another member's number is refused (409)")
	void editRefusesDuplicate() {
		member(90L, "LYL-000090", "ALI", "KHARAT", "22984935", true);
		member(139L, "LYL-000139", "MELEK", "LAHYANI", "21771273", true);
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> service.updateMember(139L, request("22984935")));
		assertEquals("Ce numéro est déjà utilisé par la carte LYL-000090 (ALI KHARAT)", e.getMessage());
		assertEquals("21771273", members.get(139L).getPhone());
	}

	@Test
	@DisplayName("Edit: clearing the number is refused (400)")
	void editRequiresPhone() {
		member(90L, "LYL-000090", "ALI", "KHARAT", "22984935", true);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> service.updateMember(90L, request("")));
		assertEquals("Le numéro de téléphone est obligatoire", e.getMessage());
		assertEquals("22984935", members.get(90L).getPhone());
	}

	@Test
	@DisplayName("Edit: a member saved without a phone must be given one")
	void editMemberWithoutPhone() {
		member(3L, "LYL-000003", "A", "B", null, true);
		assertThrows(IllegalArgumentException.class, () -> service.updateMember(3L, request(null)));
		assertEquals("29954290", service.updateMember(3L, request("29 954 290")).getPhone());
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	private CreateLoyaltyMemberRequestDTO request(String phone) {
		CreateLoyaltyMemberRequestDTO r = new CreateLoyaltyMemberRequestDTO();
		r.setFirstName("New");
		r.setLastName("Member");
		r.setPhone(phone);
		r.setMemberFunctionId(1L);
		return r;
	}

	private void member(Long id, String card, String firstName, String lastName, String phone, boolean active) {
		LoyaltyMember m = new LoyaltyMember();
		m.setId(id);
		m.setCardNumber(card);
		m.setFirstName(firstName);
		m.setLastName(lastName);
		m.setPhone(phone);
		m.setActive(active);
		members.put(id, m);
	}

	// ─── Stub plumbing ───────────────────────────────────────────────

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

	private void inject(String fieldName, Object value) throws Exception {
		Field field = LoyaltyService.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(service, value);
	}
}
