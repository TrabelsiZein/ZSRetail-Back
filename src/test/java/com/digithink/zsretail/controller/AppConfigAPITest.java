package com.digithink.zsretail.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.AppConfigDTO;
import com.digithink.zsretail.model.enumeration.LicenseStatus;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.LicenseService;
import com.digithink.zsretail.service.LoyaltyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 0.5: GET /config keeps its 14 existing fields (names, order, values) and adds
 * nodeType, ownership and salesUpstreams from ApplicationModeService, for each of today's profiles
 * (design table 5.1). Task 1.1 adds the headoffice-dev row. Task 1.5 adds headOfficeLinked, last. Plain JUnit with in-memory stubs (no Spring context).
 */
class AppConfigAPITest {

	/** Response keys before task 0.5, in their JSON order. */
	private static final List<String> OLD_KEYS = Arrays.asList("standalone", "enableSalesPriceGroup",
			"loyaltyEnabled", "franchiseAdmin", "franchiseCustomer", "allowLocalItems", "licenseStatus",
			"licenseDaysUntilExpiry", "posShowImages", "posShowStock", "tableManagementEnabled",
			"tableManagementTableCount", "appVersion", "tombolaEnabled");

	/** Keys added by the head office plan: tasks 0.5 (first three) and 1.5 (headOfficeLinked),
	 * step 6 (catalogueFromHeadOffice), always last. */
	private static final List<String> NEW_KEYS = Arrays.asList("nodeType", "ownership", "salesUpstreams",
			"headOfficeLinked", "catalogueFromHeadOffice", "supplyFromHeadOffice");

	private static final String L = "LOCAL";
	private static final String HO = "HEAD_OFFICE";
	private static final String ERP = "ERP";

	// Stub values: none equals its default, and neighbouring booleans differ
	private static final String APP_VERSION = "9.9.9-test";
	private static final long LICENSE_DAYS = 9;

	private static Map<String, String> generalSetup() {
		Map<String, String> values = new HashMap<>();
		values.put("POS_SHOW_IMAGES", "false");
		values.put("POS_SHOW_STOCK", "true");
		values.put("TABLE_MANAGEMENT_ENABLED", "true");
		values.put("TABLE_MANAGEMENT_TABLE_COUNT", "7");
		values.put("TOMBOLA_ENABLED", "true");
		return values;
	}

	// Flags of today's profile files: application.standalone, franchise.admin, franchise.customer,
	// pos.pricing.enable-sales-price-group (franchise.customer.allow-local-items is false wherever it is set)

	private static AppConfigDTO standalone() throws Exception {
		return config(true, false, false, true); // application-standalone-dev
	}

	private static AppConfigDTO dynamics() throws Exception {
		return config(false, false, false, true); // application-dynamics-dev
	}

	private static AppConfigDTO franchiseCustomer() throws Exception {
		return config(true, false, true, false); // application-franchise-customer
	}

	private static AppConfigDTO franchiseAdmin() throws Exception {
		return config(true, true, false, false); // application-franchise-admin
	}

	private static AppConfigDTO headOffice() throws Exception {
		// application-headoffice-dev: standalone flags plus node.type
		return config(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), true, false, false, true);
	}

	private static AppConfigDTO linkedStore() throws Exception {
		// application-standalone-dev with the two headoffice.* lines uncommented (task 1.4)
		return config(new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"), true, false, false, true);
	}

	private static AppConfigDTO config(boolean standalone, boolean franchiseAdmin, boolean franchiseCustomer,
			boolean enableSalesPriceGroup) throws Exception {
		return config(new MockEnvironment(), standalone, franchiseAdmin, franchiseCustomer, enableSalesPriceGroup);
	}

	private static AppConfigDTO config(MockEnvironment env, boolean standalone, boolean franchiseAdmin,
			boolean franchiseCustomer, boolean enableSalesPriceGroup) throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, ApplicationModeService.class, "environment", env);
		inject(mode, ApplicationModeService.class, "standalone", standalone);
		inject(mode, ApplicationModeService.class, "franchiseAdmin", franchiseAdmin);
		inject(mode, ApplicationModeService.class, "franchiseCustomer", franchiseCustomer);
		Method initOwnership = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		initOwnership.setAccessible(true);
		initOwnership.invoke(mode);

		LicenseService license = new LicenseService(null, null, null) {
			@Override
			public LicenseStatus getStatus() {
				return LicenseStatus.WARNING;
			}

			@Override
			public long getDaysUntilExpiry() {
				return LICENSE_DAYS;
			}
		};
		Map<String, String> setup = generalSetup();
		GeneralSetupService generalSetupService = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return setup.get(code);
			}
		};
		LoyaltyService loyalty = new LoyaltyService() {
			@Override
			public boolean isLoyaltyEnabled() {
				return true;
			}
		};

		AppConfigAPI api = new AppConfigAPI(mode, license, generalSetupService);
		inject(api, AppConfigAPI.class, "enableSalesPriceGroup", enableSalesPriceGroup);
		inject(api, AppConfigAPI.class, "appVersion", APP_VERSION);
		inject(api, AppConfigAPI.class, "loyaltyService", loyalty);
		return api.getConfig().getBody();
	}

	/** The 14 existing fields: mode flags from the profile, the rest from the stubs. */
	private static void assertOldFields(AppConfigDTO c, boolean standalone, boolean enableSalesPriceGroup,
			boolean franchiseAdmin, boolean franchiseCustomer) {
		assertEquals(standalone, c.isStandalone(), "standalone");
		assertEquals(enableSalesPriceGroup, c.isEnableSalesPriceGroup(), "enableSalesPriceGroup");
		assertTrue(c.isLoyaltyEnabled(), "loyaltyEnabled");
		assertEquals(franchiseAdmin, c.isFranchiseAdmin(), "franchiseAdmin");
		assertEquals(franchiseCustomer, c.isFranchiseCustomer(), "franchiseCustomer");
		assertFalse(c.isAllowLocalItems(), "allowLocalItems");
		assertEquals("WARNING", c.getLicenseStatus(), "licenseStatus");
		assertEquals(LICENSE_DAYS, c.getLicenseDaysUntilExpiry(), "licenseDaysUntilExpiry");
		assertFalse(c.isPosShowImages(), "posShowImages");
		assertTrue(c.isPosShowStock(), "posShowStock");
		assertTrue(c.isTableManagementEnabled(), "tableManagementEnabled");
		assertEquals(7, c.getTableManagementTableCount(), "tableManagementTableCount");
		assertEquals(APP_VERSION, c.getAppVersion(), "appVersion");
		assertTrue(c.isTombolaEnabled(), "tombolaEnabled");
	}

	/** Owners in DataDomain order: catalogue, customers, promotions, loyalty, supply. */
	private static void assertNewFields(AppConfigDTO c, String nodeType, String catalogue, String customers,
			String promotions, String loyalty, String supply, String... upstreams) {
		assertEquals(nodeType, c.getNodeType(), "nodeType");
		Map<String, String> owners = new LinkedHashMap<>();
		owners.put("CATALOGUE", catalogue);
		owners.put("CUSTOMERS", customers);
		owners.put("PROMOTIONS", promotions);
		owners.put("LOYALTY", loyalty);
		owners.put("SUPPLY", supply);
		assertEquals(owners, c.getOwnership(), "ownership");
		assertEquals(new ArrayList<>(owners.keySet()), new ArrayList<>(c.getOwnership().keySet()),
				"ownership key order");
		assertEquals(Arrays.asList(upstreams), c.getSalesUpstreams(), "salesUpstreams");
	}

	@Test
	@DisplayName("Standalone: old fields unchanged; store, everything local, sales go nowhere")
	void standaloneProfile() throws Exception {
		AppConfigDTO c = standalone();
		assertOldFields(c, true, true, false, false);
		assertNewFields(c, "STORE", L, L, L, L, L);
	}

	@Test
	@DisplayName("Dynamics (ERP): old fields unchanged; store, catalogue/customers/supply from ERP, sales go to ERP")
	void dynamicsProfile() throws Exception {
		AppConfigDTO c = dynamics();
		assertOldFields(c, false, true, false, false);
		assertNewFields(c, "STORE", ERP, ERP, L, L, ERP, "ERP");
	}

	@Test
	@DisplayName("Franchise customer: old fields unchanged; store, catalogue and supply from head office, sales go to head office")
	void franchiseCustomerProfile() throws Exception {
		AppConfigDTO c = franchiseCustomer();
		assertOldFields(c, true, false, false, true);
		assertNewFields(c, "STORE", HO, L, L, L, HO, "HEAD_OFFICE");
	}

	@Test
	@DisplayName("Franchise admin (legacy): old fields unchanged; store, everything local, sales go nowhere")
	void franchiseAdminProfile() throws Exception {
		AppConfigDTO c = franchiseAdmin();
		assertOldFields(c, true, false, true, false);
		assertNewFields(c, "STORE", L, L, L, L, L);
	}

	@Test
	@DisplayName("Head office (headoffice-dev): old fields unchanged; head office, everything local, sales go nowhere")
	void headOfficeProfile() throws Exception {
		AppConfigDTO c = headOffice();
		assertOldFields(c, true, true, false, false);
		assertNewFields(c, "HEAD_OFFICE", L, L, L, L, L);
	}

	@Test
	@DisplayName("headOfficeLinked: true only on a store with headoffice.url; false on the 4 profiles and the head office")
	void headOfficeLinked() throws Exception {
		AppConfigDTO linked = linkedStore();
		assertTrue(linked.isHeadOfficeLinked());
		assertOldFields(linked, true, true, false, false);
		assertNewFields(linked, "STORE", L, L, L, L, L);
		for (AppConfigDTO c : Arrays.asList(standalone(), dynamics(), franchiseCustomer(), franchiseAdmin(),
				headOffice())) {
			assertFalse(c.isHeadOfficeLinked());
		}
		assertFalse(config(new MockEnvironment().withProperty("headoffice.url", " "), true, false, false, true)
				.isHeadOfficeLinked(), "a blank URL is no link");
	}

	@Test
	@DisplayName("Step 6: catalogueFromHeadOffice only on a standalone store with headoffice.url and ownership.catalogue=HEAD_OFFICE;"
			+ " never on a franchise customer, even with headoffice.url")
	void catalogueFromHeadOffice() throws Exception {
		MockEnvironment catalogue = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde")
				.withProperty("ownership.catalogue", "HEAD_OFFICE");
		assertTrue(config(catalogue, true, false, false, true).isCatalogueFromHeadOffice());
		for (AppConfigDTO c : Arrays.asList(standalone(), dynamics(), franchiseCustomer(), franchiseAdmin(),
				headOffice(), linkedStore())) {
			assertFalse(c.isCatalogueFromHeadOffice());
		}
		MockEnvironment franchiseLinked = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
		AppConfigDTO franchise = config(franchiseLinked, true, false, true, false);
		assertEquals(HO, franchise.getOwnership().get("CATALOGUE"), "derived for the legacy sync");
		assertFalse(franchise.isCatalogueFromHeadOffice());
	}

	@Test
	@DisplayName("Step 7A: supplyFromHeadOffice only on a standalone store with headoffice.url and ownership.supply=HEAD_OFFICE;"
			+ " never on a franchise customer (supply derived HEAD_OFFICE), even with headoffice.url")
	void supplyFromHeadOffice() throws Exception {
		MockEnvironment supply = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde")
				.withProperty("ownership.catalogue", "HEAD_OFFICE").withProperty("ownership.supply", "HEAD_OFFICE");
		AppConfigDTO on = config(supply, true, false, false, true);
		assertTrue(on.isSupplyFromHeadOffice());
		assertEquals(HO, on.getOwnership().get("SUPPLY"));
		for (AppConfigDTO c : Arrays.asList(standalone(), dynamics(), franchiseCustomer(), franchiseAdmin(),
				headOffice(), linkedStore())) {
			assertFalse(c.isSupplyFromHeadOffice());
		}
		MockEnvironment franchiseLinked = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
		AppConfigDTO franchise = config(franchiseLinked, true, false, true, false);
		assertEquals(HO, franchise.getOwnership().get("SUPPLY"), "derived for the legacy reception");
		assertFalse(franchise.isSupplyFromHeadOffice());
	}

	@Test
	@DisplayName("JSON: the 14 old keys keep their names and order, the 6 new keys come last")
	void jsonKeys() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		List<String> expected = new ArrayList<>(OLD_KEYS);
		expected.addAll(NEW_KEYS);
		for (AppConfigDTO c : Arrays.asList(standalone(), dynamics(), franchiseCustomer(), franchiseAdmin(),
				headOffice(), linkedStore())) {
			JsonNode json = mapper.valueToTree(c);
			List<String> keys = new ArrayList<>();
			json.fieldNames().forEachRemaining(keys::add);
			assertEquals(expected, keys);
		}

		JsonNode erp = mapper.valueToTree(dynamics());
		assertEquals("\"STORE\"", erp.get("nodeType").toString());
		assertEquals("{\"CATALOGUE\":\"ERP\",\"CUSTOMERS\":\"ERP\",\"PROMOTIONS\":\"LOCAL\",\"LOYALTY\":\"LOCAL\",\"SUPPLY\":\"ERP\"}",
				erp.get("ownership").toString());
		assertEquals("[\"ERP\"]", erp.get("salesUpstreams").toString());
		assertEquals("[]", mapper.valueToTree(standalone()).get("salesUpstreams").toString());
		assertEquals("false", erp.get("headOfficeLinked").toString());
		assertEquals("true", mapper.valueToTree(linkedStore()).get("headOfficeLinked").toString());
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
