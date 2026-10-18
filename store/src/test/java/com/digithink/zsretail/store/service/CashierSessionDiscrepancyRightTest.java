package com.digithink.zsretail.store.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.BadgeService;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.store.exception.CashDiscrepancyException;

/**
 * Closing a session with a cash difference. Step C (3.1.0): the right is the role's
 * (till:close-session-with-discrepancy). The logged user whose role holds it confirms the difference shown, without a
 * badge (the first request still gets the warning); anyone else brings a badge whose owner's role holds it. The role's
 * name means nothing (an ADMIN role without the right is refused). The real check of CashierSessionService and
 * BadgeService over in-memory stubs.
 */
class CashierSessionDiscrepancyRightTest {

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

	/** A user of the role {@code roleName}, which holds the right to close with a difference or not. */
	private static UserAccount user(String login, String roleName, boolean holdsClose) {
		UserAccount user = new UserAccount();
		user.setUsername(login);
		AppRole role = new AppRole(roleName, roleName, "POS_USER".equals(roleName));
		role.setPermissions(new HashSet<>());
		role.getPermissions().add("read:cashier-interface");
		role.getPermissions().add(BadgePermission.MAKE_RETURN.key());
		if (holdsClose) {
			role.getPermissions().add(BadgePermission.CLOSE_SESSION_WITH_DISCREPANCY.key());
		}
		user.setAppRole(role);
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

	private void closeConfirmed(String badgeCode) {
		CloseSessionRequestDTO request = request(badgeCode);
		request.setDiscrepancyConfirmed(true);
		service.checkCashDiscrepancy(session, EXPECTED, COUNTED, request);
	}

	private void assertWarned(Runnable close) {
		CashDiscrepancyException refused = assertThrows(CashDiscrepancyException.class, close::run);
		assertEquals("CASH_DISCREPANCY", refused.getErrorData().get("error"));
		assertEquals(EXPECTED, refused.getErrorData().get("expectedAmount"));
		assertEquals(COUNTED, refused.getErrorData().get("actualAmount"));
		assertEquals(5.0, (Double) refused.getErrorData().get("discrepancyAmount"), 1e-9);
	}

	@Test
	@DisplayName("Role holding the right (ADMIN, or a custom role): the difference is shown first, the confirmed close goes through without a badge (setting on, or absent: on)")
	void roleRightConfirmsWithoutBadge() {
		for (UserAccount holder : new UserAccount[] { user("admin", "ADMIN", true), user("chef", "CHEF_CAISSE", true),
				user("cashier", "POS_USER", true) }) {
			loggedUser = holder;
			settings.clear();
			assertWarned(() -> close(null)); // the warning: a typing mistake never closes silently
			assertDoesNotThrow(() -> closeConfirmed(null), holder.getUsername());
			settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
			assertWarned(() -> close(null));
			assertDoesNotThrow(() -> closeConfirmed(null), holder.getUsername());
			assertDoesNotThrow(() -> closeConfirmed("NO-SUCH-BADGE"), "no badge is checked for a confirmed role right");
		}
	}

	@Test
	@DisplayName("Role without the right (the cashier, the responsible, a custom role, even one named ADMIN): the confirmation means nothing, the difference is shown again")
	void confirmationNeedsTheRight() {
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
		for (UserAccount other : new UserAccount[] { user("cashier", "POS_USER", false),
				user("responsible", "RESPONSIBLE", false), user("chef", "CHEF_CAISSE", false), user("odd", "ADMIN", false) }) {
			loggedUser = other;
			assertWarned(() -> close(null));
			assertWarned(() -> closeConfirmed(null));
		}
		UserAccount noRole = new UserAccount();
		noRole.setUsername("norole");
		loggedUser = noRole;
		assertWarned(() -> closeConfirmed(null));
		loggedUser = null; // no security context
		assertWarned(() -> closeConfirmed(null));
	}

	@Test
	@DisplayName("A badge whose owner's role holds the right passes; an owner's role without it is refused BADGE_NO_ACCESS; an unknown badge BADGE_NOT_EXISTS")
	void badgeOfARoleHoldingTheRight() {
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "true");
		loggedUser = user("cashier", "POS_USER", false);
		UserAccount responsible = user("responsible", "RESPONSIBLE", true);
		responsible.setBadgeCode("B-RESP");
		badges.put("B-RESP", responsible);
		UserAccount other = user("other", "POS_USER", false);
		other.setBadgeCode("B-OTHER");
		badges.put("B-OTHER", other);

		assertDoesNotThrow(() -> close("B-RESP"));
		assertEquals("Badge validation failed: BADGE_NO_ACCESS",
				assertThrows(IllegalStateException.class, () -> close("B-OTHER")).getMessage());
		assertEquals("Badge validation failed: BADGE_NOT_EXISTS",
				assertThrows(IllegalStateException.class, () -> close("B-NONE")).getMessage());
	}

	@Test
	@DisplayName("No difference, or the check off: nobody is asked for anything")
	void noDifferenceOrCheckOff() {
		loggedUser = user("cashier", "POS_USER", false);
		assertDoesNotThrow(() -> service.checkCashDiscrepancy(session, EXPECTED, EXPECTED + 0.005, request(null)));
		settings.put("ENABLE_CASH_DISCREPANCY_CHECK", "false");
		assertDoesNotThrow(() -> close(null));
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
