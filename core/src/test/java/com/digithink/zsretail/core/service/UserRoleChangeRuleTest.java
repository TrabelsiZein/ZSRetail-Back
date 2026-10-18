package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.exception.RoleChangeRefusedException;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.AppRoleRepository;
import com.digithink.zsretail.core.security.SystemRoles;

/**
 * I-21 (test session 2026-10-17): nobody changes his own role; only an administrator gives or removes the
 * Administrateur role. Step E (3.1.0): the administrator is the system role ADMIN (SystemRoles), the enum is retired.
 * {@code UserAccountService.checkRoleChange} over the three built-in roles and a custom one; no Spring context.
 */
class UserRoleChangeRuleTest {

	private final AppRole adminRole = role(1L, "ADMIN", false);
	private final AppRole responsibleRole = role(2L, "RESPONSIBLE", false);
	private final AppRole cashierRole = role(3L, "POS_USER", true);
	private final AppRole chefRole = role(4L, "CHEF_CAISSE", false);

	private final UserAccount admin = user(1L, adminRole);
	private final UserAccount responsible = user(2L, responsibleRole);
	private final UserAccount cashier = user(3L, cashierRole);
	private final UserAccount chef = user(4L, chefRole);

	private UserAccountService service;

	@BeforeEach
	void setUp() throws Exception {
		Map<Long, AppRole> roles = Map.of(1L, adminRole, 2L, responsibleRole, 3L, cashierRole, 4L, chefRole);
		AppRoleRepository repository = (AppRoleRepository) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[] { AppRoleRepository.class }, (proxy, method, args) -> {
					if ("findById".equals(method.getName())) {
						return Optional.ofNullable(roles.get(args[0]));
					}
					throw new UnsupportedOperationException(method.getName());
				});
		service = new UserAccountService();
		Field field = UserAccountService.class.getDeclaredField("appRoleRepository");
		field.setAccessible(true);
		field.set(service, repository);
	}

	@Test
	@DisplayName("I-21: a responsible cannot make himself Administrateur")
	void responsibleCannotPromoteHimself() {
		RoleChangeRefusedException e = assertThrows(RoleChangeRefusedException.class,
				() -> service.checkRoleChange(responsible, responsible, 1L));
		assertEquals(RoleChangeRefusedException.OWN_ROLE, e.getMessage());
	}

	@Test
	@DisplayName("Nobody changes his own role, an admin neither")
	void adminCannotChangeHisOwnRole() {
		RoleChangeRefusedException e = assertThrows(RoleChangeRefusedException.class,
				() -> service.checkRoleChange(admin, admin, 2L));
		assertEquals(RoleChangeRefusedException.OWN_ROLE, e.getMessage());
	}

	@Test
	@DisplayName("An edit that sends the same role back is not a change")
	void unchangedRoleIsAlwaysAccepted() {
		assertDoesNotThrow(() -> service.checkRoleChange(responsible, responsible, 2L));
		assertDoesNotThrow(() -> service.checkRoleChange(responsible, admin, 1L));
	}

	@Test
	@DisplayName("A responsible can neither give nor remove the Administrateur role")
	void onlyAnAdminTouchesTheAdminRole() {
		RoleChangeRefusedException give = assertThrows(RoleChangeRefusedException.class,
				() -> service.checkRoleChange(responsible, cashier, 1L));
		assertEquals(RoleChangeRefusedException.ADMIN_ONLY, give.getMessage());
		RoleChangeRefusedException remove = assertThrows(RoleChangeRefusedException.class,
				() -> service.checkRoleChange(responsible, admin, 3L));
		assertEquals(RoleChangeRefusedException.ADMIN_ONLY, remove.getMessage());
		RoleChangeRefusedException create = assertThrows(RoleChangeRefusedException.class,
				() -> service.checkRoleChange(responsible, new UserAccount(), 1L));
		assertEquals(RoleChangeRefusedException.ADMIN_ONLY, create.getMessage());
	}

	@Test
	@DisplayName("A responsible still changes roles below Administrateur; an admin gives and removes it")
	void allowedChanges() {
		assertDoesNotThrow(() -> service.checkRoleChange(responsible, cashier, 2L));
		assertDoesNotThrow(() -> service.checkRoleChange(responsible, new UserAccount(), 3L));
		assertDoesNotThrow(() -> service.checkRoleChange(admin, cashier, 1L));
		assertDoesNotThrow(() -> service.checkRoleChange(admin, responsible, 3L));
		assertDoesNotThrow(() -> service.checkRoleChange(admin, new UserAccount(), 1L));
	}

	@Test
	@DisplayName("Step E: a custom role is never an administrator, even holding every permission; no role, no user neither")
	void onlyTheSystemRoleIsAdmin() {
		chefRole.setPermissions(new java.util.HashSet<>(adminRole.getPermissions()));
		chefRole.getPermissions().add("write:admin-roles");
		RoleChangeRefusedException e = assertThrows(RoleChangeRefusedException.class,
				() -> service.checkRoleChange(chef, cashier, 1L));
		assertEquals(RoleChangeRefusedException.ADMIN_ONLY, e.getMessage());
		assertDoesNotThrow(() -> service.checkRoleChange(chef, cashier, 2L));
		assertThrows(RoleChangeRefusedException.class, () -> service.checkRoleChange(null, cashier, 1L));
		assertEquals(false, SystemRoles.isAdmin(user(9L, null)));
		assertEquals(false, SystemRoles.isAdmin(null));
		assertEquals(true, SystemRoles.isAdmin(admin));
		assertEquals(false, SystemRoles.isAdminRole(role(5L, "admin", false)), "the name, exactly");
	}

	private static AppRole role(Long id, String name, boolean pos) {
		AppRole role = new AppRole(name, name, pos);
		role.setId(id);
		return role;
	}

	private static UserAccount user(Long id, AppRole appRole) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setAppRole(appRole);
		return user;
	}
}
