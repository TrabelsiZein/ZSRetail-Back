package com.digithink.zsretail.headoffice.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatAnswerDTO;
import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatDTO;
import com.digithink.zsretail.headoffice.dto.HeadOfficePingDTO;
import com.digithink.zsretail.headoffice.enumeration.StoreKind;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.headoffice.service.StoreService;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.service.GeneralSetupService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 1.4: POST /ho/heartbeat writes lastContact (head office clock) and appVersion by id, through
 * the repository's two-column update, never by saving the detached principal; the hash and the other fields stay as
 * they are. Blank version gives null, a long one is cut. Answers like GET /ho/ping. Plain JUnit with an in-memory
 * repository (no Spring context).
 */
class HeadOfficeHeartbeatAPITest {

	private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
	private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 10, 1, 8, 0);

	/** The ho_store row, as in the database. */
	private Store row;
	private int saves;
	private final List<Object[]> contactUpdates = new ArrayList<>();
	private HeadOfficeHeartbeatAPI api;

	@BeforeEach
	void setUp() throws Exception {
		row = new Store();
		row.setId(1L);
		row.setCode("RS01");
		row.setName("Store Sousse");
		row.setKind(StoreKind.FRANCHISE);
		row.setActive(true);
		row.setApiKeyHash(HASH);
		row.setUpdatedAt(UPDATED_AT);

		StoreRepository repository = (StoreRepository) Proxy.newProxyInstance(StoreRepository.class.getClassLoader(),
				new Class<?>[] { StoreRepository.class }, (proxy, method, args) -> {
					switch (method.getName()) {
						case "findById":
							return Optional.ofNullable(row.getId().equals(args[0]) ? row : null);
						case "save":
							saves++;
							return args[0];
						case "updateContact":
							// Same effect as the JPQL update: these columns of the row with this id (task 3.6: and the owners)
							contactUpdates.add(args);
							if (!row.getId().equals(args[0])) {
								return 0;
							}
							row.setLastContact((LocalDateTime) args[1]);
							row.setAppVersion((String) args[2]);
							row.setOwnerCatalogue((String) args[3]);
							row.setOwnerCustomers((String) args[4]);
							row.setOwnerPromotions((String) args[5]);
							row.setOwnerLoyalty((String) args[6]);
							row.setOwnerSupply((String) args[7]);
							row.setReportedSalesUpstreams((String) args[8]);
							return 1;
						default:
							throw new UnsupportedOperationException("Unexpected call: StoreRepository." + method.getName());
					}
				});
		StoreService service = new StoreService();
		Field field = StoreService.class.getDeclaredField("storeRepository");
		field.setAccessible(true);
		field.set(service, repository);
		api = new HeadOfficeHeartbeatAPI(service);
	}

	/** The principal set by the filter: a detached copy, here with in-memory changes that must not reach the row. */
	private Store principal() {
		Store store = new Store();
		store.setId(1L);
		store.setCode("RS01");
		store.setName("Changed in memory");
		store.setKind(StoreKind.OWN);
		store.setActive(false);
		store.setApiKeyHash("ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
		return store;
	}

	private String beat(String version) {
		api.heartbeat(principal(), new HeadOfficeHeartbeatDTO(version));
		return row.getAppVersion();
	}

	@Test
	@DisplayName("Step 4: the answer carries the store's loyalty rights (null in an older row: false); nothing else changes")
	void answerCarriesLoyaltyRights() throws Exception {
		HeadOfficeHeartbeatAnswerDTO none = api.heartbeat(principal(), new HeadOfficeHeartbeatDTO("2.1.0"));
		assertEquals(Boolean.FALSE, none.getCanEditMembers());
		assertEquals(Boolean.FALSE, none.getCanAdjustPoints());
		Store allowed = principal();
		allowed.setCanEditMembers(true);
		HeadOfficeHeartbeatAnswerDTO answer = api.heartbeat(allowed, new HeadOfficeHeartbeatDTO("2.1.0"));
		assertEquals(Boolean.TRUE, answer.getCanEditMembers());
		assertEquals(Boolean.FALSE, answer.getCanAdjustPoints());
		assertEquals(Boolean.FALSE, answer.getRedeemRequiresOnline(), "step 5: null in the row is false");
		allowed.setRedeemRequiresOnline(true);
		assertEquals(Boolean.TRUE, api.heartbeat(allowed, new HeadOfficeHeartbeatDTO("2.1.0")).getRedeemRequiresOnline());
		assertEquals(Boolean.FALSE, answer.getEnrolRequiresOnline(), "enrol switch: null in the row is false");
		allowed.setEnrolRequiresOnline(true);
		assertEquals(Boolean.TRUE, api.heartbeat(allowed, new HeadOfficeHeartbeatDTO("2.1.0")).getEnrolRequiresOnline());
		assertEquals(Boolean.FALSE, answer.getMayChangePrices(), "step 6: null in the row is false");
		assertEquals(Boolean.FALSE, answer.getCanPurchase(), "step 6: null in the row is false");
		allowed.setMayChangePrices(true);
		allowed.setCanPurchase(true);
		HeadOfficeHeartbeatAnswerDTO catalogue = api.heartbeat(allowed, new HeadOfficeHeartbeatDTO("2.1.0"));
		assertEquals(Boolean.TRUE, catalogue.getMayChangePrices());
		assertEquals(Boolean.TRUE, catalogue.getCanPurchase());
		assertEquals("RS01", answer.getStoreCode());
		String json = new ObjectMapper().writeValueAsString(answer);
		assertTrue(json.contains("\"storeCode\":\"RS01\"") && json.contains("\"serverTime\":")
				&& json.contains("\"canEditMembers\":true"), json);
		// A store of an older version reads the ping fields only
		HeadOfficePingDTO old = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
				.readValue(json, HeadOfficePingDTO.class);
		assertEquals("RS01", old.getStoreCode());
		assertEquals(0, saves);
	}

	@Test
	@DisplayName("Heartbeat: lastContact and appVersion written by id; hash, code, name, kind, active, updatedAt unchanged; nothing saved")
	void recordsContactById() throws Exception {
		OffsetDateTime before = OffsetDateTime.now();
		HeadOfficePingDTO answer = api.heartbeat(principal(), new HeadOfficeHeartbeatDTO("1.12.0"));

		assertEquals(1, contactUpdates.size());
		assertEquals(1L, contactUpdates.get(0)[0]);
		assertEquals("1.12.0", row.getAppVersion());
		assertNotNull(row.getLastContact());
		assertTrue(Duration.between(before.toLocalDateTime(), row.getLastContact()).abs().getSeconds() < 5);

		assertEquals(HASH, row.getApiKeyHash());
		assertEquals("RS01", row.getCode());
		assertEquals("Store Sousse", row.getName());
		assertEquals(StoreKind.FRANCHISE, row.getKind());
		assertEquals(Boolean.TRUE, row.getActive());
		assertEquals(UPDATED_AT, row.getUpdatedAt());
		assertEquals(0, saves, "the principal or the row must never be saved");

		assertEquals("RS01", answer.getStoreCode());
		assertTrue(answer.getServerTime().matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}([+-]\\d{2}:\\d{2}|Z)"),
				answer.getServerTime());
		assertEquals(OffsetDateTime.parse(answer.getServerTime()).toLocalDateTime(),
				row.getLastContact().truncatedTo(ChronoUnit.MILLIS), "lastContact and serverTime are the same instant");
		String json = new ObjectMapper().writeValueAsString(answer);
		// Step 4: the store's loyalty rights travel with the answer (decided); GET /ho/ping keeps its two fields
		assertEquals(new TreeSet<>(Arrays.asList("storeCode", "serverTime", "canEditMembers", "canAdjustPoints",
				"redeemRequiresOnline", "enrolRequiresOnline", "mayChangePrices", "canPurchase")),
				new TreeSet<>(new JSONObject(json).keySet()));
	}

	@Test
	@DisplayName("Version: trimmed; blank, empty, null or no body gives null; longer than the column is cut")
	void versionRules() {
		assertEquals("1.12.0", beat("  1.12.0 "));
		assertNull(beat("   "));
		assertNull(beat(""));
		assertNull(beat(null));

		row.setAppVersion("1.12.0");
		api.heartbeat(principal(), null);
		assertNull(row.getAppVersion());

		StringBuilder longVersion = new StringBuilder();
		while (longVersion.length() < Store.APP_VERSION_LENGTH + 45) {
			longVersion.append("1.12.0-");
		}
		String cut = beat(longVersion.toString());
		assertEquals(Store.APP_VERSION_LENGTH, cut.length());
		assertEquals(longVersion.substring(0, Store.APP_VERSION_LENGTH), cut);

		String exact = longVersion.substring(0, Store.APP_VERSION_LENGTH);
		assertEquals(exact, beat(exact));
		assertEquals(0, saves);
	}

	// ─── Task 3.6: what the store owns ────────────────────────────

	private static Map<String, String> owners(String... pairs) {
		Map<String, String> owners = new LinkedHashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			owners.put(pairs[i], pairs[i + 1]);
		}
		return owners;
	}

	@Test
	@DisplayName("Task 3.6: owners and sales upstreams saved in the same update; values read leniently; JSON ownership and salesUpstreams")
	void ownershipReported() throws Exception {
		api.heartbeat(principal(), new HeadOfficeHeartbeatDTO("2.1.0",
				owners("CATALOGUE", "ERP", " customers ", "erp", "PROMOTIONS", "HEAD_OFFICE", "LOYALTY", "ERP",
						"SUPPLY", "bogus", "SHOES", "LOCAL"),
				Arrays.asList(" head_office ", "X", "ERP", "ERP")));
		assertEquals(1, contactUpdates.size(), "one update with the contact");
		assertEquals("ERP", row.getOwnerCatalogue());
		assertEquals("ERP", row.getOwnerCustomers());
		assertEquals("HEAD_OFFICE", row.getOwnerPromotions());
		assertNull(row.getOwnerLoyalty(), "ERP is not an owner loyalty allows: unknown");
		assertNull(row.getOwnerSupply(), "unreadable: unknown");
		assertEquals("ERP,HEAD_OFFICE", row.getReportedSalesUpstreams(), "known names, enum order, once");
		assertEquals(0, saves);

		JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(row);
		assertEquals("{\"CATALOGUE\":\"ERP\",\"CUSTOMERS\":\"ERP\",\"PROMOTIONS\":\"HEAD_OFFICE\",\"LOYALTY\":null,"
				+ "\"SUPPLY\":null}", json.get("ownership").toString());
		assertEquals("[\"ERP\",\"HEAD_OFFICE\"]", json.get("salesUpstreams").toString());
		assertFalse(json.has("ownerPromotions") || json.has("reportedSalesUpstreams"), json.toString());

		Store read = new ObjectMapper().findAndRegisterModules().readValue(json.toString(), Store.class);
		assertNull(read.getOwnerPromotions(), "never read from a client");

		api.heartbeat(principal(), new HeadOfficeHeartbeatDTO("2.1.0", owners("PROMOTIONS", "LOCAL"),
				Collections.emptyList()));
		assertEquals("LOCAL", row.getOwnerPromotions());
		assertNull(row.getOwnerCatalogue());
		assertEquals("", row.getReportedSalesUpstreams());
		assertEquals("[]", new ObjectMapper().findAndRegisterModules().valueToTree(row).get("salesUpstreams").toString(),
				"sales go nowhere");
	}

	@Test
	@DisplayName("Task 3.6: a store that sends nothing (older version, or no body) is unknown, also after an earlier report")
	void olderStoreUnknown() {
		api.heartbeat(principal(), new HeadOfficeHeartbeatDTO("2.1.0", owners("PROMOTIONS", "HEAD_OFFICE"),
				Collections.singletonList("HEAD_OFFICE")));
		api.heartbeat(principal(), new HeadOfficeHeartbeatDTO("1.12.0"));
		assertNull(row.getOwnership());
		assertNull(row.getSalesUpstreams());
		JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(row);
		assertTrue(json.get("ownership").isNull());
		assertTrue(json.get("salesUpstreams").isNull());
		api.heartbeat(principal(), null);
		assertNull(row.getOwnership());
		assertEquals("1.12.0", beat("1.12.0"), "the version rules are unchanged");
	}

	@Test
	@DisplayName("Task 3.6: round trip from the store's client: owners and upstreams as GET /config gives them; no mode, version only")
	void fromTheStoreClient() throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		MockEnvironment env = new MockEnvironment().withProperty("headoffice.url", "http://ho:888/zsretail/api")
				.withProperty("headoffice.api-key", "k").withProperty("ownership.promotions", "HEAD_OFFICE")
				.withProperty("sales.upstream", "HEAD_OFFICE");
		inject(mode, "environment", env);
		env.setProperty("application.standalone", String.valueOf(true)); // step 9: read from the environment, no field
		Method init = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		init.setAccessible(true);
		init.invoke(mode);
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return "RS01";
			}
		};
		List<String> bodies = new ArrayList<>();
		for (ApplicationModeService withMode : Arrays.asList(mode, null)) {
			RestTemplate restTemplate = new RestTemplate();
			MockRestServiceServer server = MockRestServiceServer.bindTo(restTemplate).build();
			server.expect(requestTo("http://ho:888/zsretail/api/ho/heartbeat")).andRespond(request -> {
				bodies.add(((MockClientHttpRequest) request).getBodyAsString());
				return withSuccess("{\"storeCode\":\"RS01\",\"serverTime\":\"2026-10-03T12:00:00.000Z\"}",
						MediaType.APPLICATION_JSON).createResponse(request);
			});
			new HeadOfficeClient(restTemplate, setup, "http://ho:888/zsretail/api", "k", "2.1.0", withMode).heartbeat();
			server.verify();
		}
		assertEquals("{\"appVersion\":\"2.1.0\",\"ownership\":{\"CATALOGUE\":\"LOCAL\",\"CUSTOMERS\":\"LOCAL\","
				+ "\"PROMOTIONS\":\"HEAD_OFFICE\",\"LOYALTY\":\"LOCAL\",\"SUPPLY\":\"LOCAL\"},\"salesUpstreams\":[\"HEAD_OFFICE\"]}",
				bodies.get(0));
		assertEquals("{\"appVersion\":\"2.1.0\"}", bodies.get(1), "as before task 3.6");

		api.heartbeat(principal(), new ObjectMapper().readValue(bodies.get(0), HeadOfficeHeartbeatDTO.class));
		assertEquals("HEAD_OFFICE", row.getOwnerPromotions());
		assertEquals("LOCAL", row.getOwnerCatalogue());
		assertEquals("HEAD_OFFICE", row.getReportedSalesUpstreams());
	}

	private static void inject(Object target, String field, Object value) throws Exception {
		Field f = ApplicationModeService.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}
}
