package com.digithink.zsretail.erp.api;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfiguredAddressTest {

	@Test
	@DisplayName("A well-written address, in any case, says nothing")
	void wellWritten() {
		for (String address : new String[] { "http://host:7048/BC", "https://host/x", "HTTP://HOST:24/x", "Https://host", " http://host " }) {
			assertNull(ConfiguredAddress.complaint("erp.emtop.base-url", address), address);
		}
	}

	@Test
	@DisplayName("An empty value is allowed and silent")
	void empty() {
		assertNull(ConfiguredAddress.complaint("headoffice.url", null));
		assertNull(ConfiguredAddress.complaint("headoffice.url", ""));
		assertNull(ConfiguredAddress.complaint("headoffice.url", "   "));
	}

	@Test
	@DisplayName("A badly written one: the line names the key, the value and what to do")
	void badlyWritten() {
		for (String address : new String[] { "localhost:888/zsretail/api", "192.168.10.166:24/test4", "host/api", "ftp://host", "http:/host" }) {
			String line = ConfiguredAddress.complaint("headoffice.url", address);
			assertTrue(line != null && line.contains("headoffice.url=" + address) && line.contains("http://"), address);
		}
	}
}
