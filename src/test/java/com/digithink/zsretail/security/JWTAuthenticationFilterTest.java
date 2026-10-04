package com.digithink.zsretail.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.TreeSet;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.AppRole;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.Role;

/**
 * Head office plan, task 1.1: on a head office a cashier role (AppRole.isPosRole, the flag the frontend reads)
 * gets no token; other roles log in as before; on the 4 store profiles a cashier login answers exactly as
 * today. Plain JUnit with Spring's servlet mocks (no Spring context).
 */
class JWTAuthenticationFilterTest {

	/** Keys of a successful login answer for a user with an AppRole, as built before task 1.1. */
	private static final TreeSet<String> LOGIN_KEYS = new TreeSet<>(Arrays.asList("role", "fullName", "token",
			"status", "permissions", "appRoleId", "appRoleName", "appRoleLabel", "isPosRole"));

	// Mode flag of today's profile files: application.standalone (the franchise profiles were removed, step 9)
	private static final boolean[][] STORE_PROFILES = {
			{ true }, // standalone
			{ false }, // dynamics (ERP)
	};

	private static ApplicationModeService mode(MockEnvironment env, boolean standalone) throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, "environment", env);
		inject(mode, "standalone", standalone);
		Method initOwnership = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		initOwnership.setAccessible(true);
		initOwnership.invoke(mode);
		return mode;
	}

	private static ApplicationModeService headOffice() throws Exception {
		return mode(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), true);
	}

	private static UserAccount user(String username, Role role, AppRole appRole) {
		UserAccount user = new UserAccount();
		user.setUsername(username);
		user.setFullName(username + " user");
		user.setRole(role);
		user.setAppRole(appRole);
		return user;
	}

	private static AppRole appRole(long id, String name, boolean isPosRole) {
		AppRole appRole = new AppRole(name, name + " label", isPosRole);
		appRole.setId(id);
		appRole.setPermissions(new HashSet<>(Arrays.asList("read:" + name.toLowerCase())));
		return appRole;
	}

	private static UserAccount cashier() {
		return user("cashier", Role.POS_USER, appRole(3L, "POS_USER", true));
	}

	private static MockHttpServletResponse login(ApplicationModeService mode, UserAccount user) throws Exception {
		JWTAuthenticationFilter filter = new JWTAuthenticationFilter(null, mode);
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.successfulAuthentication(new MockHttpServletRequest("POST", "/login"), response, new MockFilterChain(),
				new UsernamePasswordAuthenticationToken(user, null));
		return response;
	}

	@Test
	@DisplayName("Head office: a cashier role gets 403 with the head office message and no token")
	void headOfficeRefusesCashier() throws Exception {
		MockHttpServletResponse response = login(headOffice(), cashier());

		assertEquals(403, response.getStatus());
		assertNull(response.getHeader(SecurityParams.JWT_HEADER_NAME));
		JSONObject body = new JSONObject(response.getContentAsString());
		assertEquals(403, body.getInt("code"));
		assertEquals(JWTAuthenticationFilter.HEAD_OFFICE_CASHIER_REFUSAL, body.getString("msg"));
		assertFalse(body.has("token"));
	}

	@Test
	@DisplayName("Head office: an admin role and a user without AppRole still log in")
	void headOfficeAllowsOtherRoles() throws Exception {
		for (UserAccount user : Arrays.asList(user("admin", Role.ADMIN, appRole(1L, "ADMIN", false)),
				user("legacy", Role.ADMIN, null))) {
			MockHttpServletResponse response = login(headOffice(), user);

			assertEquals(200, response.getStatus(), user.getUsername());
			assertTrue(response.getHeader(SecurityParams.JWT_HEADER_NAME).startsWith(SecurityParams.HEADER_PREFIX));
			JSONObject body = new JSONObject(response.getContentAsString());
			assertEquals(200, body.getInt("status"));
			assertTrue(body.getString("token").length() > 0);
		}
	}

	@Test
	@DisplayName("Stores (standalone and ERP profiles): a cashier login answers exactly as today")
	void storeCashierLoginUnchanged() throws Exception {
		for (boolean[] flags : STORE_PROFILES) {
			MockHttpServletResponse response = login(mode(new MockEnvironment(), flags[0]),
					cashier());

			assertEquals(200, response.getStatus());
			assertEquals("application/json", response.getContentType());
			assertEquals("UTF-8", response.getCharacterEncoding());
			String header = response.getHeader(SecurityParams.JWT_HEADER_NAME);
			assertTrue(header.startsWith(SecurityParams.HEADER_PREFIX), header);

			JSONObject body = new JSONObject(response.getContentAsString());
			assertEquals(LOGIN_KEYS, new TreeSet<>(body.keySet()));
			assertEquals(200, body.getInt("status"));
			assertEquals("POS_USER", body.getString("role"));
			assertEquals("cashier user", body.getString("fullName"));
			assertEquals(header, SecurityParams.HEADER_PREFIX + body.getString("token"));
			assertEquals(3L, body.getLong("appRoleId"));
			assertEquals("POS_USER", body.getString("appRoleName"));
			assertEquals("POS_USER label", body.getString("appRoleLabel"));
			assertTrue(body.getBoolean("isPosRole"));
			assertEquals("read:pos_user", body.getJSONArray("permissions").getString(0));
		}
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception {
		Field field = ApplicationModeService.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
