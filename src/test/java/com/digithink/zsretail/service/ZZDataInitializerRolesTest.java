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
 * Head office plan, task 1.6: a head office's ADMIN role gets one permission per head office route, on a new
 * database and at each start for an existing role (only the missing ones, nothing removed); task 1.5: a store with
 * headoffice.url gets read:admin-holink-status for ADMIN; the 4 store profiles seed exactly today's roles and never
 * change an existing role. Calls the private ensureDefaultRoles() with in-memory stubs (no Spring context).
 */
class ZZDataInitializerRolesTest {

	/**
	 * The head office routes of ZSRetail-Front src/router/headoffice-routes.js, as read:<meta.resource>. Task 2.5 adds
	 * tickets, sessions and returns before their frontend routes (next frontend session).
	 */
	private static final Set<String> HEAD_OFFICE_PERMISSIONS = new HashSet<>(java.util.Arrays.asList(
			"read:admin-headoffice-home", "read:admin-headoffice-stores", "read:admin-headoffice-tickets",
			"read:admin-headoffice-sessions", "read:admin-headoffice-returns", "read:admin-headoffice-items",
			"read:admin-headoffice-item-families", "read:admin-headoffice-item-subfamilies",
			"read:admin-headoffice-item-barcodes", "read:admin-headoffice-promotions", "read:admin-headoffice-customers",
			"read:admin-headoffice-loyalty-programs", "read:admin-headoffice-loyalty-members",
			"read:admin-headoffice-loyalty-member-functions", "read:admin-headoffice-loyalty-transactions",
			"read:admin-headoffice-company-information", "read:admin-headoffice-general-setup",
			"read:admin-headoffice-users", "read:admin-headoffice-roles", "read:admin-headoffice-data-import",
			"read:admin-headoffice-erp-jobs", "read:admin-headoffice-erp-communications",
			"read:admin-headoffice-erp-reference-location", "read:admin-headoffice-loyalty-overspends",
			"read:admin-headoffice-price-lists", "read:admin-headoffice-vendors", "read:admin-headoffice-purchases",
			"read:admin-headoffice-purchase-new", "read:admin-headoffice-vendor-balance",
			"read:admin-headoffice-purchase-invoices", "read:admin-headoffice-stock",
			"read:admin-headoffice-stock-movements", "read:admin-headoffice-deliveries",
			"read:admin-headoffice-network-stock", "read:admin-headoffice-supply-prices",
			"read:admin-headoffice-supply-invoices", "read:admin-headoffice-store-balances"));

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
		return seedRoles(env, standalone, franchiseAdmin, franchiseCustomer, existing, new ArrayList<>());
	}

	/** Same; {@code saves} receives the name of the role of each save call, in order. */
	private static Map<String, AppRole> seedRoles(MockEnvironment env, boolean standalone, boolean franchiseAdmin,
			boolean franchiseCustomer, Map<String, AppRole> existing, List<String> saves) throws Exception {
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
					saves.add(role.getName());
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

	/** Any head office permission, also the Network menu permission of tasks 1.2 to 1.5 (read:admin-headoffice). */
	private static boolean hasHeadOfficePermission(AppRole role) {
		return role.getPermissions().stream().anyMatch(p -> p.startsWith("read:admin-headoffice"));
	}

	private static MockEnvironment headOffice() {
		return new MockEnvironment().withProperty("node.type", "HEAD_OFFICE");
	}

	@Test
	@DisplayName("Head office, new database: ADMIN gets today's permissions plus one per head office route, saved once; other roles none")
	void headOfficeAdminGetsHeadOfficePages() throws Exception {
		List<String> saves = new ArrayList<>();
		Map<String, AppRole> roles = seedRoles(headOffice(), true, false, false, Collections.emptyMap(), saves);

		assertEquals(HEAD_OFFICE_PERMISSIONS, staticSet("HEAD_OFFICE_ADMIN_PERMISSIONS"), "same list as the frontend routes");
		assertFalse(HEAD_OFFICE_PERMISSIONS.contains("read:admin-headoffice"), "Network menu permission dropped");
		Set<String> expected = new HashSet<>(staticSet("ADMIN_PERMISSIONS"));
		expected.addAll(HEAD_OFFICE_PERMISSIONS);
		assertEquals(expected, roles.get("ADMIN").getPermissions());
		assertEquals(java.util.Arrays.asList("ADMIN", "RESPONSIBLE", "POS_USER"), saves, "each role saved once");
		assertFalse(hasHeadOfficePermission(roles.get("RESPONSIBLE")));
		assertFalse(hasHeadOfficePermission(roles.get("POS_USER")));
		assertFalse(hasHeadOfficePermission(roleWith(staticSet("ADMIN_PERMISSIONS"))), "store set untouched");
	}

	private static AppRole roleWith(Set<String> permissions) {
		AppRole role = new AppRole("ROLE", "ROLE", false);
		role.setPermissions(new HashSet<>(permissions));
		return role;
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

	/** The three roles already in the database, ADMIN with {@code adminPermissions}, the others with read:home. */
	private static Map<String, AppRole> existingRoles(String... adminPermissions) {
		Map<String, AppRole> existing = new LinkedHashMap<>();
		for (String name : new String[] { "ADMIN", "RESPONSIBLE", "POS_USER" }) {
			AppRole role = new AppRole(name, name, "POS_USER".equals(name));
			role.setPermissions(new HashSet<>("ADMIN".equals(name) ? java.util.Arrays.asList(adminPermissions)
					: Collections.singleton("read:home")));
			existing.put(name, role);
		}
		return existing;
	}

	@Test
	@DisplayName("Head office with existing roles: ADMIN receives the missing head office permissions once, keeps the others; other roles untouched")
	void headOfficeExistingAdminToppedUp() throws Exception {
		// A head office database created at task 1.2: ADMIN has the Network menu and Stores permissions
		Map<String, AppRole> existing = existingRoles("read:home", "read:admin-headoffice", "read:admin-headoffice-stores");

		List<String> saves = new ArrayList<>();
		seedRoles(headOffice(), true, false, false, existing, saves);

		assertEquals(Collections.singletonList("ADMIN"), saves);
		Set<String> expected = new HashSet<>(HEAD_OFFICE_PERMISSIONS);
		expected.add("read:home");
		expected.add("read:admin-headoffice");
		assertEquals(expected, existing.get("ADMIN").getPermissions(), "nothing removed");
		assertEquals(Collections.singleton("read:home"), existing.get("RESPONSIBLE").getPermissions());
		assertEquals(Collections.singleton("read:home"), existing.get("POS_USER").getPermissions());

		// Next start: nothing is missing, nothing is saved
		List<String> nextSaves = new ArrayList<>();
		seedRoles(headOffice(), true, false, false, existing, nextSaves);
		assertTrue(nextSaves.isEmpty(), "saved: " + nextSaves);
		assertEquals(expected, existing.get("ADMIN").getPermissions());
	}

	@Test
	@DisplayName("Stores (4 profiles, with and without headoffice.url) with existing roles: nothing is saved or changed")
	void storeExistingRolesUntouched() throws Exception {
		MockEnvironment linked = new MockEnvironment()
				.withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
		for (MockEnvironment env : new MockEnvironment[] { new MockEnvironment(), linked }) {
			for (boolean[] flags : STORE_PROFILES) {
				Map<String, AppRole> existing = existingRoles("read:home");

				List<String> saves = new ArrayList<>();
				seedRoles(env, flags[0], flags[1], flags[2], existing, saves);

				assertTrue(saves.isEmpty(), "saved: " + saves);
				assertEquals(Collections.singleton("read:home"), existing.get("ADMIN").getPermissions());
			}
		}
	}

	private static MockEnvironment suppliedStore() {
		return new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde")
				.withProperty("ownership.catalogue", "HEAD_OFFICE").withProperty("ownership.supply", "HEAD_OFFICE");
	}

	@Test
	@DisplayName("Step 7A, store whose goods come from the head office: ADMIN gets the BL reception page on a new database"
			+ " (saved once) and at the next start of an existing database (only that permission); other roles untouched")
	void suppliedStoreAdminGetsReception() throws Exception {
		List<String> saves = new ArrayList<>();
		Map<String, AppRole> roles = seedRoles(suppliedStore(), true, false, false, Collections.emptyMap(), saves);
		Set<String> expected = new HashSet<>(staticSet("ADMIN_PERMISSIONS"));
		expected.add("read:admin-holink-status");
		expected.add("read:admin-holink-deliveries");
		assertEquals(expected, roles.get("ADMIN").getPermissions());
		assertEquals(java.util.Arrays.asList("ADMIN", "RESPONSIBLE", "POS_USER"), saves, "each role saved once");
		assertEquals(staticSet("RESPONSIBLE_PERMISSIONS"), roles.get("RESPONSIBLE").getPermissions());

		Map<String, AppRole> existing = existingRoles("read:home", "read:admin-holink-status");
		List<String> topUp = new ArrayList<>();
		seedRoles(suppliedStore(), true, false, false, existing, topUp);
		assertEquals(Collections.singletonList("ADMIN"), topUp);
		assertEquals(new HashSet<>(java.util.Arrays.asList("read:home", "read:admin-holink-status",
				"read:admin-holink-deliveries")), existing.get("ADMIN").getPermissions(), "nothing else added or removed");
		assertEquals(Collections.singleton("read:home"), existing.get("RESPONSIBLE").getPermissions());

		List<String> nextStart = new ArrayList<>();
		seedRoles(suppliedStore(), true, false, false, existing, nextStart);
		assertTrue(nextStart.isEmpty(), "saved: " + nextStart);
		assertFalse(staticSet("ADMIN_PERMISSIONS").contains("read:admin-holink-deliveries"), "store set untouched");
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
