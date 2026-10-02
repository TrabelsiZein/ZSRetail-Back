package com.digithink.zsretail.headoffice.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.StoreRepository;
import com.digithink.zsretail.headoffice.service.StoreService;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.security.SecurityParams;
import com.digithink.zsretail.service._BaseService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Head office plan, task 1.3: the store key filter on /ho/**. Good key, wrong key, missing headers, unknown store,
 * inactive store: one 401 for all refusals, the reason in the WARN line, never the key. Paths outside /ho/** are
 * not touched; a user token alone does not pass. Plain JUnit with Spring's servlet mocks, the real StoreService
 * over an in-memory repository (no Spring context).
 */
class StoreApiKeyFilterTest {

	private static final String REMOTE = "192.168.1.20";

	/** In-memory ho_store, by id. */
	private final Map<Long, Store> table = new LinkedHashMap<>();
	private int lookups;
	private StoreService service;
	private StoreApiKeyFilter filter;
	private String key;
	private ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void setUp() throws Exception {
		SecurityContextHolder.clearContext();
		StoreRepository repository = stub(StoreRepository.class, (method, args) -> {
			switch (method) {
				case "findByCodeIgnoreCase":
					lookups++;
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
		key = service.create(store("RS01", "Store Sousse")).getApiKey();
		lookups = 0;
		filter = new StoreApiKeyFilter(service);

		logs = new ListAppender<>();
		logs.start();
		logger().addAppender(logs);
	}

	@AfterEach
	void tearDown() {
		logger().detachAppender(logs);
		SecurityContextHolder.clearContext();
	}

	private static Logger logger() {
		return (Logger) LoggerFactory.getLogger(StoreApiKeyFilter.class);
	}

	private static Store store(String code, String name) {
		Store store = new Store();
		store.setCode(code);
		store.setName(name);
		return store;
	}

	private static MockHttpServletRequest request(String path, String code, String presentedKey) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/zsretail/api" + path);
		request.setContextPath("/zsretail/api");
		request.setServletPath(path);
		request.setRemoteAddr(REMOTE);
		if (code != null) {
			request.addHeader(StoreApiKeyFilter.CODE_HEADER, code);
		}
		if (presentedKey != null) {
			request.addHeader(StoreApiKeyFilter.KEY_HEADER, presentedKey);
		}
		return request;
	}

	/** Runs the filter; the chain records the request when the filter lets it through. */
	private static MockFilterChain run(StoreApiKeyFilter filter, MockHttpServletRequest request,
			MockHttpServletResponse response) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request, response, chain);
		return chain;
	}

	private List<String> warnings() {
		return logs.list.stream().filter(e -> e.getLevel() == Level.WARN).map(ILoggingEvent::getFormattedMessage)
				.collect(Collectors.toList());
	}

	/** The one refusal: 401, JSON, the fixed body, the chain not reached, no authentication left. */
	private void assertRefused(MockHttpServletResponse response, MockFilterChain chain) throws Exception {
		assertEquals(401, response.getStatus());
		assertEquals("application/json;charset=UTF-8", response.getContentType());
		assertEquals(StoreApiKeyFilter.REFUSAL_BODY, response.getContentAsString());
		assertNull(chain.getRequest(), "the request went on");
		assertNull(SecurityContextHolder.getContext().getAuthentication());
	}

	/** One WARN line with the reason, the code, the remote address and the path; never the key. */
	private void assertLogged(String reason, String code, String... neverLogged) {
		List<String> warnings = warnings();
		assertEquals(1, warnings.size(), warnings.toString());
		String line = warnings.get(0);
		assertTrue(line.contains("(" + reason + ")"), line);
		assertTrue(line.contains("store '" + code + "'"), line);
		assertTrue(line.contains(REMOTE), line);
		assertTrue(line.contains("/ho/ping"), line);
		for (String secret : neverLogged) {
			assertFalse(line.contains(secret), "logged: " + line);
		}
	}

	@Test
	@DisplayName("Good key: the request goes on with the store as principal and the authority HO_STORE")
	void goodKey() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = run(filter, request("/ho/ping", " rs01 ", key), response);

		assertNotNull(chain.getRequest(), "the request was stopped");
		assertEquals(200, response.getStatus());
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		assertTrue(authentication.isAuthenticated());
		assertSame(table.get(1L), authentication.getPrincipal());
		assertEquals(Collections.singletonList(StoreApiKeyFilter.STORE_AUTHORITY),
				authentication.getAuthorities().stream().map(Object::toString).collect(Collectors.toList()));
		assertFalse(String.valueOf(authentication).contains(key), "the token shows the key");
		assertTrue(warnings().isEmpty(), warnings().toString());
	}

	@Test
	@DisplayName("Wrong key: 401, logged as wrong key, neither key logged")
	void wrongKey() throws Exception {
		String wrong = key.substring(1) + "x";
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = run(filter, request("/ho/ping", "RS01", wrong), response);

		assertRefused(response, chain);
		assertLogged("wrong key", "RS01", wrong, key, table.get(1L).getApiKeyHash());
	}

	@Test
	@DisplayName("Missing headers (none, one only, blank): 401, logged as missing header, no store lookup")
	void missingHeaders() throws Exception {
		String[][] cases = { { null, null }, { "RS01", null }, { null, key }, { "  ", key }, { "RS01", " " } };
		for (String[] headers : cases) {
			logs.list.clear();
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = run(filter, request("/ho/ping", headers[0], headers[1]), response);

			assertRefused(response, chain);
			String code = headers[0] == null ? "" : headers[0];
			assertLogged(StoreApiKeyFilter.MISSING_HEADER, code, key);
		}
		assertEquals(0, lookups, "a store was looked up");
	}

	@Test
	@DisplayName("Unknown store: 401, logged as unknown store")
	void unknownStore() throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = run(filter, request("/ho/ping", "RS99", key), response);

		assertRefused(response, chain);
		assertLogged("unknown store", "RS99", key);
	}

	@Test
	@DisplayName("Inactive store with its right key: 401, logged as inactive store")
	void inactiveStore() throws Exception {
		table.get(1L).setActive(false);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = run(filter, request("/ho/ping", "RS01", key), response);

		assertRefused(response, chain);
		assertLogged("inactive store", "RS01", key);
	}

	@Test
	@DisplayName("The four refusals give the same status, type and body")
	void sameRefusal() throws Exception {
		String key2 = service.create(store("RS02", "Store Sfax")).getApiKey();
		table.get(2L).setActive(false);
		String[][] cases = { { null, null }, { "RS99", key }, { "RS01", key2 }, { "RS02", key2 } };

		MockHttpServletResponse first = null;
		for (String[] headers : cases) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			run(filter, request("/ho/ping", headers[0], headers[1]), response);
			if (first == null) {
				first = response;
			}
			assertEquals(first.getStatus(), response.getStatus());
			assertEquals(first.getContentType(), response.getContentType());
			assertEquals(first.getContentAsString(), response.getContentAsString());
		}
		assertEquals(401, first.getStatus());
		assertEquals(4, warnings().size());
	}

	@Test
	@DisplayName("Outside /ho/**: the filter does nothing, with or without store headers")
	void otherPathsUntouched() throws Exception {
		for (String path : new String[] { "/items", "/hold", "/admin/headoffice/stores", "/login", "/franchise/items" }) {
			for (String presentedKey : new String[] { key, null }) {
				MockHttpServletResponse response = new MockHttpServletResponse();
				MockFilterChain chain = run(filter, request(path, "RS01", presentedKey), response);

				assertNotNull(chain.getRequest(), path);
				assertEquals(200, response.getStatus(), path);
				assertNull(SecurityContextHolder.getContext().getAuthentication(), "a store key opened " + path);
			}
		}
		assertEquals(0, lookups, "a store was looked up");
		assertTrue(warnings().isEmpty(), warnings().toString());
	}

	@Test
	@DisplayName("A user token alone does not pass /ho/**; with a store key the store replaces the user")
	void userTokenAlone() throws Exception {
		String jwt = JWT.create().withSubject("admin").sign(Algorithm.HMAC256(SecurityParams.SECRET));

		// As if JWTAuthorizationFilter had run (it is not in the /ho/** chain)
		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
				Collections.emptyList()));
		MockHttpServletRequest request = request("/ho/ping", null, null);
		request.addHeader(SecurityParams.JWT_HEADER_NAME, SecurityParams.HEADER_PREFIX + jwt);
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = run(filter, request, response);

		assertRefused(response, chain);
		assertLogged(StoreApiKeyFilter.MISSING_HEADER, "", jwt);

		SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("admin", null,
				Collections.emptyList()));
		request = request("/ho/ping", "RS01", key);
		request.addHeader(SecurityParams.JWT_HEADER_NAME, SecurityParams.HEADER_PREFIX + jwt);
		chain = run(filter, request, new MockHttpServletResponse());

		assertNotNull(chain.getRequest());
		assertSame(table.get(1L), SecurityContextHolder.getContext().getAuthentication().getPrincipal());
	}

	@Test
	@DisplayName("Control characters in the code are not written to the log")
	void codeCleanedInLog() throws Exception {
		run(filter, request("/ho/ping", "RS99\rFAKE LINE", key), new MockHttpServletResponse());

		String line = warnings().get(0);
		assertFalse(line.contains("\r") || line.contains("\n"), line);
		assertTrue(line.contains("store 'RS99FAKE LINE'"), line);
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
