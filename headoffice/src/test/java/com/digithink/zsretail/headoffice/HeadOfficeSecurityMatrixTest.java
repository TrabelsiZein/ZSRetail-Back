package com.digithink.zsretail.headoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.AppRoleRepository;
import com.digithink.zsretail.core.repository.UserAccountRepository;
import com.digithink.zsretail.core.support.GoldenJson;
import com.digithink.zsretail.core.support.MigTestApplication;
import com.digithink.zsretail.core.support.StatusMatrix;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;

/**
 * Boot 4 migration, step 1.2: the status of a small set of URLs by caller on the head office's real security chains, over
 * HTTP, the store keys included. Written from what 2.3.9 answers, the reference for every later step: a changed cell is a
 * changed security behaviour.
 * <p>
 * Reading the table: /ho/** opens to a store key only, a user token opens nothing there, and a key opens nothing elsewhere.
 * A head office has no cashier: such a user gets no token ({@link #cashierGetsNoToken}), so the column here is the admin's.
 */
class HeadOfficeSecurityMatrixTest {

	private static final String HEARTBEAT = "{\"appVersion\":\"4.0.0\"}";
	private static final Pattern API_KEY = Pattern.compile("\"apiKey\":\"([^\"]+)\"");

	private static MigTestApplication headOffice;
	private static String goodKey;
	private static String inactiveKey;

	@BeforeAll
	static void start() {
		headOffice = MigTestApplication.start(HeadOfficeApplication.class, "headoffice");
		headOffice.withLicense();
		goodKey = newStore("MIG1", true);
		inactiveKey = newStore("MIG2", false);
	}

	@AfterAll
	static void stop() {
		if (headOffice != null) {
			headOffice.close();
		}
	}

	/** Through the admin API, as the page does: the only moment the key is shown. */
	private static String newStore(String code, boolean active) {
		MigTestApplication.Answer created = headOffice.call("POST", "/admin/headoffice/stores",
				"{\"code\":\"" + code + "\",\"name\":\"Store " + code + "\",\"active\":" + active + "}",
				headOffice.bearerOf("admin", "P@ssw0rd"));
		Matcher apiKey = API_KEY.matcher(created.body);
		assertTrue(created.status < 300 && apiKey.find(), created.status + ": " + created.body);
		return apiKey.group(1);
	}

	private static String[] key(String code, String key) {
		return new String[] { StoreApiKeyFilter.CODE_HEADER, code, StoreApiKeyFilter.KEY_HEADER, key };
	}

	private static StatusMatrix matrix() {
		return new StatusMatrix(headOffice).caller("anonymous").caller("bad token", "Authorization", "Bearer not.a.token")
				.caller("admin", headOffice.bearerOf("admin", "P@ssw0rd")).caller("store key", key("MIG1", goodKey))
				.caller("wrong key", key("MIG1", "not-the-key")).caller("unknown store", key("NOPE", goodKey))
				.caller("inactive store", key("MIG2", inactiveKey));
	}

	@Test
	@DisplayName("With a licence: /ho/** opens to a store key only, the rest to a logged user only")
	void licensed() {
		headOffice.withLicense();
		String company = headOffice.call("GET", "/company-info", null).body; // sent back as it is: the row does not change
		matrix()
				// The store-to-head-office chain: the same 401 for every refusal, a user token opens nothing
				.row("GET", "/ho/ping", 401, 401, 401, 200, 401, 401, 401)
				.row("POST", "/ho/heartbeat", HEARTBEAT, 401, 401, 401, 200, 401, 401, 401)
				.row("GET", "/ho/down/promotions?after=0", 401, 401, 401, 200, 401, 401, 401)
				// Its errors come back as themselves for a store, through the ERROR dispatch
				.row("GET", "/ho/no-such-path", 401, 401, 401, 404, 401, 401, 401)
				.row("POST", "/ho/heartbeat", "{not json", 401, 401, 401, 400, 401, 401, 401)
				// Public: open to all, but a token that fails verification is refused whatever the path
				.row("GET", "/config", 200, 403, 200, 200, 200, 200, 200)
				.row("GET", "/company-info", 200, 403, 200, 200, 200, 200, 200)
				.row("GET", "/license/status", 200, 403, 200, 200, 200, 200, 200)
				.row("GET", "/error", 403, 403, 500, 403, 403, 403, 403) // public from step 1.4 to 1.7 for its bodies; now as any path, for a logged user
				// How a path matches, written before step 1.6 moved the chains: exactly, whatever Spring MVC would accept
				// Spring 6 no longer takes /config/ for /config (step 1.7): 404 to the admin (200 before); no caller writes it
				.row("GET", "/config/", 403, 403, 404, 403, 403, 403, 403)
				.row("GET", "/login/x", 404, 403, 404, 404, 404, 404, 404)
				.row("GET", "/hox", 403, 403, 404, 403, 403, 403, 403)
				.row("GET", "/ho", 401, 401, 401, 401, 401, 401, 401) // in this chain, but the store key's filter reads only /ho/...
				// The main chain: a logged user; a store key is nobody here
				.row("GET", "/location", 403, 403, 200, 403, 403, 403, 403)
				.row("GET", "/admin/headoffice/stores", 403, 403, 200, 403, 403, 403, 403)
				.row("GET", "/admin/erp/jobs", 403, 403, 200, 403, 403, 403, 403)
				// Since the security fixes of 2026-11-09 (merged before step 1.7): the head office's keys of the two pages. The
				// admin's write names a user who does not exist, so no row changes
				.row("GET", "/user-account", 403, 403, 200, 403, 403, 403, 403)
				.row("PUT", "/user-account/999", "{\"fullName\":\"x\"}", 403, 403, 400, 403, 403, 403, 403)
				.row("PUT", "/company-info", company, 403, 403, 200, 403, 403, 403, 403)
				// Errors of a logged user come back as themselves, through the ERROR dispatch
				.row("GET", "/no-such-path", 403, 403, 404, 403, 403, 403, 403)
				.row("GET", "/location/not-a-number", 403, 403, 400, 403, 403, 403, 403)
				.verify();
	}

	@Test
	@DisplayName("Without a licence: a store's right key gets 402, the refusals stay 401, and the rest as on a store")
	void licenceMissing() {
		headOffice.withLicense();
		StatusMatrix matrix = matrix();
		headOffice.withoutLicense();
		matrix
				.row("GET", "/ho/ping", 401, 401, 401, 402, 401, 401, 401)
				.row("POST", "/ho/heartbeat", HEARTBEAT, 401, 401, 401, 402, 401, 401, 401)
				.row("GET", "/config", 200, 403, 200, 200, 200, 200, 200)
				.row("GET", "/license/status", 200, 403, 200, 200, 200, 200, 200)
				.row("GET", "/location", 402, 403, 402, 402, 402, 402, 402)
				.row("GET", "/admin/headoffice/stores", 402, 403, 402, 402, 402, 402, 402)
				.verify();
		assertEquals(200, headOffice.login("admin", "P@ssw0rd").status);
	}

	@Test
	@DisplayName("A cashier gets no token on a head office: 403 at the login, with the right password")
	void cashierGetsNoToken() {
		AppRole role = new AppRole("POS_USER", "Caissier", true);
		role.setCreatedBy("migtest");
		role = headOffice.bean(AppRoleRepository.class).save(role);
		UserAccount cashier = new UserAccount();
		cashier.setUsername("mig-cashier");
		cashier.setFullName("Migration cashier");
		cashier.setEmail("mig-cashier@zsretail.tn");
		cashier.setPassword(headOffice.bean(PasswordEncoder.class).encode("mig-cashier"));
		cashier.setActive(true);
		cashier.setAppRole(role);
		cashier.setCreatedBy("migtest");
		headOffice.bean(UserAccountRepository.class).save(cashier);

		MigTestApplication.Answer login = headOffice.login("mig-cashier", "mig-cashier");

		assertEquals(403, login.status);
		assertNull(login.header("Authorization"));
		assertFalse(login.body.contains("token"), login.body);
		body("cashier-login", 403, login);
	}

	@Test
	@DisplayName("The bodies of the refusals and errors, as a store and the front read them")
	void errorBodies() {
		headOffice.withoutLicense();
		String[] admin = headOffice.bearerOf("admin", "P@ssw0rd");
		body("licence-missing", 402, headOffice.call("GET", "/location", null, admin));
		body("licence-missing-store-key", 402, headOffice.call("GET", "/ho/ping", null, key("MIG1", goodKey)));
		headOffice.withLicense();
		body("wrong-password", 403, headOffice.login("admin", "wrong"));
		body("anonymous", 403, headOffice.call("GET", "/location", null));
		body("store-key-refused", 401, headOffice.call("GET", "/ho/ping", null, key("MIG1", "not-the-key")));
		body("user-token-on-ho-chain", 401, headOffice.call("GET", "/ho/ping", null, admin));
		body("ho-not-found", 404, headOffice.call("GET", "/ho/no-such-path", null, key("MIG1", goodKey)));
		body("ho-bad-json", 400, headOffice.call("POST", "/ho/heartbeat", "{not json", key("MIG1", goodKey)));
		body("not-found", 404, headOffice.call("GET", "/no-such-path", null, admin));
	}

	@Test
	@DisplayName("Real answers through the real mapper: /config, the shape the front starts from")
	void servedBodies() {
		headOffice.withoutLicense();
		GoldenJson.assertMatches("golden/served/headoffice-config.json", headOffice.call("GET", "/config", null).body);
	}

	/** The body as the caller gets it; only the time of Boot's error page is taken out. */
	private static void body(String name, int status, MigTestApplication.Answer answer) {
		assertEquals(status, answer.status, name + ": " + answer.body);
		GoldenJson.assertMatches("golden/errors/headoffice-" + name + ".json",
				answer.body.replaceAll("\"timestamp\":\"[^\"]*\"", "\"timestamp\":\"(time)\""));
	}
}
