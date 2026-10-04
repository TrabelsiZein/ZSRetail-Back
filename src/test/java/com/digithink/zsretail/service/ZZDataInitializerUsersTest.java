package com.digithink.zsretail.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.AppRole;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.repository.AppRoleRepository;
import com.digithink.zsretail.repository.UserAccountRepository;
import com.digithink.zsretail.support.TestModes;

/**
 * Head office plan, task 1.1: on a new database a head office gets only the admin account; the 4 store
 * profiles get admin, responsible and cashier as today. Calls the private initUsers() with in-memory stubs
 * (no Spring context). init() runs initUsers() only when the user table is empty; that guard is unchanged
 * and not covered here.
 */
class ZZDataInitializerUsersTest {

	// Mode of today's presets: without an ERP (store) or with one (store-erp); task 9.3
	private static final boolean[][] STORE_PROFILES = {
			{ true }, // standalone
			{ false }, // dynamics (ERP)
	};

	private static List<UserAccount> seedUsers(MockEnvironment env, boolean standalone) throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, ApplicationModeService.class, "environment", env);
		if (!standalone) {
			TestModes.erpOwners(env); // task 9.3: the ERP owners instead of application.standalone=false
		}
		Method initOwnership = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		initOwnership.setAccessible(true);
		initOwnership.invoke(mode);

		List<UserAccount> saved = new ArrayList<>();
		UserAccountRepository users = stub(UserAccountRepository.class, (method, args) -> {
			if (!"save".equals(method)) {
				return UNHANDLED;
			}
			saved.add((UserAccount) args[0]);
			return args[0];
		});
		AppRoleRepository roles = stub(AppRoleRepository.class, (method, args) -> "findByName".equals(method)
				? Optional.of(new AppRole((String) args[0], (String) args[0], "POS_USER".equals(args[0])))
				: UNHANDLED);
		PasswordEncoder encoder = new PasswordEncoder() {
			@Override
			public String encode(CharSequence raw) {
				return "encoded";
			}

			@Override
			public boolean matches(CharSequence raw, String encoded) {
				return false;
			}
		};

		ZZDataInitializer initializer = new ZZDataInitializer();
		inject(initializer, ZZDataInitializer.class, "userRepository", users);
		inject(initializer, ZZDataInitializer.class, "appRoleRepository", roles);
		inject(initializer, ZZDataInitializer.class, "passwordEncoder", encoder);
		inject(initializer, ZZDataInitializer.class, "applicationModeService", mode);
		Method initUsers = ZZDataInitializer.class.getDeclaredMethod("initUsers");
		initUsers.setAccessible(true);
		initUsers.invoke(initializer);
		return saved;
	}

	private static List<String> usernames(List<UserAccount> users) {
		List<String> names = new ArrayList<>();
		for (UserAccount user : users) {
			names.add(user.getUsername() + "/" + user.getRole() + "/" + user.getAppRole().getName());
		}
		return names;
	}

	@Test
	@DisplayName("Head office: only the admin account is created")
	void headOfficeSeedsAdminOnly() throws Exception {
		List<UserAccount> saved = seedUsers(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), true);
		assertEquals(Arrays.asList("admin/ADMIN/ADMIN"), usernames(saved));
	}

	@Test
	@DisplayName("Stores (standalone and ERP profiles): admin, responsible and cashier are created as today")
	void storesSeedThreeAccounts() throws Exception {
		for (boolean[] flags : STORE_PROFILES) {
			List<UserAccount> saved = seedUsers(new MockEnvironment(), flags[0]);
			assertEquals(Arrays.asList("admin/ADMIN/ADMIN", "responsible/RESPONSIBLE/RESPONSIBLE",
					"cashier/POS_USER/POS_USER"), usernames(saved));
		}
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
