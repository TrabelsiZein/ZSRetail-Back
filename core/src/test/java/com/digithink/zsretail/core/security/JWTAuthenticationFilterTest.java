package com.digithink.zsretail.core.security;

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

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.support.TestModes;

/**
 * Head office plan, task 1.1: on a head office a cashier role (AppRole.isPosRole, the flag the frontend reads)
 * gets no token; other roles log in as before; on the 4 store profiles a cashier login answers exactly as
 * today. Plain JUnit with Spring's servlet mocks (no Spring context).
 */
class JWTAuthenticationFilterTest {

	/** Keys of a successful login answer for a user with an AppRole (step E, 3.1.0: no "role", the enum is retired). */
	private static final TreeSet<String> LOGIN_KEYS = new TreeSet<>(Arrays.asList("fullName", "token",
			"status", "permissions", "appRoleId", "appRoleName", "appRoleLabel", "isPosRole"));

	// Mode of today's presets: without an ERP (store) or with one (store-erp); task 9.3
	private static final boolean[][] STORE_PROFILES = {
			{ true }, // standalone
			{ false }, // dynamics (ERP)
	};

	private static ApplicationModeService mode(MockEnvironment env, boolean standalone) throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, "environment", env);
		if (!standalone) {
			TestModes.erpOwners(env); // task 9.3: the ERP owners instead of application.standalone=false
		}
		Method initOwnership = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		initOwnership.setAccessible(true);
		initOwnership.invoke(mode);
		return mode;
	}

	private static ApplicationModeService headOffice() throws Exception {
		return mode(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), true);
	}

	private static UserAccount user(String username, AppRole appRole) {
		UserAccount user = new UserAccount();
		user.setUsername(username);
		user.setFullName(username + " user");
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
		return user("cashier", appRole(3L, "POS_USER", true));
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
		for (UserAccount user : Arrays.asList(user("admin", appRole(1L, "ADMIN", false)),
				user("legacy", null))) {
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
			assertFalse(body.has("role"), "step E: no enum");
			assertEquals("cashier user", body.getString("fullName"));
			assertEquals(header, SecurityParams.HEADER_PREFIX + body.getString("token"));
			assertEquals(3L, body.getLong("appRoleId"));
			assertEquals("POS_USER", body.getString("appRoleName"));
			assertEquals("POS_USER label", body.getString("appRoleLabel"));
			assertTrue(body.getBoolean("isPosRole"));
			assertEquals("read:pos_user", body.getJSONArray("permissions").getString(0));
		}
	}

	@Test
	@DisplayName("3.4.0, event log step 3: a login writes LOGIN_OK after its answer; a refused one LOGIN_REFUSED, its 403"
			+ " answer unchanged; the text typed of an unknown login is never written")
	void loginEvents() throws Exception {
		com.digithink.zsretail.core.support.InMemoryEventLog log = new com.digithink.zsretail.core.support.InMemoryEventLog();
		UserAccount cashier = cashier();
		cashier.setId(3L);
		com.digithink.zsretail.core.eventlog.service.AccessEvents events = new com.digithink.zsretail.core.eventlog.service.AccessEvents(
				log.eventLog(), login -> "cashier".equals(login) ? cashier : null);
		org.springframework.security.authentication.AuthenticationManager refusing = authentication -> {
			throw new org.springframework.security.authentication.BadCredentialsException("Bad credentials");
		};
		JWTAuthenticationFilter filter = new JWTAuthenticationFilter(refusing, mode(new MockEnvironment(), true), events);

		MockHttpServletRequest ok = new MockHttpServletRequest("POST", "/login");
		ok.setRemoteAddr("192.168.1.21");
		MockHttpServletResponse okAnswer = new MockHttpServletResponse();
		filter.successfulAuthentication(ok, okAnswer, new MockFilterChain(), new UsernamePasswordAuthenticationToken(cashier, null));
		assertEquals(200, okAnswer.getStatus());
		com.digithink.zsretail.core.eventlog.model.EventLogEntry loggedIn = log.only("LOGIN_OK");
		assertEquals("cashier", loggedIn.getUserLogin());
		assertEquals("192.168.1.21", loggedIn.getClientAddress());

		for (String typed : Arrays.asList("cashier", "Secret!Pass")) {
			MockHttpServletRequest refused = new MockHttpServletRequest("POST", "/login");
			refused.addHeader("username", typed);
			refused.addHeader("password", "wrong");
			refused.setRemoteAddr("192.168.1.30");
			MockHttpServletResponse answer = new MockHttpServletResponse();
			assertNull(filter.attemptAuthentication(refused, answer));
			assertEquals(403, answer.getStatus());
			JSONObject body = new JSONObject(answer.getContentAsString());
			assertEquals("Incorrect login or password", body.getString("msg"), "the answer never says which");
		}
		assertEquals(Arrays.asList("LOGIN_OK", "LOGIN_REFUSED", "LOGIN_REFUSED"), log.codes());
		assertEquals("cashier", log.rows.get(1).getUserLogin());
		assertEquals("Connexion refusée pour cashier user : mot de passe incorrect, depuis 192.168.1.30.",
				log.rows.get(1).getDetails());
		com.digithink.zsretail.core.eventlog.model.EventLogEntry unknown = log.rows.get(2);
		assertNull(unknown.getUserLogin());
		assertEquals(com.digithink.zsretail.core.eventlog.service.AccessEvents.UNKNOWN_LOGIN, unknown.getSubjectLabel());
		assertEquals("192.168.1.30", unknown.getClientAddress());
		assertFalse(unknown.getDetails().contains("Secret"), unknown.getDetails());
	}

	@Test
	@DisplayName("3.4.0, event log step 4: a refused login never writes the login typed in the server log (it may be a"
			+ " password typed in the wrong field): one WARN with its kind and the address")
	void logWithoutLogin() throws Exception {
		// Log4j2's API is bridged to SLF4J (log4j-to-slf4j): the lines end in Logback
		ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
				.getLogger(JWTAuthenticationFilter.class);
		ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> capture = new ch.qos.logback.core.read.ListAppender<>();
		capture.start();
		logger.addAppender(capture);
		ch.qos.logback.classic.Level before = logger.getLevel();
		logger.setLevel(ch.qos.logback.classic.Level.ALL);
		try {
			org.springframework.security.authentication.AuthenticationManager refusing = authentication -> {
				throw new org.springframework.security.authentication.BadCredentialsException("Bad credentials");
			};
			JWTAuthenticationFilter filter = new JWTAuthenticationFilter(refusing, mode(new MockEnvironment(), true));
			MockHttpServletRequest refused = new MockHttpServletRequest("POST", "/login");
			refused.addHeader("username", "MonMotDePasse!2026");
			refused.addHeader("password", "x");
			refused.setRemoteAddr("192.168.1.30");
			filter.attemptAuthentication(refused, new MockHttpServletResponse());
		} finally {
			logger.detachAppender(capture);
			logger.setLevel(before);
		}
		java.util.List<String> lines = new java.util.ArrayList<>();
		for (ch.qos.logback.classic.spi.ILoggingEvent event : capture.list) {
			lines.add(event.getLevel() + " " + event.getFormattedMessage()
					+ (event.getThrowableProxy() != null ? " " + event.getThrowableProxy().getMessage() : ""));
		}
		assertTrue(lines.contains("WARN Login refused (BadCredentialsException) from 192.168.1.30"), lines.toString());
		for (String line : lines) {
			assertFalse(line.contains("MonMotDePasse"), line);
		}
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception {
		Field field = ApplicationModeService.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test
	@DisplayName("Fixes after the test round: the login-purpose header of the till's admin-return popup writes ADMIN_RETURN_VERIFIED, the answer unchanged")
	void adminReturnHeader() throws Exception {
		com.digithink.zsretail.core.support.InMemoryEventLog log = new com.digithink.zsretail.core.support.InMemoryEventLog();
		UserAccount cashier = cashier();
		cashier.setId(3L);
		JWTAuthenticationFilter filter = new JWTAuthenticationFilter(null, mode(new MockEnvironment(), true),
				new com.digithink.zsretail.core.eventlog.service.AccessEvents(log.eventLog(), login -> cashier));
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.addHeader(JWTAuthenticationFilter.LOGIN_PURPOSE, "TILL_ADMIN_RETURN");
		MockHttpServletResponse answer = new MockHttpServletResponse();
		filter.successfulAuthentication(request, answer, new MockFilterChain(), new UsernamePasswordAuthenticationToken(cashier, null));
		assertEquals(200, answer.getStatus());
		assertTrue(new JSONObject(answer.getContentAsString()).getString("token").length() > 0);
		assertEquals(java.util.Arrays.asList("ADMIN_RETURN_VERIFIED"), log.codes());
	}
}
