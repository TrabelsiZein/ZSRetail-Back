package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.AppConfigDTO;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.LicenseStatus;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.service.LicenseService;
import com.digithink.zsretail.core.service.LoyaltyService;
import com.digithink.zsretail.core.service.ItemTreeService;
import com.digithink.zsretail.core.service.NavItemService;
import com.digithink.zsretail.core.service.TaxStampRule;
import com.digithink.zsretail.core.service.TaxStampRuleTest;
import com.digithink.zsretail.core.support.TestModes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, task 0.5: GET /config keeps its existing fields (names, order, values) and adds
 * nodeType, ownership and salesUpstreams from ApplicationModeService, for each of today's profiles
 * (design table 5.1). Task 1.1 adds the headoffice-dev row. Task 1.5 adds headOfficeLinked, last. Task 9.4a removes the
 * three franchise fields (franchiseAdmin, franchiseCustomer, allowLocalItems) with the franchise profiles; standalone
 * keeps its name and value (step 9: !hasErp()). Plain JUnit with in-memory stubs (no Spring context).
 */
class AppConfigAPITest {

	/** Response keys before task 0.5, in their JSON order. */
	private static final List<String> OLD_KEYS = Arrays.asList("standalone", "enableSalesPriceGroup",
			"loyaltyEnabled", "licenseStatus",
			"licenseDaysUntilExpiry", "posShowImages", "posShowStock", "tableManagementEnabled",
			"tableManagementTableCount", "appVersion", "tombolaEnabled");

	/** Keys added by the head office plan: tasks 0.5 (first three) and 1.5 (headOfficeLinked),
	 * step 6 (catalogueFromHeadOffice), step 7A (supplyFromHeadOffice), headoffice.stock.enabled (headOfficeStock),
	 * invoices from the ERP (supplySource), always last; then 2.2.1 decimal quantities (allowDecimalQuantity) and the tax
	 * stamp rule (taxStampActive); then 2.2.2 amounts with 3 decimals on the POS screens (posAmountsThreeDecimals); then
	 * RFID step 2 (rfidEnabled); then 3.1.0 cashierClosingTicket and the NAV items page of item variants (navItems), then
	 * its Catégories and Sections pages (itemCategories, itemSections); then step B's till actions registry (tillActions). */
	private static final List<String> NEW_KEYS = Arrays.asList("nodeType", "ownership", "salesUpstreams",
			"headOfficeLinked", "catalogueFromHeadOffice", "supplyFromHeadOffice", "headOfficeStock", "supplySource",
			"allowDecimalQuantity", "taxStampActive", "posAmountsThreeDecimals", "rfidEnabled", "cashierClosingTicket",
			"navItems", "itemCategories", "itemSections", "tillActions", "storeOrdersPost");

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
		values.put(TaxStampRule.SETTING, "true");
		values.put(AppConfigAPI.POS_AMOUNTS_THREE_DECIMALS, "TRUE");
		values.put(AppConfigAPI.CASHIER_CLOSING_TICKET, "declared");
		values.putAll(SETUP_OVERRIDES);
		return values;
	}

	/** Settings of one test over the stub values (a null value: the row absent); cleared after the test. */
	private static final Map<String, String> SETUP_OVERRIDES = new HashMap<>();

	// Mode of today's presets (with or without an ERP) and pos.pricing.enable-sales-price-group

	private static AppConfigDTO standalone() throws Exception {
		return config(true, true); // application-standalone-dev
	}

	private static AppConfigDTO dynamics() throws Exception {
		return config(false, true); // application-dynamics-dev
	}

	private static AppConfigDTO headOffice() throws Exception {
		// application-headoffice-dev: standalone flags plus node.type
		return config(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), true, true);
	}

	private static AppConfigDTO linkedStore() throws Exception {
		// application-standalone-dev with the two headoffice.* lines uncommented (task 1.4)
		return config(new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde"), true, true);
	}

	private static AppConfigDTO config(boolean standalone, boolean enableSalesPriceGroup) throws Exception {
		return config(new MockEnvironment(), standalone, enableSalesPriceGroup);
	}

	private static AppConfigDTO config(MockEnvironment env, boolean standalone, boolean enableSalesPriceGroup)
			throws Exception {
		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, ApplicationModeService.class, "environment", env);
		if (!standalone) {
			TestModes.erpOwners(env); // task 9.3: the ERP owners instead of application.standalone=false
		}
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
		inject(api, AppConfigAPI.class, "taxStampRule", TaxStampRuleTest.rule(setup.get(TaxStampRule.SETTING), true));
		inject(api, AppConfigAPI.class, "navItemService", NAV_ITEM_SERVICE[0]);
		inject(api, AppConfigAPI.class, "itemTreeService", ITEM_TREE_SERVICE[0]);
		return api.getConfig().getBody();
	}

	/** The existing fields: mode flags from the profile, the rest from the stubs. */
	private static void assertOldFields(AppConfigDTO c, boolean standalone, boolean enableSalesPriceGroup) {
		assertEquals(standalone, c.isStandalone(), "standalone");
		assertEquals(enableSalesPriceGroup, c.isEnableSalesPriceGroup(), "enableSalesPriceGroup");
		assertTrue(c.isLoyaltyEnabled(), "loyaltyEnabled");
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
		assertOldFields(c, true, true);
		assertNewFields(c, "STORE", L, L, L, L, L);
	}

	@Test
	@DisplayName("Dynamics (ERP): old fields unchanged; store, catalogue/customers/supply from ERP, sales go to ERP")
	void dynamicsProfile() throws Exception {
		AppConfigDTO c = dynamics();
		assertOldFields(c, false, true);
		assertNewFields(c, "STORE", ERP, ERP, L, L, ERP, "ERP");
	}

	@Test
	@DisplayName("Head office (headoffice-dev): old fields unchanged; head office, everything local, sales go nowhere")
	void headOfficeProfile() throws Exception {
		AppConfigDTO c = headOffice();
		assertOldFields(c, true, true);
		assertNewFields(c, "HEAD_OFFICE", L, L, L, L, L);
	}

	@Test
	@DisplayName("headOfficeLinked: true only on a store with headoffice.url; false on the other profiles and the head office")
	void headOfficeLinked() throws Exception {
		AppConfigDTO linked = linkedStore();
		assertTrue(linked.isHeadOfficeLinked());
		assertOldFields(linked, true, true);
		assertNewFields(linked, "STORE", L, L, L, L, L);
		for (AppConfigDTO c : Arrays.asList(standalone(), dynamics(), headOffice())) {
			assertFalse(c.isHeadOfficeLinked());
		}
		assertFalse(config(new MockEnvironment().withProperty("headoffice.url", " "), true, true)
				.isHeadOfficeLinked(), "a blank URL is no link");
	}

	@Test
	@DisplayName("Step 6: catalogueFromHeadOffice only on a standalone store with headoffice.url and ownership.catalogue=HEAD_OFFICE")
	void catalogueFromHeadOffice() throws Exception {
		MockEnvironment catalogue = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde")
				.withProperty("ownership.catalogue", "HEAD_OFFICE");
		assertTrue(config(catalogue, true, true).isCatalogueFromHeadOffice());
		for (AppConfigDTO c : Arrays.asList(standalone(), dynamics(), headOffice(), linkedStore())) {
			assertFalse(c.isCatalogueFromHeadOffice());
		}
	}

	@Test
	@DisplayName("Step 7A: supplyFromHeadOffice only on a standalone store with headoffice.url and ownership.supply=HEAD_OFFICE")
	void supplyFromHeadOffice() throws Exception {
		MockEnvironment supply = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde")
				.withProperty("ownership.catalogue", "HEAD_OFFICE").withProperty("ownership.supply", "HEAD_OFFICE");
		AppConfigDTO on = config(supply, true, true);
		assertTrue(on.isSupplyFromHeadOffice());
		assertEquals(HO, on.getOwnership().get("SUPPLY"));
		for (AppConfigDTO c : Arrays.asList(standalone(), dynamics(), headOffice(), linkedStore())) {
			assertFalse(c.isSupplyFromHeadOffice());
		}
	}

	@Test
	@DisplayName("Invoices from the ERP: supplySource HEAD_OFFICE or ERP on a head office, null on a store")
	void supplySource() throws Exception {
		assertEquals(HO, headOffice().getSupplySource());
		MockEnvironment erpSupply = new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")
				.withProperty("ownership.catalogue", "ERP").withProperty("erp.connector", "happyness")
				.withProperty("headoffice.supply.source", "ERP");
		assertEquals("ERP", config(erpSupply, true, true).getSupplySource());
		for (AppConfigDTO c : Arrays.asList(standalone(), dynamics(), linkedStore())) {
			assertNull(c.getSupplySource());
		}
	}

	@Test
	@DisplayName("JSON: the 11 old keys keep their names and order (the three franchise keys are gone), the new keys come last")
	void jsonKeys() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		List<String> expected = new ArrayList<>(OLD_KEYS);
		expected.addAll(NEW_KEYS);
		for (AppConfigDTO c : Arrays.asList(standalone(), dynamics(), headOffice(), linkedStore())) {
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
		assertEquals("true", erp.get("taxStampActive").toString()); // 2.2.1: ENABLE_TAX_STAMP true and the item
		assertEquals("true", erp.get("posAmountsThreeDecimals").toString()); // 2.2.2: POS_AMOUNTS_3_DECIMALS, any case
		assertFalse(new AppConfigDTO().isPosAmountsThreeDecimals()); // the row absent (a head office): off
		assertEquals("false", erp.get("rfidEnabled").toString()); // RFID step 2: no rfid.enabled line
	}

	@Test
	@DisplayName("Step B: tillActions is the registry, the five BadgePermission constants with their till:* keys in order, on every store; empty on a head office")
	void tillActions() throws Exception {
		List<String> codes = Arrays.asList("CONSULT_CUSTOMER_LIST", "MAKE_RETURN", "APPLY_LINE_DISCOUNT",
				"APPLY_TOTAL_DISCOUNT", "CLOSE_SESSION_WITH_DISCREPANCY");
		List<String> keys = Arrays.asList("till:consult-customer-list", "till:make-return", "till:apply-line-discount",
				"till:apply-total-discount", "till:close-session-with-discrepancy");
		for (AppConfigDTO store : Arrays.asList(standalone(), dynamics(), linkedStore())) {
			List<String> actualCodes = new ArrayList<>();
			List<String> actualKeys = new ArrayList<>();
			List<Boolean> actualChecked = new ArrayList<>();
			store.getTillActions().forEach(a -> {
				actualCodes.add(a.getCode());
				actualKeys.add(a.getKey());
				actualChecked.add(a.isCheckedOnRequest());
			});
			assertEquals(codes, actualCodes, "codes");
			assertEquals(keys, actualKeys, "keys");
			// step D: the customer list is the one action no request checks
			assertEquals(Arrays.asList(false, true, true, true, true), actualChecked, "checkedOnRequest");
			for (BadgePermission action : BadgePermission.values()) {
				assertEquals("till:" + action.name().toLowerCase().replace('_', '-'), action.key(), action.name());
			}
		}
		assertEquals(Arrays.asList(), headOffice().getTillActions(), "a head office has no till");
		assertEquals("{\"code\":\"MAKE_RETURN\",\"key\":\"till:make-return\",\"checkedOnRequest\":true}",
				new ObjectMapper().valueToTree(standalone()).get("tillActions").get(1).toString());
	}

	@Test
	@DisplayName("3.1.0: cashierClosingTicket: DECLARED in any case, Z otherwise and when the row is absent, null on a head office")
	void cashierClosingTicket() throws Exception {
		try {
			assertEquals("DECLARED", dynamics().getCashierClosingTicket()); // the stub: "declared"
			assertEquals("DECLARED", linkedStore().getCashierClosingTicket());
			assertNull(headOffice().getCashierClosingTicket(), "a head office has no till");
			SETUP_OVERRIDES.put(AppConfigAPI.CASHIER_CLOSING_TICKET, null);
			assertEquals("Z", standalone().getCashierClosingTicket(), "the row absent: the ticket Z");
			SETUP_OVERRIDES.put(AppConfigAPI.CASHIER_CLOSING_TICKET, "Z");
			assertEquals("Z", dynamics().getCashierClosingTicket());
			SETUP_OVERRIDES.put(AppConfigAPI.CASHIER_CLOSING_TICKET, "anything");
			assertEquals("Z", dynamics().getCashierClosingTicket());
		} finally {
			SETUP_OVERRIDES.clear();
		}
	}

	@Test
	@DisplayName("Item variants (ZEN), step 11a: navItems is the NAV item service's answer, read on each call; false without it")
	void navItems() throws Exception {
		boolean[] exists = { false };
		NavItemService navItemService = new NavItemService(null, null, null, null, Optional.empty()) {
			@Override
			public boolean hasNavItems() {
				return exists[0];
			}
		};
		assertFalse(dynamics().isNavItems(), "no NAV item service (a hand-built API): false");
		AppConfigDTO before = configWith(navItemService);
		assertFalse(before.isNavItems());
		exists[0] = true; // a catalogue read or a copy down brought the first variant
		assertTrue(configWith(navItemService).isNavItems(), "no cache: the next call sees it");
	}

	/** The store preset without an ERP (standalone()), with this NAV item service. */
	private static AppConfigDTO configWith(NavItemService navItemService) throws Exception {
		NAV_ITEM_SERVICE[0] = navItemService;
		try {
			return standalone();
		} finally {
			NAV_ITEM_SERVICE[0] = null;
		}
	}

	/** The NAV item service config() injects, null = none (navItems false). */
	private static final NavItemService[] NAV_ITEM_SERVICE = { null };

	/** The tree service config() injects, null = none (itemCategories, itemSections false). */
	private static final ItemTreeService[] ITEM_TREE_SERVICE = { null };

	@Test
	@DisplayName("Item variants (ZEN), step 11b: itemCategories and itemSections are the tree service's answers, read on each call")
	void itemTree() throws Exception {
		boolean[] rows = { false, false }; // catégories, sections
		ItemTreeService tree = new ItemTreeService(null, null, null, null) {
			@Override
			public boolean hasCategories() {
				return rows[0];
			}

			@Override
			public boolean hasSections() {
				return rows[1];
			}
		};
		AppConfigDTO none = dynamics();
		assertFalse(none.isItemCategories(), "no tree service (a hand-built API): false");
		assertFalse(none.isItemSections());
		ITEM_TREE_SERVICE[0] = tree;
		try {
			assertFalse(standalone().isItemCategories(), "empty tables (EMTOP, Happyness)");
			rows[0] = true; // the first catalogue read or copy down
			AppConfigDTO categoriesOnly = standalone();
			assertTrue(categoriesOnly.isItemCategories(), "no cache: the next call sees it");
			assertFalse(categoriesOnly.isItemSections(), "each table its own answer");
			rows[1] = true;
			assertTrue(standalone().isItemSections());
		} finally {
			ITEM_TREE_SERVICE[0] = null;
		}
	}

	@Test
	@DisplayName("3.1.1, storeOrdersPost: MANUAL on a head office with the key; null without it and always on a store")
	void storeOrdersPost() throws Exception {
		assertEquals(null, headOffice().getStoreOrdersPost(), "a head office without the key takes no order");
		AppConfigDTO manual = config(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")
				.withProperty("headoffice.storeorders.post", "manual"), true, true);
		assertEquals("MANUAL", manual.getStoreOrdersPost());
		AppConfigDTO store = config(new MockEnvironment().withProperty("headoffice.storeorders.post", "MANUAL"), true, true);
		assertEquals(null, store.getStoreOrdersPost(), "a store never reads the key");
		assertEquals(null, linkedStore().getStoreOrdersPost());
		// ERP needs a connector with ErpAbility.STORE_ORDERS (zen): network-tests StoreOrdersPostKeyTest
	}

	private static void inject(Object target, Class<?> declaringClass, String fieldName, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(target, value);
	}
}
