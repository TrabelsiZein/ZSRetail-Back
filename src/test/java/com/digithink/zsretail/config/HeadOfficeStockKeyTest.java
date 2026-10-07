package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Conditional;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import com.digithink.zsretail.controller.AppConfigAPI;
import com.digithink.zsretail.dto.AppConfigDTO;
import com.digithink.zsretail.headoffice.security.HeadOfficeWithoutStockFilter;
import com.digithink.zsretail.model.enumeration.LicenseStatus;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.LicenseService;
import com.digithink.zsretail.service.LoyaltyService;
import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.support.TestModes;

/**
 * headoffice.stock.enabled (default true in the head office type file): read on a head office only, strictly true or
 * false; false gives a head office without stock (the refusal filter exists, /config headOfficeStock false). A store never
 * reads it: every store kind, with the key absent or false (or even unreadable) in its file, answers headOfficeStock true
 * and has no filter. The filter refuses the purchase, vendor, purchase invoice, stock report and stock adjustment paths
 * only. Bare bean registry and plain servlet mocks (no Spring context).
 */
class HeadOfficeStockKeyTest {

	private static final String KEY = "headoffice.stock.enabled";

	/** Every store kind: the old preset shapes and the store files of deploy/ (with an ERP, without, linked). */
	private static List<MockEnvironment> stores() {
		List<MockEnvironment> stores = new ArrayList<>();
		for (String preset : Arrays.asList("store", "store-erp", "network-store", "network-store-erp")) {
			MockEnvironment env = Installations.preset(preset);
			if (preset.startsWith("network")) { // the head office link, as NetworkPresetTruthTableTest gives it
				env.setProperty("headoffice.url", "http://CHANGE_ME:888/zsretail/api");
				env.setProperty("headoffice.api-key", "CHANGE_ME");
			}
			stores.add(env);
		}
		for (String file : Arrays.asList("customers/erp-prod.properties", "customers/store-prod.properties",
				"dev/store-b.properties", "dev/store-c.properties")) {
			stores.add(Installations.machine(file));
		}
		return stores;
	}

	private static MockEnvironment headOffice() {
		return Installations.type("headoffice");
	}

	private static boolean filterRegistered(MockEnvironment env) {
		return OnHeadOfficePullConditionTest.registered(env, HeadOfficeWithoutStockFilter.class);
	}

	@Test
	@DisplayName("The head office type file says true: the head office keeps its stock, no filter, /config true, no summary word")
	void headOfficeDefault() throws Exception {
		MockEnvironment env = headOffice();
		assertEquals("true", env.getProperty(KEY));
		assertFalse(NodeOwnership.isHeadOfficeWithoutStockSet(env));
		assertFalse(TestModes.of(env).isHeadOfficeWithoutStock());
		assertFalse(filterRegistered(env));
		assertTrue(config(env).isHeadOfficeStock());
		assertFalse(InstallationSummary.summary(env, NodeOwnership.resolve(env)).contains("stock off"));

		MockEnvironment absent = new MockEnvironment().withProperty("node.type", "HEAD_OFFICE");
		assertFalse(NodeOwnership.isHeadOfficeWithoutStockSet(absent), "absent: true");
		assertFalse(filterRegistered(absent));
		assertFalse(filterRegistered(headOffice().withProperty(KEY, " TRUE ")));
	}

	@Test
	@DisplayName("false on a head office (any case and spacing): without stock, the filter exists, /config false, the summary says it")
	void headOfficeWithoutStock() throws Exception {
		for (String value : Arrays.asList("false", " FALSE ", "False")) {
			MockEnvironment env = headOffice().withProperty(KEY, value);
			assertTrue(NodeOwnership.isHeadOfficeWithoutStockSet(env), value);
			assertTrue(TestModes.of(env).isHeadOfficeWithoutStock(), value);
			assertTrue(filterRegistered(env), value);
			assertFalse(config(env).isHeadOfficeStock(), value);
		}
		MockEnvironment env = headOffice().withProperty(KEY, "false");
		assertTrue(InstallationSummary.summary(env, NodeOwnership.resolve(env))
				.contains("sales to nowhere, head office stock off, "));
		assertTrue(HeadOfficeWithoutStockFilter.class.isAnnotationPresent(ConditionalOnHeadOfficeWithoutStock.class));
		assertEquals(OnHeadOfficeWithoutStockCondition.class,
				ConditionalOnHeadOfficeWithoutStock.class.getAnnotation(Conditional.class).value()[0]);
	}

	@Test
	@DisplayName("An added gate: a head office with an ERP and false keeps its ERP answers, and the filter exists too")
	void addedToTheErpGates() {
		MockEnvironment env = headOffice().withProperty("ownership.catalogue", "ERP")
				.withProperty("ownership.customers", "ERP").withProperty("ownership.supply", "ERP").withProperty(KEY, "false");
		ApplicationModeService mode = TestModes.of(env);
		assertTrue(mode.isSupplyFromErp());
		assertTrue(mode.hasErp());
		assertTrue(mode.isHeadOfficeWithoutStock());
		assertTrue(filterRegistered(env));
	}

	@Test
	@DisplayName("Strict on a head office: any other value stops the startup with a message naming the key")
	void strictOnHeadOffice() {
		for (String value : Arrays.asList("flase", "", " ", "yes", "0", "no")) {
			MockEnvironment env = headOffice().withProperty(KEY, value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env), value);
			assertEquals("Invalid value '" + value + "' for property headoffice.stock.enabled: true (the head office keeps"
					+ " its own stock and makes purchases) or false (no stock, no purchases)", e.getMessage());
			assertThrows(IllegalStateException.class, () -> filterRegistered(env), value);
		}
	}

	@Test
	@DisplayName("Every store kind (ERP, no ERP, linked to a head office), key absent or false in its file:"
			+ " /config headOfficeStock true, no filter bean, never read (even a bad value starts)")
	void storeNeverReadsTheKey() throws Exception {
		for (String value : Arrays.asList(null, "false", " FALSE ", "flase")) {
			for (MockEnvironment store : stores()) {
				if (value != null) {
					store.setProperty(KEY, value);
				}
				String label = store.getProperty("spring.datasource.url") + " " + store.getProperty("ownership.supply")
						+ " key " + value;
				NodeOwnership resolved = NodeOwnership.resolve(store);
				assertEquals("STORE", resolved.getNodeType().name(), label);
				assertFalse(NodeOwnership.isHeadOfficeWithoutStockSet(store), label);
				assertFalse(TestModes.of(store).isHeadOfficeWithoutStock(), label);
				assertFalse(filterRegistered(store), label);
				assertTrue(config(store).isHeadOfficeStock(), label);
				assertFalse(InstallationSummary.summary(store, resolved).contains("stock off"), label);
			}
		}
		// The three kinds asked for are among them
		List<MockEnvironment> kinds = stores();
		assertTrue(kinds.stream().anyMatch(s -> TestModes.of(s).isSupplyFromErp()), "a store with an ERP");
		assertTrue(kinds.stream().anyMatch(s -> !TestModes.of(s).hasErp() && !TestModes.of(s).isHeadOfficeLinked()),
				"a store without an ERP");
		assertTrue(kinds.stream().anyMatch(s -> TestModes.of(s).isHeadOfficeLinked()), "a store linked to a head office");
	}

	// The filter

	@Test
	@DisplayName("Filter: 403 {error} on purchases, vendors, purchase invoices, the stock and purchase reports and adjust-stock,"
			+ " any method; every other path goes on")
	void filterPaths() throws Exception {
		String[][] refused = { { "GET", "/purchase-header" }, { "POST", "/purchase-header" },
				{ "GET", "/purchase-header/history" }, { "GET", "/purchase-header/vendor-balance" },
				{ "POST", "/purchase-header/process-purchase" }, { "PUT", "/purchase-header/7" },
				{ "DELETE", "/purchase-header/7" }, { "PATCH", "/purchase-header/7/set-paid" }, { "GET", "/vendor" },
				{ "GET", "/vendor/" }, { "GET", "/vendor/3" }, { "GET", "/vendor/admin/paginated" }, { "POST", "/vendor" },
				{ "DELETE", "/vendor/3" }, { "GET", "/admin/purchase-invoices" }, { "POST", "/admin/purchase-invoices" },
				{ "GET", "/admin/purchase-invoices/4" }, { "GET", "/report/stock" }, { "GET", "/report/stock/" },
				{ "GET", "/report/stock-movements" }, { "GET", "/report/purchases" },
				{ "POST", "/item/12/adjust-stock" } };
		for (String[] r : refused) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();
			new HeadOfficeWithoutStockFilter().doFilter(request(r[0], r[1]), response, chain);
			assertEquals(403, response.getStatus(), r[0] + " " + r[1]);
			assertNull(chain.getRequest(), r[0] + " " + r[1] + ": not passed on");
			assertEquals("{\"error\":\"This head office keeps no stock and makes no purchases"
					+ " (headoffice.stock.enabled=false).\"}", response.getContentAsString());
			assertTrue(response.getContentType().startsWith("application/json"));
		}
		String[][] passed = { { "GET", "/item/search" }, { "GET", "/item/12" }, { "PUT", "/item/12" },
				{ "GET", "/item/12/adjust-stock/x" }, { "GET", "/report/sales" }, { "GET", "/report/stock-value" },
				{ "GET", "/vendors" }, { "GET", "/purchase-headers" }, { "GET", "/location" },
				{ "GET", "/admin/headoffice/deliveries" }, { "POST", "/admin/headoffice/deliveries/1/validate" },
				{ "GET", "/admin/headoffice/stock" }, { "GET", "/admin/headoffice/supply-prices" },
				{ "GET", "/admin/headoffice/supply-invoices" }, { "GET", "/admin/headoffice/supply-invoices/balances" },
				{ "POST", "/ho/supply/stock" }, { "GET", "/admin/inventory-counts" }, { "GET", "/config" } };
		for (String[] r : passed) {
			MockHttpServletResponse response = new MockHttpServletResponse();
			MockFilterChain chain = new MockFilterChain();
			new HeadOfficeWithoutStockFilter().doFilter(request(r[0], r[1]), response, chain);
			assertNotNull(chain.getRequest(), r[0] + " " + r[1] + ": passed on");
			assertEquals(200, response.getStatus(), r[0] + " " + r[1]);
		}
	}

	private static MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, "/zsretail/api" + path);
		request.setContextPath("/zsretail/api");
		request.setServletPath(path);
		return request;
	}

	// GET /config, built like AppConfigAPITest

	private static AppConfigDTO config(MockEnvironment env) throws Exception {
		LicenseService license = new LicenseService(null, null, null) {
			@Override
			public LicenseStatus getStatus() {
				return LicenseStatus.VALID;
			}

			@Override
			public long getDaysUntilExpiry() {
				return 100;
			}
		};
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return null;
			}
		};
		LoyaltyService loyalty = new LoyaltyService() {
			@Override
			public boolean isLoyaltyEnabled() {
				return false;
			}
		};
		AppConfigAPI api = new AppConfigAPI(TestModes.of(env), license, setup);
		inject(api, "appVersion", "test");
		inject(api, "loyaltyService", loyalty);
		return api.getConfig().getBody();
	}

	private static void inject(Object target, String fieldName, Object value) throws Exception {
		Field field = AppConfigAPI.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
