package com.digithink.zsretail.connector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.connector.emtop.config.DynamicsNavConfig;
import com.digithink.zsretail.connector.emtop.config.DynamicsNavProperties;
import com.digithink.zsretail.support.ErrorLines;

/**
 * Boot 4 migration, step 1.3b: a badly written address of the connector emtop, which has no startup check, gives one clear
 * ERROR line at the start, naming the key, and the start goes on: no new refused start. For happyness:
 * NavPosPagesStartupCheckTest; for headoffice.url: HeadOfficeClientTest.
 */
class BadlyWrittenAddressLogTest {

	private static final String BAD = "192.168.1.1:7048/BC";

	private static List<String> errors(String baseUrl) throws Exception {
		DynamicsNavProperties properties = new DynamicsNavProperties();
		properties.setBaseUrl(baseUrl);
		properties.setUsername("user");
		properties.setPassword("not-a-password");
		properties.setDomain("domain");
		return ErrorLines.of(DynamicsNavConfig.class, () -> assertNotNull(new DynamicsNavConfig().dynamicsNavRestTemplate(properties)));
	}

	@Test
	@DisplayName("emtop: one ERROR line naming erp.emtop.base-url, the start goes on; silent when empty or well written")
	void emtop() throws Exception {
		List<String> errors = errors(BAD);
		assertEquals(1, errors.size(), errors.toString());
		assertTrue(errors.get(0).contains("erp.emtop.base-url=" + BAD), errors.get(0));
		assertTrue(errors(null).isEmpty());
		assertTrue(errors("").isEmpty());
		assertTrue(errors("http://host:24/test4/ODataV4").isEmpty());
		assertTrue(errors("HTTP://HOST:24/test4/ODataV4").isEmpty(), "in capitals it works");
	}
}
