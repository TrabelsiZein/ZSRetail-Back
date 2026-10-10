package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
 * 2.2.2, step 4, point 3: the member's function is required only when at least one active function exists; with none
 * (or only inactive ones) the member is saved without it. A database with an active function behaves as before. Plain
 * JUnit over in-memory stubs.
 */
class LoyaltyMemberFunctionTest {

	private LoyaltyService service;
	private final Map<Long, LoyaltyMember> members = new LinkedHashMap<>();
	private final List<MemberFunction> functions = new ArrayList<>();
	private long nextId = 1000;

	@BeforeEach
	void setUp() throws Exception {
		service = new LoyaltyService();
		inject("loyaltyMemberRepository", stub(LoyaltyMemberRepository.class, (method, a) -> {
			switch (method) {
				case "findById": return Optional.ofNullable(members.get(a[0]));
				case "findByPhone": return new ArrayList<>();
				case "findMaxCardSequence": return 250;
				case "save": {
					LoyaltyMember m = (LoyaltyMember) a[0];
					if (m.getId() == null) m.setId(nextId++);
					members.put(m.getId(), m);
					return m;
				}
				default: return UNHANDLED;
			}
		}));
		inject("memberFunctionRepository", stub(MemberFunctionRepository.class, (method, a) -> {
			switch (method) {
				case "findById":
					return functions.stream().filter(f -> f.getId().equals(a[0])).findFirst();
				case "countActive":
					// the query: active is null or true
					return functions.stream().filter(f -> f.getActive() == null || f.getActive()).count();
				default: return UNHANDLED;
			}
		}));
		inject("loyaltyProgramRepository", stub(LoyaltyProgramRepository.class, (method, a) ->
				"findCurrentActivePrograms".equals(method) ? new ArrayList<>() : UNHANDLED));
	}

	private MemberFunction function(long id, String name, Boolean active) {
		MemberFunction f = new MemberFunction();
		f.setId(id);
		f.setName(name);
		f.setActive(active);
		functions.add(f);
		return f;
	}

	private static CreateLoyaltyMemberRequestDTO request(String phone, Long functionId) {
		CreateLoyaltyMemberRequestDTO r = new CreateLoyaltyMemberRequestDTO();
		r.setFirstName("New");
		r.setLastName("Member");
		r.setPhone(phone);
		r.setMemberFunctionId(functionId);
		return r;
	}

	@Test
	@DisplayName("An active function exists: create and update without one are refused, exactly as before; with one, saved")
	void requiredWhenAnActiveFunctionExists() {
		MemberFunction client = function(1L, "Client", true);
		function(2L, "Old", false);
		assertEquals("La fonction du membre est obligatoire", assertThrows(IllegalArgumentException.class,
				() -> service.createMember(request("29954290", null))).getMessage());
		assertEquals(0, members.size());

		LoyaltyMemberDTO created = service.createMember(request("29954290", 1L));
		assertSame(client, members.get(created.getId()).getMemberFunction());
		assertEquals("La fonction du membre est obligatoire", assertThrows(IllegalArgumentException.class,
				() -> service.updateMember(created.getId(), request("29954290", null))).getMessage());
		assertEquals("Fonction du membre introuvable: 9", assertThrows(IllegalArgumentException.class,
				() -> service.createMember(request("29954291", 9L))).getMessage());
	}

	@Test
	@DisplayName("A function with active not set counts as active (as the screens list it)")
	void activeNotSetCounts() {
		function(1L, "Client", null);
		assertThrows(IllegalArgumentException.class, () -> service.createMember(request("29954290", null)));
	}

	@Test
	@DisplayName("No function at all: the member is created and updated without one")
	void noFunctionAtAll() {
		LoyaltyMemberDTO created = service.createMember(request("29954290", null));
		assertNull(members.get(created.getId()).getMemberFunction());
		assertNull(created.getMemberFunctionId());
		LoyaltyMemberDTO updated = service.updateMember(created.getId(), request("29954291", null));
		assertEquals("29954291", updated.getPhone());
		assertNull(members.get(created.getId()).getMemberFunction());
	}

	@Test
	@DisplayName("Only inactive functions: as none (saved without one); an update keeps the function the member had")
	void onlyInactiveFunctions() {
		MemberFunction old = function(2L, "Old", false);
		LoyaltyMemberDTO created = service.createMember(request("29954290", null));
		assertNull(members.get(created.getId()).getMemberFunction());

		LoyaltyMember withOld = members.get(created.getId());
		withOld.setMemberFunction(old);
		service.updateMember(withOld.getId(), request("29954290", null));
		assertSame(old, members.get(withOld.getId()).getMemberFunction(), "kept, not wiped");
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
