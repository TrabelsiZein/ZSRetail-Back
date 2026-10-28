package com.digithink.zsretail.connector.zen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;

import com.digithink.zsretail.connector.zen.config.ZenStartupCheck;
import com.digithink.zsretail.support.Installations;

/**
 * Item variants (ZEN), step 8: the startup refusals of ZEN's connector, with their messages, and its startup line. Here
 * (network-tests) because the check asks the application whether the installation is a head office whose catalogue
 * only comes from the ERP.
 */
class ZenStartupCheckTest {

	static final String BASE_URL = "http://nav.test:7158/ZEN";

	/** The settings of a ZEN head office: the three keys of ZEN's NAV, the credentials, the default VAT. */
	static MockEnvironment validEnvironment() {
		MockEnvironment env = Installations.type("headoffice");
		env.setProperty("ownership.catalogue", "ERP");
		env.setProperty("erp.connector", "zen");
		env.setProperty("erp.zen.base-url", BASE_URL);
		env.setProperty("erp.zen.company", "ZEN SA");
		env.setProperty("erp.zen.username", "user");
		env.setProperty("erp.zen.password", "secret");
		env.setProperty("erp.zen.default-vat", "19");
		return env;
	}

	private static String refusal(MockEnvironment env) {
		return assertThrows(IllegalStateException.class, () -> ZenStartupCheck.check(env)).getMessage();
	}

	/** The valid settings with one key set (or removed when value is null). */
	@SuppressWarnings("unchecked")
	private static MockEnvironment with(String key, String value) {
		MockEnvironment env = validEnvironment();
		if (value == null) {
			((Map<String, Object>) env.getPropertySources().get(MockPropertySource.MOCK_PROPERTIES_PROPERTY_SOURCE_NAME)
					.getSource()).remove(key);
		} else {
			env.setProperty(key, value);
		}
		return env;
	}

	@Test
	@DisplayName("Valid settings pass; the startup line names the address, the API, the company and the pages, never the password")
	void valid() {
		ZenStartupCheck.check(validEnvironment());
		ZenStartupCheck.check(with("erp.zen.default-vat", " 0 "));
		ZenStartupCheck.check(with("erp.zen.domain", "ZEN"));
		ZenStartupCheck.check(with("erp.zen.page.barcodes", "http://nav.test:7048/ZEN/ODataV4/Company('ZEN')/ItemBarCodePOS"));
		MockEnvironment env = validEnvironment();
		new ZenStartupCheck(env); // the bean checks and logs
		String line = ZenStartupCheck.summary(env);
		assertEquals("ERP connector zen (read only, GET): " + BASE_URL + ", API api/sat/zsretail/v1.0, company"
				+ " ZEN SA, pages stockPoints, itemCategories, barCodes", line);
		assertFalse(line.contains("secret"));
	}

	@Test
	@DisplayName("Only on a head office whose catalogue only comes from the ERP")
	void onlyThatHeadOffice() {
		String message = "Invalid value 'zen' for property erp.connector: this connector only reads a catalogue, on a head"
				+ " office whose catalogue only comes from the ERP (the head office application, ownership.catalogue=ERP,"
				+ " ownership.customers and ownership.supply not ERP).";
		assertEquals(message, refusal(with("ownership.catalogue", "LOCAL")), "head office without an ERP");
		MockEnvironment store = Installations.type("store");
		store.setProperty("erp.connector", "zen");
		assertEquals(message, refusal(store), "store");
	}

	@Test
	@DisplayName("Required keys, the address, the VAT and the numbers: refused naming the key")
	void refusals() {
		for (String key : new String[] { "base-url", "username", "password" }) {
			String expected = "Missing value for property erp.zen." + key + ": required with erp.connector=zen.";
			assertEquals(expected, refusal(with("erp.zen." + key, "  ")), key + " blank");
			assertEquals(expected, refusal(with("erp.zen." + key, null)), key + " absent");
		}
		// Step 10: the company by its name or by its id; neither: refused
		String company = "Missing value for property erp.zen.company: the company's name (or its id in the API,"
				+ " erp.zen.company-id), required with erp.connector=zen.";
		assertEquals(company, refusal(with("erp.zen.company", " ")));
		assertEquals(company, refusal(with("erp.zen.company", null)));
		MockEnvironment byId = with("erp.zen.company", null);
		byId.setProperty("erp.zen.company-id", "89c8b192-3f2e-f111-9f24-7ced8db3f120");
		ZenStartupCheck.check(byId);
		assertEquals("ERP connector zen (read only, GET): " + BASE_URL + ", API api/sat/zsretail/v1.0, company id"
				+ " 89c8b192-3f2e-f111-9f24-7ced8db3f120, pages stockPoints, itemCategories, barCodes",
				ZenStartupCheck.summary(byId), "the id is used as it is");
		assertEquals("Invalid value 'nav.test:7158/ZEN' for property erp.zen.base-url: the address of the NAV instance,"
				+ " http://host:port/instance", refusal(with("erp.zen.base-url", "nav.test:7158/ZEN")));
		String vat = "for property erp.zen.default-vat: a whole number from 0 to 100 (the VAT of a row that gives none, e.g. 19)";
		assertEquals("Invalid value '' " + vat, refusal(with("erp.zen.default-vat", null)));
		assertEquals("Invalid value '19.5' " + vat, refusal(with("erp.zen.default-vat", "19.5")));
		assertEquals("Missing value for property erp.zen.page.stock-points: remove the line to use the default, or write"
				+ " the value.", refusal(with("erp.zen.page.stock-points", " ")));
		assertEquals("Invalid value '0' for property erp.zen.packet-size: a whole number from 1 to 5000",
				refusal(with("erp.zen.packet-size", "0")));
		assertEquals("Invalid value 'yes' for property erp.zen.dry-run: true (read and compare only) or false",
				refusal(with("erp.zen.dry-run", "yes")));
	}

	@Test
	@DisplayName("Step 9, the invoices: every key optional; a blank line, a wrong lines property or prefix, page-size out of range refused naming the key")
	void invoiceRefusals() {
		ZenStartupCheck.check(with("erp.zen.invoices.number-prefix", " FVX "));
		ZenStartupCheck.check(with("erp.zen.invoices.page-size", "1000"));
		ZenStartupCheck.check(with("erp.zen.invoices.lines-expand", "salesInvoiceLines"));
		ZenStartupCheck.check(with("erp.zen.invoices.api-path", "api/sat/franchise/v2.0"));
		for (String key : new String[] { "invoices.page", "invoices.api-path", "invoices.seller-name" }) {
			assertEquals("Missing value for property erp.zen." + key + ": remove the line to use the default, or write the"
					+ " value.", refusal(with("erp.zen." + key, " ")), key);
		}
		assertEquals("Invalid value 'Sales Lines' for property erp.zen.invoices.lines-expand: the navigation property of"
				+ " the lines, letters, digits and _ (e.g. salesInvoiceLines)",
				refusal(with("erp.zen.invoices.lines-expand", "Sales Lines")));
		assertEquals("Invalid value 'FV E' for property erp.zen.invoices.number-prefix: the start of every invoice number,"
				+ " without spaces, e.g. FVE (the year follows in 2 digits)",
				refusal(with("erp.zen.invoices.number-prefix", "FV E")));
		assertEquals("Invalid value '0' for property erp.zen.invoices.page-size: a whole number from 1 to 1000",
				refusal(with("erp.zen.invoices.page-size", "0")));
		assertEquals("Invalid value '1001' for property erp.zen.invoices.page-size: a whole number from 1 to 1000",
				refusal(with("erp.zen.invoices.page-size", "1001")));
	}

	@Test
	@DisplayName("Step 9, headoffice.supply.source=ERP: the company by its name or its id (the same companies(id)); the startup line names the invoices page")
	void invoicesWithErpSupply() {
		MockEnvironment env = with("erp.zen.company", "ESP");
		env.setProperty("headoffice.supply.source", "ERP");
		ZenStartupCheck.check(env);
		assertEquals("ERP connector zen (read only, GET): " + BASE_URL + ", API api/sat/zsretail/v1.0, company ESP, pages"
				+ " stockPoints, itemCategories, barCodes, invoices api/sat/franchise/v1.0 salesInvoices (lines"
				+ " salesInvoiceLines, prefix FVE, 50 per year and run, seller ZEN)", ZenStartupCheck.summary(env));
		env.setProperty("erp.zen.invoices.page-size", "20");
		env.setProperty("erp.zen.invoices.seller-name", "ZEN Franchise");
		assertTrue(ZenStartupCheck.summary(env).endsWith("prefix FVE, 20 per year and run, seller ZEN Franchise)"));

		MockEnvironment byId = with("erp.zen.company", null);
		byId.setProperty("erp.zen.company-id", "1d6c4f00-6ac9-f111-9db8-ad3d0c589c9c");
		byId.setProperty("headoffice.supply.source", "ERP");
		ZenStartupCheck.check(byId); // the id alone is enough: the invoices API is under the same companies(id)
		assertTrue(ZenStartupCheck.summary(byId).contains("company id 1d6c4f00-6ac9-f111-9db8-ad3d0c589c9c, pages"));
	}

	@Test
	@DisplayName("3.1.1, the item images: products.url and products.api-key both or neither, the address http(s); the"
			+ " startup line names the address, never the key")
	void products() {
		String url = "https://api-zentral.zen.com.tn/api/products/advanced-search-zs";
		MockEnvironment env = with("erp.zen.products.url", url);
		env.setProperty("erp.zen.products.api-key", "the-key");
		ZenStartupCheck.check(env);
		String line = ZenStartupCheck.summary(env);
		assertTrue(line.endsWith(", pages stockPoints, itemCategories, barCodes, item images from " + url), line);
		assertFalse(line.contains("the-key"));

		assertEquals("Missing value for property erp.zen.products.api-key: the item images need both"
				+ " erp.zen.products.url and erp.zen.products.api-key (or neither: no images).",
				refusal(with("erp.zen.products.url", url)));
		assertEquals("Missing value for property erp.zen.products.url: the item images need both"
				+ " erp.zen.products.url and erp.zen.products.api-key (or neither: no images).",
				refusal(with("erp.zen.products.api-key", "the-key")));
		MockEnvironment ftp = with("erp.zen.products.url", "ftp://zen/products");
		ftp.setProperty("erp.zen.products.api-key", "the-key");
		assertTrue(refusal(ftp).startsWith("Invalid value 'ftp://zen/products' for property erp.zen.products.url"));
		ZenStartupCheck.check(with("erp.zen.products.url", " ")); // both blank: no images
	}
}
