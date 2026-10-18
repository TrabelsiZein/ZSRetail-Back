package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.repository.BadgeScanLogRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;

/**
 * Step S (3.1.0): the badge of a deactivated user is refused, at a scan (BADGE_OWNER_INACTIVE, logged like the other
 * refusals) and at the close with a cash difference (findUserByBadgeCode). The real BadgeService over in-memory stubs.
 */
class BadgeServiceInactiveOwnerTest {

	private final Map<String, UserAccount> badges = new HashMap<>();
	private final List<BadgeScanLog> logged = new ArrayList<>();
	private final UserAccount cashier = new UserAccount();
	private BadgeService service;

	@BeforeEach
	void setUp() throws Exception {
		cashier.setUsername("cashier");
		service = new BadgeService();
		inject(service, "userAccountRepository", stub(UserAccountRepository.class, (m, a) ->
				"findByBadgeCode".equals(m) ? Optional.ofNullable(badges.get(a[0])) : UNHANDLED));
		inject(service, "badgeScanLogRepository", stub(BadgeScanLogRepository.class, (m, a) -> {
			if (!"save".equals(m)) return UNHANDLED;
			logged.add((BadgeScanLog) a[0]);
			return a[0];
		}));
	}

	private UserAccount owner(String badgeCode, Boolean active) {
		UserAccount owner = new UserAccount();
		owner.setUsername("owner-" + badgeCode);
		owner.setActive(active);
		owner.setBadgeCode(badgeCode);
		owner.setBadgePermissions("MAKE_RETURN,APPLY_TOTAL_DISCOUNT");
		owner.setBadgeExpirationDate(LocalDateTime.now().plusYears(1));
		owner.setBadgeRevoked(false);
		badges.put(badgeCode, owner);
		return owner;
	}

	@Test
	@DisplayName("A valid badge holding the permission, owner deactivated: refused BADGE_OWNER_INACTIVE, logged with its owner")
	void inactiveOwnerRefusedAndLogged() {
		UserAccount owner = owner("B-GONE", false);

		BadgeService.BadgeScanResult result = service.scanBadge("B-GONE", BadgePermission.MAKE_RETURN, cashier, 7L);

		assertFalse(result.isSuccess());
		assertFalse(result.isHasPermission());
		assertEquals("BADGE_OWNER_INACTIVE", result.getFailureReason());
		assertEquals(1, logged.size());
		BadgeScanLog row = logged.get(0);
		assertFalse(row.getSuccess());
		assertEquals("BADGE_OWNER_INACTIVE", row.getFailureReason());
		assertSame(owner, row.getScannedUser());
		assertSame(cashier, row.getScannedBy());
		assertEquals(BadgePermission.MAKE_RETURN, row.getFunctionality());
		assertEquals(7L, row.getSessionId());
		// the close with a cash difference reads the badge through findUserByBadgeCode: refused too
		assertFalse(service.findUserByBadgeCode("B-GONE").isPresent());
	}

	@Test
	@DisplayName("Owner active, or active unset (read as active): the scan passes as before, logged as a success")
	void activeOwnerStillPasses() {
		owner("B-ON", true);
		owner("B-NULL", null);

		for (String code : new String[] { "B-ON", "B-NULL" }) {
			BadgeService.BadgeScanResult result = service.scanBadge(code, BadgePermission.APPLY_TOTAL_DISCOUNT, cashier,
					null);
			assertTrue(result.isSuccess(), code);
			assertNull(result.getFailureReason(), code);
			assertTrue(service.findUserByBadgeCode(code).isPresent(), code);
		}
		assertEquals(2, logged.size());
		assertTrue(logged.stream().allMatch(BadgeScanLog::getSuccess));
	}

	// ─── Stub plumbing (as CashierSessionAdminBadgeTest) ─────────────

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

	private static void inject(Object target, String fieldName, Object value) throws Exception {
		Field field = BadgeService.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
