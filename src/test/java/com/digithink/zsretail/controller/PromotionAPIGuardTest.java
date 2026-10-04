package com.digithink.zsretail.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.model.enumeration.PromotionScope;
import com.digithink.zsretail.model.enumeration.PromotionType;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.PromotionRepository;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.PromotionService;
import com.digithink.zsretail.service._BaseService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 3.2 (with Zein's correction of 2026-10-03): on a store whose promotions are owned by the head
 * office, every promotion is only consulted (create, edit, deactivate, delete: 409, whatever its origin). Elsewhere (rule
 * fix of step 3) the store owns every promotion of its table: one that came from the head office is written like a
 * local one and keeps its origin; the client never sets the origin, and local promotions behave as before (usage lock,
 * delete refusal). Real PromotionAPI and PromotionService over an in-memory promotion table; no Spring context.
 */
class PromotionAPIGuardTest {

	private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

	/** The promotion table, by id. */
	private final Map<Long, Promotion> table = new LinkedHashMap<>();

	/** Sales lines + headers that reference each promotion. */
	private final Map<Long, Long> usages = new HashMap<>();

	private long nextId;

	@BeforeEach
	void setUp() {
		table.clear();
		usages.clear();
		nextId = 1;
		table.put(1L, promotion(1L, "LOCAL1", null));
		table.put(2L, promotion(2L, "HO1", RecordOrigin.HEAD_OFFICE));
		table.put(3L, promotion(3L, "LOCAL2", RecordOrigin.LOCAL));
		nextId = 4;
	}

	private static Promotion promotion(Long id, String code, RecordOrigin origin) {
		Promotion p = new Promotion();
		p.setId(id);
		p.setCode(code);
		p.setName("Promo " + code);
		p.setPromotionType(PromotionType.SIMPLE_DISCOUNT);
		p.setScope(PromotionScope.CART);
		p.setBenefitType(PromotionBenefitType.PERCENTAGE_DISCOUNT);
		p.setDiscountPercentage(10.0);
		p.setOrigin(origin);
		return p;
	}

	/** A copy, as a request body would arrive. */
	private Promotion body(Long id, String code) {
		Promotion p = promotion(id, code, null);
		p.setName("Edited " + code);
		return p;
	}

	private Map<Long, String> snapshot() {
		Map<Long, String> rows = new LinkedHashMap<>();
		table.forEach((id, p) -> rows.put(id, p.getCode() + "|" + p.getName() + "|" + p.getActive() + "|" + p.getOrigin()));
		return rows;
	}

	@Test
	@DisplayName("Promotions owned by the head office: create, edit, deactivate and delete refused for every origin; nothing written")
	void ownedByHeadOffice() throws Exception {
		PromotionAPI api = api(owned());
		Map<Long, String> before = snapshot();

		assertConflict(api.create(body(null, "NEW")), PromotionAPI.OWNED_BY_HEAD_OFFICE);
		for (long id = 1; id <= 3; id++) {
			Promotion deactivate = body(id, table.get(id).getCode());
			deactivate.setActive(false);
			assertConflict(api.update(id, deactivate), PromotionAPI.OWNED_BY_HEAD_OFFICE);
			assertConflict(api.deleteById(id), PromotionAPI.OWNED_BY_HEAD_OFFICE);
		}
		assertEquals(before, snapshot());
		assertEquals(404, api.update(99L, body(99L, "X")).getStatusCodeValue());

		assertEquals(200, api.getById(2L).getStatusCodeValue(), "consulting stays allowed");
		assertEquals(200, api.getAllPaginated(0, 20, null).getStatusCodeValue());
	}

	@Test
	@DisplayName("Promotions local (rule fix): a promotion that came from the head office is edited, deactivated and deleted like a local one; origin kept")
	void headOfficeRecordOnLocalStore() throws Exception {
		PromotionAPI api = api(local());

		assertEquals(200, api.update(2L, body(2L, "HO1")).getStatusCodeValue());
		assertEquals("Edited HO1", table.get(2L).getName());
		assertEquals(RecordOrigin.HEAD_OFFICE, table.get(2L).getOrigin(), "origin kept, for the badge");

		Promotion deactivate = body(2L, "HO1");
		deactivate.setActive(false);
		assertEquals(200, api.update(2L, deactivate).getStatusCodeValue());
		assertFalse(table.get(2L).getActive());

		Promotion again = body(2L, "HO1");
		again.setName("Through the generic create");
		assertEquals(201, api.create(again).getStatusCodeValue());
		assertEquals(RecordOrigin.HEAD_OFFICE, table.get(2L).getOrigin(), "a create with its id keeps its origin");

		usages.put(2L, 1L);
		Promotion locked = body(2L, "HO1");
		locked.setDiscountPercentage(70.0);
		assertEquals(409, api.update(2L, locked).getStatusCodeValue(), "the usage lock applies as to a local one");
		usages.remove(2L);

		assertEquals(204, api.deleteById(2L).getStatusCodeValue());
		assertFalse(table.containsKey(2L));
	}

	@Test
	@DisplayName("Promotions local: local promotions as before; the origin is never taken from the request")
	void localUnchanged() throws Exception {
		PromotionAPI api = api(local());

		Promotion created = mapper.readValue("{\"code\":\"NEW\",\"name\":\"New\",\"promotionType\":\"SIMPLE_DISCOUNT\","
				+ "\"scope\":\"CART\",\"benefitType\":\"PERCENTAGE_DISCOUNT\",\"discountPercentage\":5,"
				+ "\"origin\":\"HEAD_OFFICE\"}", Promotion.class);
		assertNull(created.getOrigin(), "origin is read-only in JSON");
		ResponseEntity<?> answer = api.create(created);
		assertEquals(201, answer.getStatusCodeValue());
		assertNull(((Promotion) answer.getBody()).getOrigin());
		assertNull(table.get(4L).getOrigin());

		Promotion sneaky = body(1L, "LOCAL1");
		sneaky.setOrigin(RecordOrigin.HEAD_OFFICE); // set in Java, not reachable from JSON
		assertEquals(200, api.update(1L, sneaky).getStatusCodeValue());
		assertNull(table.get(1L).getOrigin(), "a local promotion stays local");
		assertEquals("Edited LOCAL1", table.get(1L).getName());

		Promotion deactivate = body(3L, "LOCAL2");
		deactivate.setActive(false);
		assertEquals(200, api.update(3L, deactivate).getStatusCodeValue());
		assertFalse(table.get(3L).getActive());
		assertEquals(RecordOrigin.LOCAL, table.get(3L).getOrigin());

		usages.put(1L, 2L);
		Promotion locked = body(1L, "LOCAL1");
		locked.setDiscountPercentage(50.0);
		ResponseEntity<?> refused = api.update(1L, locked);
		assertEquals(409, refused.getStatusCodeValue());
		assertTrue(refused.getBody().toString().startsWith("Promotion has been used in 2 sale(s). Cannot modify"));
		ResponseEntity<?> deleteUsed = api.deleteById(1L);
		assertEquals(409, deleteUsed.getStatusCodeValue());
		assertEquals("This promotion has been used in 2 sale(s) and cannot be deleted. Deactivate it instead.",
				deleteUsed.getBody());

		assertEquals(204, api.deleteById(3L).getStatusCodeValue());
		assertFalse(table.containsKey(3L));
	}

	@Test
	@DisplayName("JSON: origin is sent (HEAD_OFFICE, LOCAL or null), never read back")
	void json() throws Exception {
		assertEquals("HEAD_OFFICE", mapper.valueToTree(table.get(2L)).get("origin").asText());
		JsonNode local = mapper.valueToTree(table.get(1L));
		assertTrue(local.has("origin") && local.get("origin").isNull());
		Promotion read = mapper.readValue(mapper.writeValueAsString(table.get(2L)), Promotion.class);
		assertNull(read.getOrigin());
	}

	@Test
	@DisplayName("isPromotionsOwnedByHeadOffice: only with headoffice.url and ownership.promotions=HEAD_OFFICE")
	void ownership() throws Exception {
		assertTrue(owned().isPromotionsOwnedByHeadOffice());
		assertFalse(local().isPromotionsOwnedByHeadOffice());
		assertFalse(mode(new MockEnvironment(), false).isPromotionsOwnedByHeadOffice(), "ERP");
		assertFalse(mode(link(), true).isPromotionsOwnedByHeadOffice(), "linked, promotions local");
	}

	// ─── Wiring ───────────────────────────────────────────────────

	private static void assertConflict(ResponseEntity<?> answer, String message) {
		assertEquals(409, answer.getStatusCodeValue());
		assertEquals(message, answer.getBody());
	}

	private static MockEnvironment link() {
		return new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
	}

	private static ApplicationModeService owned() throws Exception {
		return mode(link().withProperty("ownership.promotions", "HEAD_OFFICE"), true);
	}

	private static ApplicationModeService local() throws Exception {
		return mode(new MockEnvironment(), true);
	}

	private static ApplicationModeService mode(MockEnvironment env, boolean standalone) throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, ApplicationModeService.class, "environment", env);
		env.setProperty("application.standalone", String.valueOf(standalone)); // step 9: read from the environment, no field
		Method init = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		init.setAccessible(true);
		init.invoke(mode);
		return mode;
	}

	private PromotionAPI api(ApplicationModeService mode) throws Exception {
		PromotionService service = new PromotionService();
		inject(service, PromotionService.class, "promotionRepository", repository());
		inject(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}
		});
		PromotionAPI api = new PromotionAPI();
		inject(api, _BaseController.class, "service", service);
		inject(api, PromotionAPI.class, "promotionService", service);
		inject(api, PromotionAPI.class, "applicationModeService", mode);
		return api;
	}

	private PromotionRepository repository() {
		return (PromotionRepository) Proxy.newProxyInstance(PromotionRepository.class.getClassLoader(),
				new Class<?>[] { PromotionRepository.class }, (proxy, method, args) -> {
					switch (method.getName()) {
						case "findById":
							Promotion found = table.get(args[0]);
							return Optional.ofNullable(found == null ? null : copy(found));
						case "save":
							Promotion saved = (Promotion) args[0];
							if (saved.getId() == null) {
								saved.setId(nextId++);
							}
							table.put(saved.getId(), saved);
							return saved;
						case "deleteById":
							table.remove(args[0]);
							return null;
						case "countUsages":
							return usages.getOrDefault(args[0], 0L);
						case "findAll":
							return new org.springframework.data.domain.PageImpl<>(new java.util.ArrayList<>(table.values()));
						default:
							throw new UnsupportedOperationException(method.getName());
					}
				});
	}

	/** A detached copy, like a fresh read: the API never edits the stored row in place. */
	private Promotion copy(Promotion p) {
		try {
			Promotion copy = mapper.readValue(mapper.writeValueAsString(p), Promotion.class);
			copy.setOrigin(p.getOrigin());
			return copy;
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
