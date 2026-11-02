package com.digithink.zsretail.connector.parafendri.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * ParaFendri, step 0: the startup refusals (the message names the key) and the startup line (never a password).
 */
class ParafendriStartupCheckTest {

	static final String BASE_URL = "http://bc.test:7048/BC280";
	static final String COMPANY_ID = "6b29fc40-ca47-1067-b31d-00dd010662da";

	/** Every required key set, then key given this value (null: the key left out). */
	static MockEnvironment with(String key, String value) {
		Map<String, String> lines = new LinkedHashMap<>();
		lines.put("erp.connector", "parafendri");
		lines.put("erp.parafendri.base-url", BASE_URL);
		lines.put("erp.parafendri.company-id", COMPANY_ID);
		lines.put("erp.parafendri.username", "user");
		lines.put("erp.parafendri.password", "secret");
		if (key != null) {
			lines.put(key, value);
		}
		MockEnvironment env = new MockEnvironment();
		lines.forEach((name, text) -> {
			if (text != null) {
				env.setProperty(name, text);
			}
		});
		return env;
	}

	static String refusal(MockEnvironment env) {
		return assertThrows(IllegalStateException.class, () -> ParafendriStartupCheck.check(env)).getMessage();
	}

	@Test
	@DisplayName("Accepted: the required keys, a domain, https, a GUID in capitals, the timeouts")
	void accepted() {
		ParafendriStartupCheck.check(with(null, null));
		ParafendriStartupCheck.check(with("erp.parafendri.domain", "PF"));
		ParafendriStartupCheck.check(with("erp.parafendri.base-url", "https://bc.test/BC280/"));
		ParafendriStartupCheck.check(with("erp.parafendri.company-id", " " + COMPANY_ID.toUpperCase() + " "));
		ParafendriStartupCheck.check(with("erp.parafendri.read-timeout-seconds", "60"));
	}

	@Test
	@DisplayName("The startup line: address, API, company id, never the password")
	void summary() {
		assertEquals("ERP connector parafendri (read only, GET): " + BASE_URL + ", API api/sat/zsretail/v1.0, company id "
				+ COMPANY_ID, ParafendriStartupCheck.summary(with(null, null)));
	}

	@Test
	@DisplayName("Refused: a required key blank or absent, naming it")
	void missing() {
		for (String key : new String[] { "base-url", "company-id", "username", "password" }) {
			String expected = "Missing value for property erp.parafendri." + key + ": required with"
					+ " erp.connector=parafendri.";
			assertEquals(expected, refusal(with("erp.parafendri." + key, "  ")), key + " blank");
			assertEquals(expected, refusal(with("erp.parafendri." + key, null)), key + " absent");
		}
	}

	@Test
	@DisplayName("Refused: a base-url not well formed, a company id not a GUID, a timeout not a whole number")
	void invalid() {
		String url = "for property erp.parafendri.base-url: the address of the Business Central instance,"
				+ " http://host:port/instance";
		assertEquals("Invalid value 'bc.test:7048/BC280' " + url, refusal(with("erp.parafendri.base-url", "bc.test:7048/BC280")));
		assertEquals("Invalid value 'ftp://bc.test/BC280' " + url, refusal(with("erp.parafendri.base-url", "ftp://bc.test/BC280")));
		assertEquals("Invalid value 'http://bc.test/BC280?x=1' " + url,
				refusal(with("erp.parafendri.base-url", "http://bc.test/BC280?x=1")));
		assertEquals("Invalid value 'http://bc test/BC280' " + url, refusal(with("erp.parafendri.base-url", "http://bc test/BC280")));
		assertEquals("Invalid value 'PARAFENDRI' for property erp.parafendri.company-id: the company's id in the API, a GUID"
				+ " (e.g. 89c8b192-3f2e-f111-9f24-7ced8db3f120)", refusal(with("erp.parafendri.company-id", "PARAFENDRI")));
		assertEquals("Invalid value '0' for property erp.parafendri.connect-timeout-seconds: a whole number, at least 1",
				refusal(with("erp.parafendri.connect-timeout-seconds", "0")));
		assertEquals("Invalid value '1.5' for property erp.parafendri.read-timeout-seconds: a whole number, at least 1",
				refusal(with("erp.parafendri.read-timeout-seconds", "1.5")));
	}
}
