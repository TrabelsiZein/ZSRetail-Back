package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.digithink.zsretail.headoffice.security.HeadOfficeErpCatalogueFilter;
import com.digithink.zsretail.headoffice.security.HeadOfficeWithoutStockFilter;
import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.support.TestModes;

/**
 * ERP catalogue, step 2: on a head office whose catalogue only comes from the ERP, the writes on the families,
 * sub-families and barcodes still open are refused with 403 {"error"}; everything else goes on. The bean exists only on
 * that head office.
 */
class HeadOfficeErpCatalogueFilterTest {

	static final String BODY = "{\"error\":\"The catalogue of this head office comes from the ERP: families,"
			+ " sub-families, items and barcodes are read-only here.\"}";

	private static boolean registered(MockEnvironment env) {
		return OnHeadOfficePullConditionTest.registered(env, HeadOfficeErpCatalogueFilter.class);
	}

	private static MockEnvironment catalogueOnlyHeadOffice() {
		MockEnvironment env = Installations.type("headoffice");
		env.setProperty("ownership.catalogue", "ERP");
		return env;
	}

	private static MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/zsretail/api" + path);
		request.setContextPath("/zsretail/api");
		request.setServletPath(path);
		return request;
	}

	@Test
	@DisplayName("Refused: PUT, PATCH, DELETE on a family or sub-family, every write on the barcodes; 403 with the message, not passed on")
	void refusedPaths() throws Exception {
		String[][] refused = { { "PUT", "/item-family/3" }, { "DELETE", "/item-family/3" }, { "PATCH", "/item-family/3" },
				{ "PUT", "/item-family" }, { "DELETE", "/item-family/" + "3/" }, { "PUT", "/item-sub-family/4" },
				{ "DELETE", "/item-sub-family/4" }, { "PATCH", "/item-sub-family/4" }, { "POST", "/item-barcode" },
				{ "POST", "/item-barcode/" }, { "PUT", "/item-barcode/5" }, { "DELETE", "/item-barcode/5" },
				{ "PATCH", "/item-barcode/5" }, { "delete", "/item-barcode/5" } };
		for (String[] r : refused) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();
			new HeadOfficeErpCatalogueFilter().doFilter(request(r[0], r[1]), response, chain);
			assertEquals(403, response.getStatus(), r[0] + " " + r[1]);
			assertNull(chain.getRequest(), r[0] + " " + r[1] + ": not passed on");
			assertEquals(BODY, response.getContentAsString(), r[0] + " " + r[1]);
			assertTrue(response.getContentType().startsWith("application/json"));
		}
	}

	@Test
	@DisplayName("Release 2.2: the price lists (every method) and a store's selling price list refused with their own message")
	void priceListsRefused() throws Exception {
		String[][] refused = { { "GET", "/admin/headoffice/price-lists" }, { "GET", "/admin/headoffice/price-lists/1" },
				{ "POST", "/admin/headoffice/price-lists" }, { "PUT", "/admin/headoffice/price-lists/1/lines" },
				{ "DELETE", "/admin/headoffice/price-lists/1" }, { "PUT", "/admin/headoffice/stores/7/selling-price-list" } };
		for (String[] r : refused) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();
			new HeadOfficeErpCatalogueFilter().doFilter(request(r[0], r[1]), response, chain);
			assertEquals(403, response.getStatus(), r[0] + " " + r[1]);
			assertNull(chain.getRequest(), r[0] + " " + r[1] + ": not passed on");
			assertEquals(HeadOfficeErpCatalogueFilter.PRICE_LIST_REFUSAL_BODY, response.getContentAsString(), r[0] + " " + r[1]);
		}
	}

	@Test
	@DisplayName("Passed: every GET (but the price lists), the creations the controllers refuse themselves, packs, images, supply prices, the stores, the ERP pages")
	void passedPaths() throws Exception {
		String[][] passed = { { "GET", "/item-family" }, { "GET", "/item-family/3" }, { "GET", "/item-sub-family/by-family/3" },
				{ "GET", "/item-barcode/item/12" }, { "GET", "/item-barcode/barcode/619" },
				{ "GET", "/item-barcode/items-with-barcodes" }, { "GET", "/item" }, { "GET", "/item/search" },
				// Refused by the controllers (isCatalogueFromErp, their own message): not this filter
				{ "POST", "/item-family" }, { "POST", "/item-sub-family" }, { "POST", "/item" }, { "PUT", "/item/12" },
				{ "DELETE", "/item/12" }, { "POST", "/item/quick-product" }, { "POST", "/admin/import/preview" },
				{ "POST", "/admin/import/execute" },
				// Open on purpose
				{ "PUT", "/item/12/package-flag" }, { "POST", "/item-composition" }, { "PUT", "/item-composition/7" },
				{ "DELETE", "/item-composition/7" }, { "POST", "/item-image/item/12" }, { "DELETE", "/item-image/item/12" },
				{ "PUT", "/admin/headoffice/supply-prices" }, { "POST", "/admin/erp/jobs/1/run" },
				{ "PUT", "/admin/headoffice/stores/1" }, { "PUT", "/admin/headoffice/stores/1/stock-point" },
				{ "GET", "/admin/headoffice/stores" },
				// Look-alike paths
				{ "POST", "/item-families" }, { "PUT", "/item-barcodes/5" }, { "OPTIONS", "/item-barcode/5" },
				{ "HEAD", "/item-family/3" } };
		for (String[] r : passed) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();
			new HeadOfficeErpCatalogueFilter().doFilter(request(r[0], r[1]), response, chain);
			assertNotNull(chain.getRequest(), r[0] + " " + r[1] + ": passed on");
			assertEquals(200, response.getStatus(), r[0] + " " + r[1]);
		}
	}

	@Test
	@DisplayName("The bean exists only on a head office with the catalogue only from the ERP")
	void whereTheBeanExists() {
		assertTrue(HeadOfficeErpCatalogueFilter.class.isAnnotationPresent(ConditionalOnHeadOfficeErpCatalogue.class));
		assertEquals(OnHeadOfficeErpCatalogueCondition.class,
				ConditionalOnHeadOfficeErpCatalogue.class.getAnnotation(org.springframework.context.annotation.Conditional.class)
						.value()[0]);

		assertTrue(registered(catalogueOnlyHeadOffice()));
		// The Happyness head office without stock, with the catalogue from the ERP: both filters
		MockEnvironment happyness = Installations.config("local/happyness_ho.properties");
		happyness.setProperty("ownership.catalogue", "ERP");
		assertTrue(registered(happyness));
		assertTrue(OnHeadOfficePullConditionTest.registered(happyness, HeadOfficeWithoutStockFilter.class));

		assertFalse(registered(Installations.type("headoffice")), "head office without an ERP");
		assertFalse(registered(Installations.type("store")), "store");
		assertFalse(registered(Installations.preset("headoffice-erp")), "head office whose ERP owns all three");
		assertFalse(registered(Installations.preset("store-erp")), "ERP store");
		assertFalse(registered(TestModes.erpOwners(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"))),
				"head office with the ERP owners, no type file");
		for (String variant : Installations.variants()) {
			MockEnvironment env = Installations.preset(variant);
			if (variant.startsWith("network-")) {
				env.setProperty("headoffice.url", "http://localhost:888/zsretail/api");
				env.setProperty("headoffice.api-key", "k");
			}
			assertFalse(registered(env), "variant " + variant);
		}
		for (String machine : Installations.machineFiles()) {
			assertFalse(registered(Installations.machine(machine)), "deploy/" + machine);
		}
		// configs/: only the Happyness head office, the real example of this mode, has the filter
		for (String config : Installations.configFiles()) {
			assertEquals(config.equals(ModeQuestionTruthTableTest.HAPPYNESS_HEAD_OFFICE),
					registered(Installations.config(config)), "configs/" + config);
		}
	}
}
