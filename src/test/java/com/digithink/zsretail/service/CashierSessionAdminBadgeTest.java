package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.exception.CashDiscrepancyException;
import com.digithink.zsretail.model.AppRole;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.GeneralSetup;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.Role;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.UserAccountRepository;
import com.digithink.zsretail.security.CurrentUserProvider;

/**
 * 2.2.2, step 2: closing a session with a cash difference. An admin (the logged user's role is ADMIN) closes without a
 * badge; a cashier, a responsible or a custom role is refused without a badge and passes with a valid one, exactly as in
 * 2.2.1. The real check of CashierSessionService and BadgeService over in-memory stubs.
 */
class CashierSessionAdminBadgeTest {

	private static final double EXPECTED = 120.0;
	private static final double COUNTED = 115.0;

	private final Map<String, String> settings = new HashMap<>();
	private final Map<String, UserAccount> badges = new HashMap<>();
	private UserAccount loggedUser;
	private CashierSessionService service;
	private final CashierSession session = new CashierSession();

	@BeforeEach
	void setUp() throws Exception {
		session.setSessionNumber("SESSION261010001");
		GeneralSetupRepository setup = stub(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) return UNHANDLED;
			if (!settings.containsKey(a[0])) return Optional.empty();
			GeneralSetup row = new GeneralSetup();
			row.setCode((String) a[0]);
			row.setValeur(settings.get(a[0]));
			return Optional.of(row);
		});
		BadgeService badgeService = new BadgeService();
		inject(badgeService, BadgeService.class, "userAccountRepository", stub(UserAccountRepository.class, (m, a) ->
				"findByBadgeCode".equals(m) ? Optional.ofNullable(badges.get(a[0])) : UNHANDLED));
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
		inject(service, CashierSessionService.class, "badgeService", badgeService);
		inject(service, _BaseService.class, "currentUserProvider", currentUser);
	}

	private static UserAccount user(String login, Role role, String appRoleName) {
		UserAccount user = new UserAccount();
		user.setUsername(login);
		user.setRole(role);
		AppRole appRole = new AppRole();
		appRole.setName(appRoleName);
		user.setAppRole(appRole);
		return user;
	}

	private static CloseSessionRequestDTO request(String badgeCode) {
		CloseSessionRequestDTO request = new CloseSessionRequestDTO();
		if (badgeCode != null) {
			request.setBadgeCode(badgeCode);
			request.setBadgePermission("CLOSE_SESSION_WITH_DISCREPANCY");
		}
		return request;
	}

	private void close(String badgeCode) {
		service.checkCashDiscrepancy(session, EXPECTED, COUNTED, request(badgeCode));
	}

	private void assertRefusedAsToday() {
		CashDiscrepancyException refused = assertThrows(CashDiscrepancyException.class, () -> close(null));
		assertEquals("CASH_DISCREPANCY", refused.getErrorData().get("error"));
		assertEquals(EXPECTED, refused.getErrorData().get("expectedAmount"));
		assertEquals(COUNTED, refused.getErrorData().get("actualAmount"));
		assertEquals(5.0, (Double) refused.getErrorData().get("discrepancyAmount"), 1e-9);
	}

	private void closeConfirmed(String badgeCode) {
		CloseSessionRequestDTO request = request(badgeCode);
		request.setDiscrepancyConfirmed(true);
		service.checkCashDiscrepancy(session, EXPECTED, COUNTED, request);
	}

	@Test
	@DisplayName("Admin: the difference is shown first (CASH_DISCREPANCY), the confirmed close goes through without a badge (setting on, or absent: on)")
	void adminConfirmsWithoutBadge() {
		loggedUser = user("admin", Role.ADMIN, "ADMIN");
		assertRefusedAsToday(); // the warning: a typing mistake never closes silently
		assertDoesNotThrow(() -> closeConfirmed(null));
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
		assertRefusedAsToday();
		assertDoesNotThrow(() -> closeConfirmed(null));
		assertDoesNotThrow(() -> closeConfirmed("NO-SUCH-BADGE"), "no badge is checked for an admin who confirmed");
	}

	@Test
	@DisplayName("The confirmation means nothing for a cashier, a responsible or a custom role: refused without a badge as in 2.2.1")
	void confirmationOnlyForAdmin() {
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
		for (UserAccount other : new UserAccount[] { user("cashier", Role.POS_USER, "POS_USER"),
				user("responsible", Role.RESPONSIBLE, "RESPONSIBLE"), user("chef", Role.RESPONSIBLE, "CHEF_CAISSE") }) {
			loggedUser = other;
			assertThrows(CashDiscrepancyException.class, () -> closeConfirmed(null), other.getUsername());
		}
	}

	@Test
	@DisplayName("Cashier and responsible without a badge: refused exactly as in 2.2.1 (CASH_DISCREPANCY with the amounts)")
	void othersRefusedWithoutBadge() {
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
		loggedUser = user("cashier", Role.POS_USER, "POS_USER");
		assertRefusedAsToday();
		loggedUser = user("responsible", Role.RESPONSIBLE, "RESPONSIBLE");
		assertRefusedAsToday();
	}

	@Test
	@DisplayName("Cashier with a valid badge passes as in 2.2.1; a badge without the permission or unknown is refused as in 2.2.1")
	void cashierWithBadge() {
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
		loggedUser = user("cashier", Role.POS_USER, "POS_USER");
		UserAccount responsible = user("responsible", Role.RESPONSIBLE, "RESPONSIBLE");
		responsible.setBadgeCode("B-RESP");
		responsible.setBadgePermissions("MAKE_RETURN,CLOSE_SESSION_WITH_DISCREPANCY");
		badges.put("B-RESP", responsible);
		UserAccount other = user("other", Role.POS_USER, "POS_USER");
		other.setBadgeCode("B-OTHER");
		other.setBadgePermissions("MAKE_RETURN");
		badges.put("B-OTHER", other);

		assertDoesNotThrow(() -> close("B-RESP"));
		assertEquals("Badge validation failed: BADGE_NO_ACCESS",
				assertThrows(IllegalStateException.class, () -> close("B-OTHER")).getMessage());
		assertEquals("Badge validation failed: BADGE_NOT_EXISTS",
				assertThrows(IllegalStateException.class, () -> close("B-NONE")).getMessage());
	}

	@Test
	@DisplayName("Not admin: a custom role (legacy role RESPONSIBLE), the ADMIN role name on another enum, no logged user")
	void onlyTheAdminRole() {
		loggedUser = user("chef", Role.RESPONSIBLE, "CHEF_CAISSE");
		assertRefusedAsToday();
		loggedUser = user("odd", Role.POS_USER, "ADMIN"); // the definition is the role enum, kept in step with the built-in role
		assertRefusedAsToday();
		loggedUser = null;
		assertRefusedAsToday();
	}

	@Test
	@DisplayName("No difference, or the check off: nobody is asked for anything (as in 2.2.1)")
	void noDifferenceOrCheckOff() {
		loggedUser = user("cashier", Role.POS_USER, "POS_USER");
		assertDoesNotThrow(() -> service.checkCashDiscrepancy(session, EXPECTED, EXPECTED + 0.005, request(null)));
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "false");
		assertDoesNotThrow(() -> close(null));
	}

	@Test
	@DisplayName("CurrentUserProvider.currentUserIsAdmin: the role ADMIN only; false without a logged user")
	void adminDefinition() {
		CurrentUserProvider provider = new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				if (loggedUser == null) {
					throw new IllegalStateException("no authentication");
				}
				return loggedUser;
			}
		};
		loggedUser = user("admin", Role.ADMIN, "ADMIN");
		assertTrue(provider.currentUserIsAdmin());
		loggedUser = user("responsible", Role.RESPONSIBLE, "RESPONSIBLE");
		assertFalse(provider.currentUserIsAdmin());
		loggedUser = user("chef", Role.RESPONSIBLE, "CHEF_CAISSE");
		assertFalse(provider.currentUserIsAdmin());
		loggedUser = null;
		assertFalse(provider.currentUserIsAdmin());
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
