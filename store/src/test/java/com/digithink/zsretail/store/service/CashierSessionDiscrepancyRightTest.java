package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.GrantedBy;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.store.exception.CashDiscrepancyException;
import com.digithink.zsretail.store.exception.TillRightRequiredException;
import com.digithink.zsretail.store.exception.TillRightRequiredException.Reason;
import com.digithink.zsretail.store.support.InMemoryTillRights;

/**
 * Closing a session with a cash difference. Step C (3.1.0): the right is the role's
 * (till:close-session-with-discrepancy), whatever the role's name. Step D: the logged user whose role holds it confirms
 * the difference shown (logged ROLE); anyone else sends the grant of a badge scan, used once (logged BADGE with the
 * badge's owner); neither: the difference is shown (CASH_DISCREPANCY); a confirmation without the right or a grant not
 * valid: TILL_RIGHT_REQUIRED (403). The real CashierSessionService check, TillRightsGuard, TillGrantService and
 * BadgeService over in-memory stubs.
 */
class CashierSessionDiscrepancyRightTest {

	private static final double EXPECTED = 120.0;
	private static final double COUNTED = 115.0;
	private static final BadgePermission CLOSE = BadgePermission.CLOSE_SESSION_WITH_DISCREPANCY;

	private final Map<String, String> settings = new HashMap<>();
	private final InMemoryTillRights rights = new InMemoryTillRights();
	private UserAccount loggedUser;
	private CashierSessionService service;
	private final CashierSession session = new CashierSession();
	private final UserAccount responsible = user("responsible", "RESPONSIBLE", true);

	@BeforeEach
	void setUp() throws Exception {
		session.setId(7L);
		session.setSessionNumber("SESSION261010001");
		GeneralSetupRepository setup = stub(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) return UNHANDLED;
			if (!settings.containsKey(a[0])) return Optional.empty();
			GeneralSetup row = new GeneralSetup();
			row.setCode((String) a[0]);
			row.setValeur(settings.get(a[0]));
			return Optional.of(row);
		});
		CurrentUserProvider currentUser = new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				if (loggedUser == null) {
					throw new IllegalStateException("no authentication"); // as with no security context
				}
				return loggedUser;
			}

			@Override
			public String getCurrentUserName() {
				return loggedUser == null ? null : loggedUser.getUsername();
			}
		};
		service = new CashierSessionService();
		inject(service, CashierSessionService.class, "generalSetupRepository", setup);
		inject(service, CashierSessionService.class, "tillRightsGuard", rights.guard);
		inject(service, _BaseService.class, "currentUserProvider", currentUser);
	}

	/** A user of the role {@code roleName}, which holds the right to close with a difference or not. */
	private static UserAccount user(String login, String roleName, boolean holdsClose) {
		UserAccount user = new UserAccount();
		user.setUsername(login);
		user.setId((long) login.hashCode());
		AppRole role = new AppRole(roleName, roleName, "POS_USER".equals(roleName));
		role.setPermissions(new HashSet<>());
		role.getPermissions().add("read:cashier-interface");
		role.getPermissions().add(BadgePermission.MAKE_RETURN.key());
		if (holdsClose) {
			role.getPermissions().add(CLOSE.key());
		}
		user.setAppRole(role);
		return user;
	}

	private void close(boolean confirmed, String grant) {
		CloseSessionRequestDTO request = new CloseSessionRequestDTO();
		request.setDiscrepancyConfirmed(confirmed);
		request.setTillGrant(grant);
		service.checkCashDiscrepancy(session, EXPECTED, COUNTED, request);
	}

	private void assertWarned(Runnable close) {
		CashDiscrepancyException refused = assertThrows(CashDiscrepancyException.class, close::run);
		assertEquals("CASH_DISCREPANCY", refused.getErrorData().get("error"));
		assertEquals(EXPECTED, refused.getErrorData().get("expectedAmount"));
		assertEquals(COUNTED, refused.getErrorData().get("actualAmount"));
		assertEquals(5.0, (Double) refused.getErrorData().get("discrepancyAmount"), 1e-9);
	}

	private Reason refusedFor(Runnable close) {
		TillRightRequiredException refused = assertThrows(TillRightRequiredException.class, close::run);
		assertEquals(CLOSE, refused.getAction());
		assertNull(refused.getLineIndex());
		assertEquals("TILL_RIGHT_REQUIRED", refused.body().get("error"));
		return refused.getReason();
	}

	@Test
	@DisplayName("Role holding the right (ADMIN, a custom role, a cashier): the difference first, then the confirmed close passes without a badge, logged ROLE (setting on, or absent: on)")
	void roleRightConfirms() {
		for (UserAccount holder : new UserAccount[] { user("admin", "ADMIN", true), user("chef", "CHEF_CAISSE", true),
				user("cashier", "POS_USER", true) }) {
			loggedUser = holder;
			settings.clear();
			rights.log.clear();
			assertWarned(() -> close(false, null)); // the warning: a typing mistake never closes silently
			assertDoesNotThrow(() -> close(true, null), holder.getUsername());
			settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
			assertDoesNotThrow(() -> close(true, null), holder.getUsername());
			assertEquals(2, rights.log.size());
			BadgeScanLog row = rights.log.get(0);
			assertEquals(GrantedBy.ROLE, row.getGrantedBy());
			assertSame(holder, row.getScannedBy());
			assertSame(holder, row.getScannedUser());
			assertNull(row.getScannedBadgeCode());
			assertEquals(CLOSE, row.getFunctionality());
			assertEquals(7L, row.getSessionId());
		}
	}

	@Test
	@DisplayName("Role without the right (the cashier, the responsible, a custom role, one named ADMIN, no role, no user): the difference is shown, a confirmation is refused TILL_RIGHT_REQUIRED NO_GRANT")
	void confirmationNeedsTheRight() {
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
		UserAccount noRole = new UserAccount();
		noRole.setUsername("norole");
		for (UserAccount other : new UserAccount[] { user("cashier", "POS_USER", false),
				user("responsible", "RESPONSIBLE", false), user("chef", "CHEF_CAISSE", false), user("odd", "ADMIN", false),
				noRole, null }) {
			loggedUser = other;
			assertWarned(() -> close(false, null));
			assertEquals(Reason.NO_GRANT, refusedFor(() -> close(true, null)));
		}
		assertTrue(rights.log.isEmpty());
	}

	@Test
	@DisplayName("A cashier without the right sends a responsible's grant: passes once, logged BADGE with the owner; again: GRANT_USED")
	void badgeGrant() {
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
		loggedUser = user("cashier", "POS_USER", false);
		String token = rights.grant(CLOSE, 7L, loggedUser, responsible);
		assertNotNull(token);

		assertDoesNotThrow(() -> close(false, token));
		assertEquals(1, rights.log.size());
		BadgeScanLog row = rights.log.get(0);
		assertEquals(GrantedBy.BADGE, row.getGrantedBy());
		assertSame(loggedUser, row.getScannedBy());
		assertSame(responsible, row.getScannedUser());
		assertEquals("B-responsible", row.getScannedBadgeCode());
		assertEquals(7L, row.getSessionId());
		assertEquals("CLOSE SESSION261010001", rights.last().getConsumedByRef());

		assertEquals(Reason.GRANT_USED, refusedFor(() -> close(false, token)));
		assertEquals(1, rights.log.size());
	}

	@Test
	@DisplayName("A grant of another action, another session, expired or unknown: TILL_RIGHT_REQUIRED with its reason, nothing used")
	void grantsNotValid() {
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
		loggedUser = user("cashier", "POS_USER", false);
		String otherAction = rights.grant(BadgePermission.MAKE_RETURN, 7L, loggedUser, responsible);
		String otherSession = rights.grant(CLOSE, 8L, loggedUser, responsible);
		String expired = rights.grant(CLOSE, 7L, loggedUser, responsible);
		rights.last().setExpiresAt(rights.last().getIssuedAt().minusSeconds(1));

		assertEquals(Reason.GRANT_OTHER_ACTION, refusedFor(() -> close(false, otherAction)));
		assertEquals(Reason.GRANT_OTHER_SESSION, refusedFor(() -> close(false, otherSession)));
		assertEquals(Reason.GRANT_EXPIRED, refusedFor(() -> close(false, expired)));
		assertEquals(Reason.GRANT_UNKNOWN, refusedFor(() -> close(false, "no-such-token")));
		assertTrue(rights.grants.values().stream().allMatch(g -> g.getConsumedAt() == null));
		assertTrue(rights.log.isEmpty());
	}

	@Test
	@DisplayName("No difference, or the check off: nobody is asked for anything")
	void noDifferenceOrCheckOff() {
		loggedUser = user("cashier", "POS_USER", false);
		assertDoesNotThrow(() -> service.checkCashDiscrepancy(session, EXPECTED, EXPECTED + 0.005,
				new CloseSessionRequestDTO()));
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "false");
		assertDoesNotThrow(() -> close(false, null));
		assertTrue(rights.log.isEmpty());
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

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
