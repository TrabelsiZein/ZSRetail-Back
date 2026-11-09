package com.digithink.zsretail.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.TreeSet;

import org.json.JSONObject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.GoldenJson;
import com.digithink.zsretail.core.support.MigTestApplication;
import com.digithink.zsretail.core.support.StatusMatrix;

/**
 * Boot 4 migration, step 1.2: the status of a small set of URLs by caller on the store's real security chains, over HTTP.
 * Written from what 2.3.9 answers, the reference for every later step: a changed cell is a changed security behaviour.
 * <p>
 * Reading the table: an anonymous call on the main chain gets 403, not 401 (no login form or HTTP Basic, so Spring
 * Security's default refusal); 401 is the /ho/** chain, which exists on a store too and refuses everyone. The API checks no
 * role in general (the front does), only some endpoints do, so a cashier reaches most URLs.
 */
class StoreSecurityMatrixTest {

	private static final int[] OK = { 403, 403, 200, 200 };

	private static MigTestApplication store;

	@BeforeAll
	static void start() {
		store = MigTestApplication.start(StoreApplication.class, "store");
	}

	@AfterAll
	static void stop() {
		if (store != null) {
			store.close();
		}
	}

	private static StatusMatrix matrix() {
		return new StatusMatrix(store).caller("anonymous").caller("bad token", "Authorization", "Bearer not.a.token")
				.caller("cashier", store.bearerOf("cashier", "cashier")).caller("admin", store.bearerOf("admin", "P@ssw0rd"));
	}

	@Test
	@DisplayName("With a licence: the public paths, the authenticated ones, the admin ones, the errors, /ho/**")
	void licensed() {
		store.withLicense();
		matrix()
				// Public: open to all, but a token that fails verification is refused whatever the path
				.row("GET", "/config", 200, 403, 200, 200)
				.row("GET", "/company-info", 200, 403, 200, 200)
				.row("GET", "/license/status", 200, 403, 200, 200)
				.row("GET", "/item-image/ITEM/1", 404, 403, 404, 404) // no such image, but the chain let it through
				.row("GET", "/error", 500, 403, 500, 500) // public since step 1.4 for its bodies; called directly it has no error to show
				// Any logged user
				.row("GET", "/location", OK)
				.row("GET", "/user-account/current", OK)
				.row("GET", "/payment-method", OK)
				.row("GET", "/cashier-session/current", OK)
				.row("GET", "/general-setup", OK)
				// Checked by the endpoint: admin only (a ResponseStatusException, through the ERROR dispatch)
				.row("GET", "/admin/erp/jobs", 403, 403, 403, 200)
				// Errors of a logged user come back as themselves, through the ERROR dispatch
				.row("GET", "/no-such-path", 403, 403, 404, 404)
				.row("GET", "/location/not-a-number", 403, 403, 400, 400)
				.row("POST", "/sales-header/process-sale", "{not json", 403, 403, 400, 400)
				.row("DELETE", "/config", 405, 403, 405, 405)
				// The preflight is answered by the JWT filter, on the main chain only
				.row("OPTIONS", "/location", 200, 200, 200, 200)
				// /ho/** on a store: no filter, the same 401 for everyone, a user token opens nothing
				.row("GET", "/ho/ping", 401, 401, 401, 401)
				.row("POST", "/ho/heartbeat", "{}", 401, 401, 401, 401)
				.row("OPTIONS", "/ho/ping", 401, 401, 401, 401)
				.verify();
	}

	@Test
	@DisplayName("Without a licence: 402 everywhere but the bypassed paths, before the role of the caller counts")
	void licenceMissing() {
		store.withLicense();
		StatusMatrix matrix = matrix(); // the logins before the licence goes, though /login is bypassed too
		store.withoutLicense();
		matrix
				.row("GET", "/config", 200, 403, 200, 200)
				.row("GET", "/company-info", 200, 403, 200, 200)
				.row("GET", "/license/status", 200, 403, 200, 200)
				.row("GET", "/location", 402, 403, 402, 402)
				.row("GET", "/admin/erp/jobs", 402, 403, 402, 402)
				.row("GET", "/no-such-path", 402, 403, 402, 402)
				.row("GET", "/ho/ping", 401, 401, 401, 401)
				.verify();
		// Without a licence a user can still sign in, or the licence page could never be reached
		assertEquals(200, store.login("admin", "P@ssw0rd").status);
	}

	@Test
	@DisplayName("The login's answer: the token, the role, the permissions; a wrong password gets no token")
	void login() {
		store.withLicense();
		MigTestApplication.Answer cashier = store.login("cashier", "cashier");
		assertEquals(200, cashier.status);
		JSONObject body = new JSONObject(cashier.body);
		assertEquals(new TreeSet<>(Arrays.asList("appRoleId", "appRoleLabel", "appRoleName", "fullName", "isPosRole", "permissions",
				"status", "token")), new TreeSet<>(body.keySet()));
		assertEquals("POS_USER", body.getString("appRoleName"));
		assertTrue(body.getBoolean("isPosRole"));
		assertEquals("Bearer " + body.getString("token"), cashier.header("Authorization"));
		assertFalse(new JSONObject(store.login("admin", "P@ssw0rd").body).getBoolean("isPosRole"));
		assertNull(store.login("admin", "wrong").header("Authorization"));
	}

	@Test
	@DisplayName("The bodies of the refusals and errors, as the front reads them")
	void errorBodies() {
		store.withoutLicense();
		String[] admin = store.bearerOf("admin", "P@ssw0rd");
		body("licence-missing", 402, store.call("GET", "/location", null, admin));
		store.withLicense();
		String[] cashier = store.bearerOf("cashier", "cashier");
		body("wrong-password", 403, store.login("admin", "wrong"));
		body("anonymous", 403, store.call("GET", "/location", null));
		body("ho-chain", 401, store.call("GET", "/ho/ping", null));
		body("admin-only", 403, store.call("GET", "/admin/erp/jobs", null, cashier));
		body("not-found", 404, store.call("GET", "/no-such-path", null, admin));
		body("bad-id", 400, store.call("GET", "/location/not-a-number", null, admin));
		body("bad-json", 400, store.call("POST", "/sales-header/process-sale", "{not json", admin));
		body("not-whole-quantity", 400,
				store.call("POST", "/sales-header/process-sale", "{\"lines\":[{\"freeQuantity\":1.5}]}", admin));
		body("method-not-allowed", 405, store.call("DELETE", "/config", null, admin));
		body("api-refusal", 500, store.call("POST", "/location", "{}", admin));
	}

	@Test
	@DisplayName("Real answers through the real mapper: /config, the shape the front starts from, and an entity")
	void servedBodies() {
		store.withoutLicense();
		GoldenJson.assertMatches("golden/served/store-config.json", store.call("GET", "/config", null).body);
		store.withLicense();
		String seeded = store.call("GET", "/payment-method/1", null, store.bearerOf("admin", "P@ssw0rd")).body;
		// The moment of the seed changes at every run, not its form: the pattern stays in the comparison
		GoldenJson.assertMatches("golden/served/store-payment-method.json",
				seeded.replaceAll("\\d{4}-\\d{2}-\\d{2} \\| \\d{2}:\\d{2}:\\d{2}", "yyyy-MM-dd | HH:mm:ss"));
	}

	/** The body as the caller gets it; only the time of Boot's error page is taken out. */
	private static void body(String name, int status, MigTestApplication.Answer answer) {
		assertEquals(status, answer.status, name + ": " + answer.body);
		GoldenJson.assertMatches("golden/errors/store-" + name + ".json",
				answer.body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"(time)\""));
	}
}
