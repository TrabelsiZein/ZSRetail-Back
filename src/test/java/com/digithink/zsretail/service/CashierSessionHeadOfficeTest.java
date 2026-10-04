package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.SessionStatus;
import com.digithink.zsretail.repository.CashierSessionRepository;
import com.digithink.zsretail.security.CurrentUserProvider;

/**
 * Head office plan, task 1.1: a head office opens no cashier session, through openSession or through a
 * generic save of a new session; existing sessions are still saved; the 4 store profiles open sessions as
 * today. Plain JUnit with in-memory stubs (no Spring context).
 */
class CashierSessionHeadOfficeTest {

	/** Calls made on the repository stub, by method name. */
	private final List<String> repositoryCalls = new ArrayList<>();
	private final List<CashierSession> saved = new ArrayList<>();

	// Mode flag of today's profile files: application.standalone (the franchise profiles were removed, step 9)
	private static final boolean[][] STORE_PROFILES = {
			{ true }, // standalone
			{ false }, // dynamics (ERP)
	};

	private CashierSessionService service(MockEnvironment env, boolean standalone) throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, ApplicationModeService.class, "environment", env);
		env.setProperty("application.standalone", String.valueOf(standalone)); // step 9: read from the environment, no field
		Method initOwnership = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		initOwnership.setAccessible(true);
		initOwnership.invoke(mode);

		CashierSessionRepository repository = stub(CashierSessionRepository.class, (method, args) -> {
			repositoryCalls.add(method);
			switch (method) {
				case "findByCashierAndStatus": return Optional.empty();
				case "countByOpenedAtGreaterThanEqual": return 0L;
				case "save":
					saved.add((CashierSession) args[0]);
					return args[0];
				default: return UNHANDLED;
			}
		});
		CurrentUserProvider currentUser = new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "tester";
			}
		};

		CashierSessionService service = new CashierSessionService();
		inject(service, CashierSessionService.class, "cashierSessionRepository", repository);
		inject(service, CashierSessionService.class, "applicationModeService", mode);
		inject(service, _BaseService.class, "currentUserProvider", currentUser);
		return service;
	}

	private CashierSessionService headOffice() throws Exception {
		return service(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), true);
	}

	private static UserAccount cashier() {
		UserAccount cashier = new UserAccount();
		cashier.setUsername("cashier");
		return cashier;
	}

	@Test
	@DisplayName("Head office: openSession is refused with the head office message, nothing is read or saved")
	void headOfficeRefusesOpenSession() throws Exception {
		CashierSessionService service = headOffice();
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> service.openSession(cashier(), 100.0));
		assertEquals(CashierSessionService.HEAD_OFFICE_REFUSAL, e.getMessage());
		assertTrue(repositoryCalls.isEmpty(), "repository calls: " + repositoryCalls);
	}

	@Test
	@DisplayName("Head office: a generic save of a new session is refused (POST /cashier-session)")
	void headOfficeRefusesNewSessionThroughSave() throws Exception {
		CashierSessionService service = headOffice();
		CashierSession session = new CashierSession();
		session.setCashier(cashier());
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service.save(session));
		assertEquals(CashierSessionService.HEAD_OFFICE_REFUSAL, e.getMessage());
		assertTrue(saved.isEmpty());
	}

	@Test
	@DisplayName("Head office: an existing session is still saved")
	void headOfficeSavesExistingSession() throws Exception {
		CashierSessionService service = headOffice();
		CashierSession session = new CashierSession();
		session.setId(5L);
		session.setStatus(SessionStatus.CLOSED);
		assertSame(session, service.save(session));
		assertEquals(1, saved.size());
		assertEquals("tester", session.getUpdatedBy());
	}

	@Test
	@DisplayName("Stores (standalone and ERP profiles): openSession opens a session as today")
	void storesOpenSessionAsToday() throws Exception {
		for (boolean[] flags : STORE_PROFILES) {
			repositoryCalls.clear();
			saved.clear();
			CashierSessionService service = service(new MockEnvironment(), flags[0]);
			UserAccount cashier = cashier();

			CashierSession session = service.openSession(cashier, 100.0);

			assertSame(cashier, session.getCashier());
			assertEquals(Double.valueOf(100.0), session.getOpeningCash());
			assertEquals(SessionStatus.OPENED, session.getStatus());
			assertTrue(session.getSessionNumber().matches("SESSION\\d{6}001"), session.getSessionNumber());
			assertEquals("tester", session.getCreatedBy());
			assertEquals(1, saved.size());
		}
	}

	@Test
	@DisplayName("Stores: an open session for the same cashier is still refused with today's message")
	void storeKeepsAlreadyOpenCheck() throws Exception {
		CashierSessionService service = service(new MockEnvironment(), true);
		inject(service, CashierSessionService.class, "cashierSessionRepository",
				stub(CashierSessionRepository.class, (method, args) -> "findByCashierAndStatus".equals(method)
						? Optional.of(new CashierSession())
						: UNHANDLED));
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> service.openSession(cashier(), 100.0));
		assertEquals("Cashier already has an open session", e.getMessage());
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
