package com.digithink.zsretail.headoffice.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.dto.HeadOfficeHeartbeatDTO;
import com.digithink.zsretail.headoffice.dto.HeadOfficePingDTO;
import com.digithink.zsretail.headoffice.enumeration.StoreKind;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.headoffice.service.StoreService;
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
							// Same effect as the JPQL update: these two columns of the row with this id
							contactUpdates.add(args);
							if (!row.getId().equals(args[0])) {
								return 0;
							}
							row.setLastContact((LocalDateTime) args[1]);
							row.setAppVersion((String) args[2]);
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
		assertEquals(new TreeSet<>(Arrays.asList("storeCode", "serverTime")), new TreeSet<>(new JSONObject(json).keySet()));
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
}
