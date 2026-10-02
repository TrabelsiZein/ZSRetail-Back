package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.dto.StoreWithKeyDTO;
import com.digithink.zsretail.headoffice.enumeration.StoreKind;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service._BaseService;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 1.2: the stores list. Server-generated key stored as a hash, the key check for task 1.3,
 * the code rules, the fields the client cannot write, the delete rule. Plain JUnit with an in-memory repository
 * (no Spring context).
 */
class StoreServiceTest {

	/** In-memory ho_store, by id. */
	private final Map<Long, Store> table = new LinkedHashMap<>();
	private StoreService service;

	@BeforeEach
	void setUp() throws Exception {
		StoreRepository repository = stub(StoreRepository.class, (method, args) -> {
			switch (method) {
				case "findByCodeIgnoreCase":
					return table.values().stream().filter(s -> s.getCode().equalsIgnoreCase((String) args[0])).findFirst();
				case "findById":
					return Optional.ofNullable(table.get(args[0]));
				case "save":
					Store store = (Store) args[0];
					if (store.getId() == null) {
						store.setId((long) table.size() + 1);
					}
					table.put(store.getId(), store);
					return store;
				case "deleteById":
					table.remove(args[0]);
					return null;
				default:
					return UNHANDLED;
			}
		});
		service = new StoreService();
		inject(service, StoreService.class, "storeRepository", repository);
		inject(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}
		});
	}

	private static Store input(String code, String name) {
		Store store = new Store();
		store.setCode(code);
		store.setName(name);
		return store;
	}

	// --- Create and key ---

	@Test
	@DisplayName("Create generates a 43-character URL-safe key and stores only its SHA-256 hash")
	void createStoresOnlyTheHash() throws Exception {
		StoreWithKeyDTO created = service.create(input("RS01", "Store Sousse"));
		String key = created.getApiKey();

		assertTrue(key.matches("[A-Za-z0-9_-]{43}"), key);
		Store saved = table.get(created.getStore().getId());
		assertEquals(StoreService.sha256Hex(key), saved.getApiKeyHash());
		assertNotEquals(key, saved.getApiKeyHash());
		assertFalse(saved.toString().contains(key), "toString shows the key");
		assertFalse(saved.toString().contains(saved.getApiKeyHash()), "toString shows the hash");
		assertFalse(created.toString().contains(key), "DTO toString shows the key");
		assertNotEquals(key, service.create(input("RS02", "Store Sfax")).getApiKey(), "keys must differ");
	}

	@Test
	@DisplayName("Create: code trimmed and uppercase, kind OWN and active by default")
	void createDefaults() throws Exception {
		Store store = service.create(input("  rs01 ", " Store Sousse ")).getStore();
		assertEquals("RS01", store.getCode());
		assertEquals("Store Sousse", store.getName());
		assertEquals(StoreKind.OWN, store.getKind());
		assertTrue(store.getActive());
		assertNull(store.getLastContact());
		assertNull(store.getAppVersion());
	}

	@Test
	@DisplayName("Create ignores lastContact, appVersion and a hash sent by the client")
	void createIgnoresClientFields() throws Exception {
		Store input = input("RS01", "Store Sousse");
		input.setLastContact(LocalDateTime.now());
		input.setAppVersion("9.9.9");
		input.setApiKeyHash("client-hash");
		input.setKind(StoreKind.FRANCHISE);

		StoreWithKeyDTO created = service.create(input);
		Store saved = table.get(created.getStore().getId());
		assertNull(saved.getLastContact());
		assertNull(saved.getAppVersion());
		assertEquals(StoreService.sha256Hex(created.getApiKey()), saved.getApiKeyHash());
		assertEquals(StoreKind.FRANCHISE, saved.getKind());
	}

	@Test
	@DisplayName("Create needs a code and a name; a duplicate code is refused (trimmed, case-insensitive)")
	void createValidation() throws Exception {
		assertEquals(StoreService.CODE_REQUIRED,
				assertThrows(IllegalArgumentException.class, () -> service.create(input("  ", "Name"))).getMessage());
		assertEquals(StoreService.NAME_REQUIRED,
				assertThrows(IllegalArgumentException.class, () -> service.create(input("RS01", null))).getMessage());
		service.create(input("RS01", "Store Sousse"));
		IllegalStateException duplicate = assertThrows(IllegalStateException.class,
				() -> service.create(input(" rs01 ", "Other")));
		assertTrue(duplicate.getMessage().contains("RS01"), duplicate.getMessage());
		assertEquals(1, table.size());
	}

	// --- Key check (task 1.3) ---

	@Test
	@DisplayName("Key check: good key, bad key, inactive store, unknown code, missing key")
	void authenticate() throws Exception {
		String key = service.create(input("RS01", "Store Sousse")).getApiKey();

		assertTrue(service.authenticate("RS01", key).isPresent(), "good key");
		assertTrue(service.authenticate(" rs01 ", key).isPresent(), "code trimmed, case-insensitive");
		assertFalse(service.authenticate("RS01", key + "x").isPresent(), "bad key");
		assertFalse(service.authenticate("RS01", null).isPresent(), "missing key");
		assertFalse(service.authenticate("RS99", key).isPresent(), "unknown code");
		assertFalse(service.authenticate(null, key).isPresent(), "missing code");

		table.values().iterator().next().setActive(false);
		assertFalse(service.authenticate("RS01", key).isPresent(), "inactive store");
	}

	@Test
	@DisplayName("Regenerate: a new key works, the old one stops working")
	void regenerateKey() throws Exception {
		StoreWithKeyDTO created = service.create(input("RS01", "Store Sousse"));
		String oldKey = created.getApiKey();

		String newKey = service.regenerateKey(created.getStore().getId()).get().getApiKey();

		assertNotEquals(oldKey, newKey);
		assertTrue(service.authenticate("RS01", newKey).isPresent());
		assertFalse(service.authenticate("RS01", oldKey).isPresent());
		assertFalse(service.regenerateKey(99L).isPresent());
	}

	// --- Update ---

	@Test
	@DisplayName("Update refuses a different code; the same code in another case or spacing is accepted")
	void codeCannotChange() throws Exception {
		Long id = service.create(input("RS01", "Store Sousse")).getStore().getId();

		assertEquals(StoreService.CODE_IS_FINAL,
				assertThrows(IllegalArgumentException.class, () -> service.update(id, input("RS02", "X"))).getMessage());
		assertEquals("RS01", table.get(id).getCode());
		assertEquals("Store Sousse 2", service.update(id, input(" rs01 ", "Store Sousse 2")).get().getName());
	}

	@Test
	@DisplayName("Update applies name, kind and active only; lastContact, appVersion and the hash are kept")
	void updateIgnoresClientFields() throws Exception {
		StoreWithKeyDTO created = service.create(input("RS01", "Store Sousse"));
		Long id = created.getStore().getId();
		LocalDateTime contact = LocalDateTime.of(2026, 10, 1, 9, 30);
		table.get(id).setLastContact(contact);
		table.get(id).setAppVersion("1.13.0");
		String hash = table.get(id).getApiKeyHash();

		Store input = input(null, "Store Sousse Centre");
		input.setKind(StoreKind.FRANCHISE);
		input.setActive(false);
		input.setLastContact(LocalDateTime.now());
		input.setAppVersion("0.0.1");
		input.setApiKeyHash("client-hash");
		Store updated = service.update(id, input).get();

		assertEquals("Store Sousse Centre", updated.getName());
		assertEquals(StoreKind.FRANCHISE, updated.getKind());
		assertFalse(updated.getActive());
		assertEquals(contact, updated.getLastContact());
		assertEquals("1.13.0", updated.getAppVersion());
		assertEquals(hash, updated.getApiKeyHash());
		assertFalse(service.authenticate("RS01", created.getApiKey()).isPresent(), "inactive now");
	}

	@Test
	@DisplayName("Update with only active=false keeps the name (deactivate action); unknown id gives empty")
	void partialUpdate() throws Exception {
		Long id = service.create(input("RS01", "Store Sousse")).getStore().getId();
		Store input = new Store();
		input.setActive(false);
		input.setKind(null);

		Store updated = service.update(id, input).get();

		assertEquals("Store Sousse", updated.getName());
		assertEquals(StoreKind.OWN, updated.getKind());
		assertFalse(updated.getActive());
		assertFalse(service.update(99L, input).isPresent());
	}

	// --- Delete ---

	@Test
	@DisplayName("Delete is allowed before the first contact and refused after it")
	void deleteRule() throws Exception {
		Long fresh = service.create(input("RS01", "Store Sousse")).getStore().getId();
		Long contacted = service.create(input("RS02", "Store Sfax")).getStore().getId();
		table.get(contacted).setLastContact(LocalDateTime.now());

		service.deleteById(fresh);
		assertFalse(table.containsKey(fresh));

		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.deleteById(contacted));
		assertEquals(StoreService.DELETE_AFTER_CONTACT, e.getMessage());
		assertTrue(table.containsKey(contacted));
	}

	// --- JSON (what the endpoints send and accept) ---

	@Test
	@DisplayName("JSON: the hash is never sent; lastContact, appVersion and the hash are never read from the client")
	void json() throws Exception {
		ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
		StoreWithKeyDTO created = service.create(input("RS01", "Store Sousse"));
		Store saved = table.get(created.getStore().getId());
		saved.setLastContact(LocalDateTime.of(2026, 10, 1, 9, 30));

		String sent = mapper.writeValueAsString(saved);
		assertFalse(sent.contains("apiKeyHash"), sent);
		assertFalse(sent.contains(saved.getApiKeyHash()), sent);
		assertTrue(sent.contains("\"lastContact\""), sent);
		assertTrue(mapper.writeValueAsString(created).contains("\"apiKey\":\"" + created.getApiKey() + "\""));

		Store received = mapper.readValue("{\"code\":\"RS09\",\"name\":\"N\",\"lastContact\":\"2026-10-01T09:30:00\","
				+ "\"appVersion\":\"9.9.9\",\"apiKeyHash\":\"client-hash\"}", Store.class);
		assertEquals("RS09", received.getCode());
		assertNull(received.getLastContact());
		assertNull(received.getAppVersion());
		assertNull(received.getApiKeyHash());
	}

	// --- Stubs ---

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

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
