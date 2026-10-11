package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.dto.StoreListItemDTO;
import com.digithink.zsretail.headoffice.enumeration.StoreKind;
import com.digithink.zsretail.headoffice.enumeration.StoreStatus;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 1.5: the status of a store on the Stores page, computed with the head office clock
 * (INACTIVE, NEVER, ONLINE up to and including headoffice.offline-after-seconds, OFFLINE after), and the list item:
 * the store's JSON unchanged plus status and secondsSinceContact, never the key hash. Plain JUnit with an in-memory
 * repository (no Spring context).
 */
class StoreStatusTest {

	private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 3, 12, 0, 0);
	private static final String HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

	private final Map<Long, Store> table = new LinkedHashMap<>();
	private StoreService service;

	@BeforeEach
	void setUp() throws Exception {
		StoreRepository repository = (StoreRepository) Proxy.newProxyInstance(StoreRepository.class.getClassLoader(),
				new Class<?>[] { StoreRepository.class }, (proxy, method, args) -> {
					switch (method.getName()) {
						case "findAllByOrderByUpdatedAtDesc":
							return new ArrayList<>(table.values());
						case "findById":
							return Optional.ofNullable(table.get(args[0]));
						default:
							throw new UnsupportedOperationException("Unexpected call: StoreRepository." + method.getName());
					}
				});
		service = new StoreService();
		Field field = StoreService.class.getDeclaredField("storeRepository");
		field.setAccessible(true);
		field.set(service, repository);
	}

	private static Store store(long id, String code, boolean active, LocalDateTime lastContact) {
		Store store = new Store();
		store.setId(id);
		store.setCode(code);
		store.setName("Store " + code);
		store.setKind(StoreKind.OWN);
		store.setActive(active);
		store.setLastContact(lastContact);
		store.setAppVersion(lastContact == null ? null : "1.12.0");
		store.setApiKeyHash(HASH);
		return store;
	}

	private static StoreStatus status(boolean active, LocalDateTime lastContact) {
		return StoreService.statusOf(store(1, "RS01", active, lastContact), NOW, 180);
	}

	@Test
	@DisplayName("Inactive store: INACTIVE, with or without a recent contact")
	void inactive() {
		assertEquals(StoreStatus.INACTIVE, status(false, NOW.minusSeconds(5)));
		assertEquals(StoreStatus.INACTIVE, status(false, null));
		Store noFlag = store(1, "RS01", true, NOW);
		noFlag.setActive(null);
		assertEquals(StoreStatus.INACTIVE, StoreService.statusOf(noFlag, NOW, 180));
	}

	@Test
	@DisplayName("Active store without a contact: NEVER")
	void never() {
		assertEquals(StoreStatus.NEVER, status(true, null));
	}

	@Test
	@DisplayName("Active store: ONLINE up to and including the threshold, OFFLINE after it")
	void onlineOffline() {
		assertEquals(StoreStatus.ONLINE, status(true, NOW));
		assertEquals(StoreStatus.ONLINE, status(true, NOW.minusSeconds(60)));
		assertEquals(StoreStatus.ONLINE, status(true, NOW.minusSeconds(180)), "exactly at the threshold");
		assertEquals(StoreStatus.OFFLINE, status(true, NOW.minusSeconds(180).minusNanos(1_000_000)), "1 ms older");
		assertEquals(StoreStatus.OFFLINE, status(true, NOW.minusSeconds(181)));
		assertEquals(StoreStatus.OFFLINE, status(true, NOW.minusDays(2)));
		assertEquals(StoreStatus.ONLINE, status(true, NOW.plusSeconds(10)), "head office clock moved back");
		assertEquals(StoreStatus.OFFLINE, StoreService.statusOf(store(1, "RS01", true, NOW.minusSeconds(31)), NOW, 30));
	}

	@Test
	@DisplayName("List: status and secondsSinceContact per store, the store JSON unchanged, never the key hash")
	void listItems() throws Exception {
		LocalDateTime now = LocalDateTime.now();
		table.put(1L, store(1, "RS01", true, now.minusSeconds(42)));
		table.put(2L, store(2, "RS02", true, now.minusMinutes(10)));
		table.put(3L, store(3, "TUNIS1", true, null));
		table.put(4L, store(4, "RS04", false, now.minusSeconds(5)));

		List<StoreListItemDTO> items = service.findAllWithStatus();
		assertEquals(4, items.size());
		assertEquals(StoreStatus.ONLINE, items.get(0).getStatus());
		assertTrue(items.get(0).getSecondsSinceContact() >= 42 && items.get(0).getSecondsSinceContact() < 47,
				String.valueOf(items.get(0).getSecondsSinceContact()));
		assertEquals(StoreStatus.OFFLINE, items.get(1).getStatus(), "default threshold 540 s (2.2.2)");
		assertEquals(StoreStatus.NEVER, items.get(2).getStatus());
		assertNull(items.get(2).getSecondsSinceContact());
		assertEquals(StoreStatus.INACTIVE, items.get(3).getStatus());

		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		for (StoreListItemDTO item : items) {
			Set<String> expected = new TreeSet<>();
			mapper.valueToTree(item.getStore()).fieldNames().forEachRemaining(expected::add);
			expected.add("status");
			expected.add("secondsSinceContact");
			JsonNode json = mapper.valueToTree(item);
			Set<String> keys = new TreeSet<>();
			json.fieldNames().forEachRemaining(keys::add);
			assertEquals(expected, keys);
			assertTrue(keys.containsAll(java.util.Arrays.asList("id", "code", "name", "kind", "active", "lastContact",
					"appVersion")), keys.toString());
			assertFalse(json.toString().contains("apiKeyHash"), json.toString());
			assertFalse(json.toString().contains(HASH), json.toString());
		}
		assertEquals("\"ONLINE\"", mapper.valueToTree(items.get(0)).get("status").toString());
	}

	@Test
	@DisplayName("headoffice.offline-after-seconds is applied; read by id like the list")
	void thresholdAndReadById() throws Exception {
		Field threshold = StoreService.class.getDeclaredField("offlineAfterSeconds");
		threshold.setAccessible(true);
		threshold.set(service, 30L);
		table.put(1L, store(1, "RS01", true, LocalDateTime.now().minusSeconds(45)));

		assertEquals(StoreStatus.OFFLINE, service.findByIdWithStatus(1L).get().getStatus());
		assertFalse(service.findByIdWithStatus(9L).isPresent());
		threshold.set(service, 180L);
		assertEquals(StoreStatus.ONLINE, service.findByIdWithStatus(1L).get().getStatus());
	}
}
