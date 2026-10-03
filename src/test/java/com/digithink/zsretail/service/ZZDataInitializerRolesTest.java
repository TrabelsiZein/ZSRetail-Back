package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.AppRole;
import com.digithink.zsretail.repository.AppRoleRepository;
import com.digithink.zsretail.repository.UserAccountRepository;

/**
 * Head office plan, task 1.2: on a new database a head office's ADMIN role gets the Network permissions
 * (read:admin-headoffice, read:admin-headoffice-stores); task 1.5: a store with headoffice.url gets
 * read:admin-holink-status for ADMIN; the 4 store profiles seed exactly today's roles; an existing
 * role is never changed. Calls the private ensureDefaultRoles() with in-memory stubs (no Spring context).
 */
class ZZDataInitializerRolesTest {

	private static final Set<String> HEAD_OFFICE_PERMISSIONS = new HashSet<>(
			java.util.Arrays.asList("read:admin-headoffice", "read:admin-headoffice-stores"));

	// Mode flags of today's profile files: application.standalone, franchise.admin, franchise.customer
	private static final boolean[][] STORE_PROFILES = {
			{ true, false, false }, // standalone
			{ false, false, false }, // dynamics (ERP)
			{ true, false, true }, // franchise-customer
			{ true, true, false }, // franchise-admin
	};

	/** Roles saved by ensureDefaultRoles(), by name. {@code existing}: roles already in the database. */
	private static Map<String, AppRole> seedRoles(MockEnvironment env, boolean standalone, boolean franchiseAdmin,
			boolean franchiseCustomer, Map<String, AppRole> existing) throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, ApplicationModeService.class, "environment", env);
		inject(mode, ApplicationModeService.class, "standalone", standalone);
		inject(mode, ApplicationModeService.class, "franchiseAdmin", franchiseAdmin);
		inject(mode, ApplicationModeService.class, "franchiseCustomer", franchiseCustomer);
		Method initOwnership = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		initOwnership.setAccessible(true);
		initOwnership.invoke(mode);

		Map<String, AppRole> saved = new LinkedHashMap<>();
		AppRoleRepository roles = stub(AppRoleRepository.class, (method, args) -> {
			switch (method) {
				case "findByName": return Optional.ofNullable(existing.get(args[0]));
				case "save":
					AppRole role = (AppRole) args[0];
					saved.put(role.getName(), role);
					return role;
				default: return UNHANDLED;
			}
		});
		UserAccountRepository users = stub(UserAccountRepository.class,
				(method, args) -> "findAll".equals(method) ? new ArrayList<>() : UNHANDLED);

		ZZDataInitializer initializer = new ZZDataInitializer();
		inject(initializer, ZZDataInitializer.class, "appRoleRepository", roles);
		inject(initializer, ZZDataInitializer.class, "userRepository", users);
		inject(initializer, ZZDataInitializer.class, "applicationModeService", mode);
		Method ensureDefaultRoles = ZZDataInitializer.class.getDeclaredMethod("ensureDefaultRoles");
		ensureDefaultRoles.setAccessible(true);
		ensureDefaultRoles.invoke(initializer);
		return saved;
	}

	@SuppressWarnings("unchecked")
	private static Set<String> staticSet(String field) throws Exception {
		Field f = ZZDataInitializer.class.getDeclaredField(field);
		f.setAccessible(true);
		return (Set<String>) f.get(null);
	}

	private static boolean hasHeadOfficePermission(AppRole role) {
		return role.getPermissions().stream().anyMatch(HEAD_OFFICE_PERMISSIONS::contains);
	}

	@Test
	@DisplayName("Head office: ADMIN gets today's permissions plus the two Network permissions; other roles none")
	void headOfficeAdminGetsNetwork() throws Exception {
		Map<String, AppRole> roles = seedRoles(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), true,
				false, false, Collections.emptyMap());

		Set<String> expected = new HashSet<>(staticSet("ADMIN_PERMISSIONS"));
		expected.addAll(HEAD_OFFICE_PERMISSIONS);
		assertEquals(expected, roles.get("ADMIN").getPermissions());
		assertFalse(hasHeadOfficePermission(roles.get("RESPONSIBLE")));
		assertFalse(hasHeadOfficePermission(roles.get("POS_USER")));
		assertFalse(staticSet("ADMIN_PERMISSIONS").containsAll(HEAD_OFFICE_PERMISSIONS), "store set untouched");
	}

	@Test
	@DisplayName("Stores (4 profiles): the three roles get exactly today's permission sets, no Network permission")
	void storesSeedAsToday() throws Exception {
		for (boolean[] flags : STORE_PROFILES) {
			Map<String, AppRole> roles = seedRoles(new MockEnvironment(), flags[0], flags[1], flags[2],
					Collections.emptyMap());

			assertEquals(staticSet("ADMIN_PERMISSIONS"), roles.get("ADMIN").getPermissions());
			assertEquals(staticSet("RESPONSIBLE_PERMISSIONS"), roles.get("RESPONSIBLE").getPermissions());
			assertEquals(staticSet("POS_PERMISSIONS"), roles.get("POS_USER").getPermissions());
			for (AppRole role : roles.values()) {
				assertFalse(hasHeadOfficePermission(role), role.getName());
			}
		}
	}

	@Test
	@DisplayName("Store linked to a head office (headoffice.url): ADMIN gets today's permissions plus read:admin-holink-status; other roles as today")
	void linkedStoreAdminGetsHeadOfficeLink() throws Exception {
		MockEnvironment linked = new MockEnvironment()
				.withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
		for (boolean[] flags : STORE_PROFILES) {
			Map<String, AppRole> roles = seedRoles(linked, flags[0], flags[1], flags[2], Collections.emptyMap());

			Set<String> expected = new HashSet<>(staticSet("ADMIN_PERMISSIONS"));
			expected.add("read:admin-holink-status");
			assertEquals(expected, roles.get("ADMIN").getPermissions());
			assertEquals(staticSet("RESPONSIBLE_PERMISSIONS"), roles.get("RESPONSIBLE").getPermissions());
			assertEquals(staticSet("POS_PERMISSIONS"), roles.get("POS_USER").getPermissions());
			assertFalse(hasHeadOfficePermission(roles.get("ADMIN")));
		}
		Map<String, AppRole> headOffice = seedRoles(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), true,
				false, false, Collections.emptyMap());
		assertFalse(headOffice.get("ADMIN").getPermissions().contains("read:admin-holink-status"));
		assertFalse(staticSet("ADMIN_PERMISSIONS").contains("read:admin-holink-status"), "store set untouched");
	}

	@Test
	@DisplayName("Head office with existing roles (database created before 1.2): nothing is saved or changed")
	void existingRolesUntouched() throws Exception {
		Map<String, AppRole> existing = new LinkedHashMap<>();
		for (String name : new String[] { "ADMIN", "RESPONSIBLE", "POS_USER" }) {
			AppRole role = new AppRole(name, name, "POS_USER".equals(name));
			role.setPermissions(new HashSet<>(Collections.singleton("read:home")));
			existing.put(name, role);
		}

		Map<String, AppRole> saved = seedRoles(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), true,
				false, false, existing);

		assertTrue(saved.isEmpty(), "saved: " + saved.keySet());
		assertEquals(Collections.singleton("read:home"), existing.get("ADMIN").getPermissions());
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
