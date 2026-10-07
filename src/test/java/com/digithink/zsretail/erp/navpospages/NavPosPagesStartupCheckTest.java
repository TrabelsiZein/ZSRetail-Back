package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.erp.navpospages.config.NavPosPagesStartupCheck;
import com.digithink.zsretail.support.Installations;

/** ERP catalogue, step 5: each startup refusal of the connector, with its message; the startup line. */
class NavPosPagesStartupCheckTest {

	private static String refusal(MockEnvironment env) {
		return assertThrows(IllegalStateException.class, () -> NavPosPagesStartupCheck.check(env)).getMessage();
	}

	/** The valid settings with one key set (or removed when value is null). */
	@SuppressWarnings("unchecked")
	private static MockEnvironment with(String key, String value) {
		MockEnvironment env = NavPosPagesTestSupport.validEnvironment();
		if (value == null) {
			((Map<String, Object>) env.getPropertySources().get(org.springframework.mock.env.MockPropertySource.MOCK_PROPERTIES_PROPERTY_SOURCE_NAME)
					.getSource()).remove(key);
		} else {
			env.setProperty(key, value);
		}
		return env;
	}

	@Test
	@DisplayName("Valid settings pass; the startup line names the address, company, location and pages, never the password")
	void valid() {
		NavPosPagesStartupCheck.check(NavPosPagesTestSupport.validEnvironment());
		NavPosPagesStartupCheck.check(with("erp.navpospages.default-vat", " 0 "));
		NavPosPagesStartupCheck.check(with("erp.navpospages.price-includes-vat", "FALSE"));
		NavPosPagesStartupCheck.check(with("erp.navpospages.barcode-page-size", "5000"));
		NavPosPagesStartupCheck.check(with("erp.navpospages.domain", null));
		MockEnvironment env = with("erp.navpospages.page.items", "OtherStock");
		new NavPosPagesStartupCheck(env); // the bean checks and logs
		String line = NavPosPagesStartupCheck.summary(env);
		assertEquals("ERP connector navpospages (read only, GET): " + NavPosPagesTestSupport.BASE_URL
				+ ", company HAPPYNESS, location FRANCHISE, pages ItemCategory, OtherStock, ItemBarCodePOS", line);
		assertFalse(line.contains("secret"));
	}

	@Test
	@DisplayName("One ERP connector per installation: erp.dynamicsnav.enabled=true as well is refused")
	void twoConnectors() {
		assertEquals("Invalid combination: erp.navpospages.enabled=true with erp.dynamicsnav.enabled=true. One ERP"
				+ " connector per installation: set one of them to false.",
				refusal(with("erp.dynamicsnav.enabled", " TRUE ")));
		NavPosPagesStartupCheck.check(with("erp.dynamicsnav.enabled", "false"));
	}

	@Test
	@DisplayName("Only on a head office whose catalogue only comes from the ERP")
	void onlyThatHeadOffice() {
		String message = "Invalid value 'true' for property erp.navpospages.enabled: this connector only reads a catalogue,"
				+ " on a head office whose catalogue only comes from the ERP (node.type=HEAD_OFFICE,"
				+ " ownership.catalogue=ERP, ownership.customers and ownership.supply not ERP).";
		assertEquals(message, refusal(with("ownership.catalogue", "LOCAL")), "head office without an ERP");
		MockEnvironment allErp = NavPosPagesTestSupport.validEnvironment();
		allErp.setProperty("ownership.customers", "ERP");
		allErp.setProperty("ownership.supply", "ERP");
		assertEquals(message, refusal(allErp), "head office whose ERP owns all three");
		MockEnvironment store = Installations.type("store");
		store.setProperty("erp.navpospages.enabled", "true");
		assertEquals(message, refusal(store), "store");
		MockEnvironment erpStore = Installations.preset("store-erp");
		erpStore.setProperty("erp.dynamicsnav.enabled", "false");
		assertEquals(message, refusal(erpStore), "store whose ERP owns all three");
	}

	@Test
	@DisplayName("Blank or absent address, company, user, password or location: refused naming the key")
	void requiredKeys() {
		for (String key : new String[] { "base-url", "company", "username", "password", "location-code" }) {
			String expected = "Missing value for property erp.navpospages." + key
					+ ": required when erp.navpospages.enabled=true.";
			assertEquals(expected, refusal(with("erp.navpospages." + key, "  ")), key + " blank");
			assertEquals(expected, refusal(with("erp.navpospages." + key, null)), key + " absent");
		}
	}

	@Test
	@DisplayName("default-vat, price-includes-vat, barcode-page-size and the timeouts: wrong values refused naming the key")
	void values() {
		for (String vat : new String[] { null, "", "abc", "19.5", "-1", "101" }) {
			assertEquals("Invalid value '" + (vat == null ? "" : vat) + "' for property erp.navpospages.default-vat: a whole"
					+ " number from 0 to 100 (the VAT given to every item, e.g. 19)",
					refusal(with("erp.navpospages.default-vat", vat)), "default-vat " + vat);
		}
		for (String includes : new String[] { null, "", "yes", "1" }) {
			assertEquals("Invalid value '" + (includes == null ? "" : includes) + "' for property"
					+ " erp.navpospages.price-includes-vat: true (Unit_Price includes the VAT) or false (Unit_Price is"
					+ " before VAT)", refusal(with("erp.navpospages.price-includes-vat", includes)), "includes " + includes);
		}
		for (String size : new String[] { "0", "5001", "x", "" }) {
			assertEquals("Invalid value '" + size + "' for property erp.navpospages.barcode-page-size: a whole number"
					+ " from 1 to 5000", refusal(with("erp.navpospages.barcode-page-size", size)), "size " + size);
		}
		// Step 6
		for (String cap : new String[] { "0", "-1", "many" }) {
			assertEquals("Invalid value '" + cap + "' for property erp.navpospages.max-changes-per-run: a whole number, at"
					+ " least 1", refusal(with("erp.navpospages.max-changes-per-run", cap)), "cap " + cap);
		}
		for (String percent : new String[] { "101", "-1", "10%" }) {
			assertEquals("Invalid value '" + percent + "' for property erp.navpospages.deactivate-guard-percent: a whole"
					+ " number from 0 to 100", refusal(with("erp.navpospages.deactivate-guard-percent", percent)), percent);
		}
		NavPosPagesStartupCheck.check(with("erp.navpospages.deactivate-guard-percent", "0"));
		assertEquals("Invalid value 'yes' for property erp.navpospages.dry-run: true (read and compare only) or false",
				refusal(with("erp.navpospages.dry-run", "yes")));
		NavPosPagesStartupCheck.check(with("erp.navpospages.dry-run", " TRUE "));
		for (String key : new String[] { "connect-timeout-seconds", "read-timeout-seconds" }) {
			for (String timeout : new String[] { "0", "-5", "ten" }) {
				assertEquals("Invalid value '" + timeout + "' for property erp.navpospages." + key + ": a whole number,"
						+ " at least 1", refusal(with("erp.navpospages." + key, timeout)), key + " " + timeout);
			}
		}
	}
}
