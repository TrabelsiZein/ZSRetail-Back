package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.NodeType;
import com.digithink.zsretail.model.enumeration.SalesUpstream;

/**
 * Head office plan, task 0.4: node type, data ownership and sales upstreams derived from today's mode
 * flags (design table 5.1), overridden by the optional keys, and rejected at startup when invalid.
 * Task 1.1: the head office rows and the startup checks across keys.
 * Task 1.4: the head office link startup checks (headoffice.url on a head office, missing key, interval).
 * Task 1.5: headoffice.offline-after-seconds on a head office; isHeadOfficeLinked().
 * Task 2.1: headoffice.sales-push.from-date. Task 2.4: a head office upstream needs headoffice.url; batch size and
 * interval of the sales push.
 * Plain JUnit with a MockEnvironment (no Spring context).
 */
class ApplicationModeOwnershipTest {

	private static final DataOwner L = DataOwner.LOCAL;
	private static final DataOwner HO = DataOwner.HEAD_OFFICE;
	private static final DataOwner ERP = DataOwner.ERP;

	// Mode flags of today's profiles: standalone, franchise.admin, franchise.customer
	private static NodeOwnership standalone(MockEnvironment env) {
		return NodeOwnership.resolve(env, true, false, false);
	}

	private static NodeOwnership erp(MockEnvironment env) {
		return NodeOwnership.resolve(env, false, false, false);
	}

	private static NodeOwnership franchiseCustomer(MockEnvironment env) {
		return NodeOwnership.resolve(env, true, false, true);
	}

	private static NodeOwnership franchiseAdmin(MockEnvironment env) {
		return NodeOwnership.resolve(env, true, true, false);
	}

	/** Owners in DataDomain order: catalogue, customers, promotions, loyalty, supply. */
	private static void assertRow(NodeOwnership o, NodeType type, DataOwner catalogue, DataOwner customers,
			DataOwner promotions, DataOwner loyalty, DataOwner supply, Set<SalesUpstream> upstreams) {
		assertEquals(type, o.getNodeType(), "node type");
		assertEquals(catalogue, o.ownerOf(DataDomain.CATALOGUE), "catalogue");
		assertEquals(customers, o.ownerOf(DataDomain.CUSTOMERS), "customers");
		assertEquals(promotions, o.ownerOf(DataDomain.PROMOTIONS), "promotions");
		assertEquals(loyalty, o.ownerOf(DataDomain.LOYALTY), "loyalty");
		assertEquals(supply, o.ownerOf(DataDomain.SUPPLY), "supply");
		assertEquals(upstreams, o.getSalesUpstreams(), "sales upstreams");
	}

	private static Set<SalesUpstream> none() {
		return EnumSet.noneOf(SalesUpstream.class);
	}

	// --- Derived rows (no new key set) ---

	@Test
	@DisplayName("Standalone: store, everything local, sales go nowhere")
	void standaloneRow() {
		assertRow(standalone(new MockEnvironment()), NodeType.STORE, L, L, L, L, L, none());
	}

	@Test
	@DisplayName("ERP: store, catalogue/customers/supply from ERP, promotions and loyalty local, sales go to ERP")
	void erpRow() {
		assertRow(erp(new MockEnvironment()), NodeType.STORE, ERP, ERP, L, L, ERP, EnumSet.of(SalesUpstream.ERP));
	}

	@Test
	@DisplayName("Franchise customer: store, catalogue and supply from head office, the rest local, sales go to head office")
	void franchiseCustomerRow() {
		assertRow(franchiseCustomer(new MockEnvironment()), NodeType.STORE, HO, L, L, L, HO,
				EnumSet.of(SalesUpstream.HEAD_OFFICE));
	}

	@Test
	@DisplayName("Franchise admin (legacy): store, everything local, sales go nowhere")
	void franchiseAdminRow() {
		assertRow(franchiseAdmin(new MockEnvironment()), NodeType.STORE, L, L, L, L, L, none());
	}

	@Test
	@DisplayName("Precedence: franchise customer beats franchise admin, which beats standalone (task 9.1a: never with ERP flags)")
	void precedence() {
		assertRow(NodeOwnership.resolve(new MockEnvironment(), true, true, true), NodeType.STORE, HO, L, L, L, HO,
				EnumSet.of(SalesUpstream.HEAD_OFFICE));
		assertRow(NodeOwnership.resolve(new MockEnvironment(), true, true, false), NodeType.STORE, L, L, L, L, L,
				none());
	}

	// --- Explicit keys ---

	@Test
	@DisplayName("An explicit owner overrides only its own domain")
	void explicitOwnerOverridesOneDomain() {
		// task 3.1: promotions owned by the head office need headoffice.url
		MockEnvironment env = linkEnv().withProperty("ownership.promotions", "HEAD_OFFICE");
		assertRow(erp(env), NodeType.STORE, ERP, ERP, HO, L, ERP, EnumSet.of(SalesUpstream.ERP));
	}

	@Test
	@DisplayName("Every domain and the node type can be set explicitly (on a store)")
	void allKeysExplicit() {
		MockEnvironment env = linkEnv() // task 2.4: a head office upstream needs headoffice.url
				.withProperty("node.type", "STORE")
				.withProperty("ownership.catalogue", "HEAD_OFFICE")
				.withProperty("ownership.customers", "HEAD_OFFICE")
				.withProperty("ownership.promotions", "LOCAL")
				.withProperty("ownership.loyalty", "HEAD_OFFICE")
				.withProperty("ownership.supply", "LOCAL")
				.withProperty("sales.upstream", "HEAD_OFFICE");
		assertRow(standalone(env), NodeType.STORE, HO, HO, L, HO, L, EnumSet.of(SalesUpstream.HEAD_OFFICE));
		// With ERP flags (task 9.1a: catalogue, customers and supply can only be ERP there)
		MockEnvironment erpEnv = linkEnv()
				.withProperty("ownership.catalogue", "ERP")
				.withProperty("ownership.customers", "ERP")
				.withProperty("ownership.promotions", "HEAD_OFFICE")
				.withProperty("ownership.loyalty", "LOCAL")
				.withProperty("ownership.supply", "ERP")
				.withProperty("sales.upstream", "ERP,HEAD_OFFICE");
		assertRow(erp(erpEnv), NodeType.STORE, ERP, ERP, HO, L, ERP,
				EnumSet.of(SalesUpstream.ERP, SalesUpstream.HEAD_OFFICE));
	}

	@Test
	@DisplayName("sales.upstream: a comma list gives both, an empty value gives none even in ERP mode")
	void salesUpstreamList() {
		assertEquals(EnumSet.of(SalesUpstream.ERP, SalesUpstream.HEAD_OFFICE),
				erp(linkEnv().withProperty("sales.upstream", "ERP, HEAD_OFFICE")).getSalesUpstreams());
		assertEquals(none(), erp(new MockEnvironment().withProperty("sales.upstream", "")).getSalesUpstreams());
		assertEquals(none(), erp(new MockEnvironment().withProperty("sales.upstream", "  ")).getSalesUpstreams());
	}

	@Test
	@DisplayName("Values are trimmed and case-insensitive")
	void lenientCase() {
		MockEnvironment env = new MockEnvironment()
				.withProperty("node.type", " head_office ")
				.withProperty("ownership.catalogue", "Erp");
		NodeOwnership o = erp(env);
		assertEquals(NodeType.HEAD_OFFICE, o.getNodeType());
		assertEquals(ERP, o.ownerOf(DataDomain.CATALOGUE));
	}

	// --- Task 9.1a: owners and application.standalone agree ---

	private static void assertRefused(MockEnvironment env, boolean standalone, boolean admin, boolean customer,
			String... parts) {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> NodeOwnership.resolve(env, standalone, admin, customer));
		for (String part : parts) {
			assertTrue(e.getMessage().contains(part), "message contains '" + part + "': " + e.getMessage());
		}
	}

	@Test
	@DisplayName("9.1a: without an ERP (standalone=true) an explicit owner ERP is refused, on a store and on a head office")
	void standaloneRefusesErpOwner() {
		for (String domain : new String[] { "ownership.catalogue", "ownership.customers", "ownership.supply" }) {
			assertRefused(new MockEnvironment().withProperty(domain, "ERP"), true, false, false,
					"Invalid combination: " + domain + "=ERP with application.standalone=true");
			assertRefused(headOfficeEnv().withProperty(domain, " erp "), true, false, false, domain + "=ERP");
		}
	}

	@Test
	@DisplayName("9.1a: with an ERP (standalone=false) catalogue, customers and supply other than ERP are refused; promotions and loyalty stay free")
	void erpRefusesOtherOwners() {
		assertRefused(new MockEnvironment().withProperty("ownership.customers", "LOCAL"), false, false, false,
				"Invalid combination: ownership.customers=LOCAL with application.standalone=false");
		assertRefused(linkEnv().withProperty("ownership.customers", "HEAD_OFFICE"), false, false, false,
				"ownership.customers=HEAD_OFFICE");
		assertRefused(new MockEnvironment().withProperty("ownership.supply", "LOCAL"), false, false, false,
				"ownership.supply=LOCAL");
		assertRefused(new MockEnvironment().withProperty("ownership.catalogue", "LOCAL"), false, false, false,
				"ownership.catalogue=LOCAL");
		assertRefused(headOfficeEnv().withProperty("ownership.catalogue", "LOCAL"), false, false, false,
				"ownership.catalogue=LOCAL");
		// The step 6 and 7A messages for HEAD_OFFICE with ERP flags are unchanged
		assertRefused(linkEnv().withProperty("ownership.catalogue", "HEAD_OFFICE"), false, false, false,
				"A store whose items come from an ERP");
		assertRow(erp(linkEnv().withProperty("ownership.promotions", "HEAD_OFFICE").withProperty("ownership.loyalty",
				"HEAD_OFFICE")), NodeType.STORE, ERP, ERP, HO, HO, ERP, EnumSet.of(SalesUpstream.ERP));
		assertRow(erp(new MockEnvironment().withProperty("ownership.catalogue", "ERP")), NodeType.STORE, ERP, ERP, L, L,
				ERP, EnumSet.of(SalesUpstream.ERP));
	}

	@Test
	@DisplayName("9.1a: franchise.admin or franchise.customer with ERP flags is refused")
	void franchiseRefusedWithErpFlags() {
		assertRefused(new MockEnvironment(), false, false, true,
				"Invalid combination: franchise.customer=true with application.standalone=false");
		assertRefused(new MockEnvironment(), false, true, false, "franchise.admin=true with application.standalone=false");
	}

	// --- Invalid values fail with the key in the message ---

	private static void assertInvalid(String key, String value) {
		assertInvalid(key, value, value.trim());
	}

	/** {@code shown}: the part of the value the message must quote (the bad item of a list). */
	private static void assertInvalid(String key, String value, String shown) {
		MockEnvironment env = new MockEnvironment().withProperty(key, value);
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env));
		assertTrue(e.getMessage().contains(key), "message names the key: " + e.getMessage());
		assertTrue(e.getMessage().contains("'" + shown + "'"), "message shows the value: " + e.getMessage());
	}

	@Test
	@DisplayName("Loyalty and promotions cannot be owned by the ERP")
	void erpNotAllowedForLoyaltyAndPromotions() {
		assertInvalid("ownership.loyalty", "ERP");
		assertInvalid("ownership.promotions", "ERP");
	}

	@Test
	@DisplayName("Unknown values are refused for node.type, an owner and sales.upstream")
	void unknownValues() {
		assertInvalid("node.type", "SHOP");
		assertInvalid("ownership.catalogue", "NAV");
		assertInvalid("sales.upstream", "ERP,NAV", "NAV");
		assertInvalid("ownership.customers", "");
	}

	// --- Head office (task 1.1): sales go nowhere, startup checks across keys ---

	private static MockEnvironment headOfficeEnv() {
		return new MockEnvironment().withProperty("node.type", "HEAD_OFFICE");
	}

	@Test
	@DisplayName("Head office on standalone flags (headoffice-dev): everything local, sales go nowhere")
	void headOfficeRow() {
		assertRow(standalone(headOfficeEnv()), NodeType.HEAD_OFFICE, L, L, L, L, L, none());
	}

	@Test
	@DisplayName("Head office on ERP flags: owners from the ERP row, but sales go nowhere")
	void headOfficeOnErpFlags() {
		assertRow(erp(headOfficeEnv()), NodeType.HEAD_OFFICE, ERP, ERP, L, L, ERP, none());
	}

	@Test
	@DisplayName("Head office refuses franchise.admin=true and franchise.customer=true")
	void headOfficeRefusesFranchiseFlags() {
		IllegalStateException admin = assertThrows(IllegalStateException.class, () -> franchiseAdmin(headOfficeEnv()));
		assertTrue(admin.getMessage().contains("node.type=HEAD_OFFICE"), admin.getMessage());
		assertTrue(admin.getMessage().contains("franchise.admin=true"), admin.getMessage());
		IllegalStateException customer = assertThrows(IllegalStateException.class,
				() -> franchiseCustomer(headOfficeEnv()));
		assertTrue(customer.getMessage().contains("franchise.customer=true"), customer.getMessage());
	}

	@Test
	@DisplayName("Head office refuses an explicit owner HEAD_OFFICE, for every domain")
	void headOfficeRefusesHeadOfficeOwner() {
		for (DataDomain domain : DataDomain.values()) {
			MockEnvironment env = headOfficeEnv().withProperty(domain.getPropertyKey(), "HEAD_OFFICE");
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env));
			assertTrue(e.getMessage().contains(domain.getPropertyKey()), e.getMessage());
		}
	}

	@Test
	@DisplayName("Head office refuses a non-empty sales.upstream; an empty value is accepted")
	void headOfficeRefusesSalesUpstream() {
		for (String value : new String[] { "ERP", "HEAD_OFFICE", "ERP,HEAD_OFFICE" }) {
			MockEnvironment env = headOfficeEnv().withProperty("sales.upstream", value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env));
			assertTrue(e.getMessage().contains("sales.upstream"), e.getMessage());
		}
		assertEquals(none(), standalone(headOfficeEnv().withProperty("sales.upstream", "")).getSalesUpstreams());
	}

	// --- Head office link (task 1.4): startup checks when headoffice.url is set ---

	private static MockEnvironment linkEnv() {
		return new MockEnvironment()
				.withProperty("headoffice.url", "http://localhost:888/zsretail/api/")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
	}

	@Test
	@DisplayName("Store with headoffice.url and a key: accepted on the 4 profiles (trailing slash tolerated), rows unchanged")
	void headOfficeLinkAcceptedOnStore() {
		assertRow(standalone(linkEnv()), NodeType.STORE, L, L, L, L, L, none());
		assertRow(erp(linkEnv()), NodeType.STORE, ERP, ERP, L, L, ERP, EnumSet.of(SalesUpstream.ERP));
		assertRow(franchiseCustomer(linkEnv()), NodeType.STORE, HO, L, L, L, HO, EnumSet.of(SalesUpstream.HEAD_OFFICE));
		assertRow(franchiseAdmin(linkEnv()), NodeType.STORE, L, L, L, L, L, none());
		assertEquals(NodeType.STORE, standalone(linkEnv().withProperty("node.type", "STORE")).getNodeType());
	}

	@Test
	@DisplayName("headoffice.url on a head office is refused")
	void headOfficeLinkRefusedOnHeadOffice() {
		MockEnvironment env = linkEnv().withProperty("node.type", "HEAD_OFFICE");
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env));
		assertTrue(e.getMessage().contains("node.type=HEAD_OFFICE"), e.getMessage());
		assertTrue(e.getMessage().contains("headoffice.url"), e.getMessage());
	}

	@Test
	@DisplayName("headoffice.url with a missing, empty or blank headoffice.api-key is refused")
	void headOfficeLinkRefusedWithoutKey() {
		MockEnvironment missing = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api");
		for (MockEnvironment env : new MockEnvironment[] { missing, linkEnv().withProperty("headoffice.api-key", ""),
				linkEnv().withProperty("headoffice.api-key", "  ") }) {
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> erp(env));
			assertTrue(e.getMessage().startsWith("Missing value for property headoffice.api-key"), e.getMessage());
		}
	}

	@Test
	@DisplayName("headoffice.heartbeat-interval-seconds below 1 or not a whole number is refused; 1 and ' 30 ' accepted")
	void headOfficeLinkInterval() {
		for (String value : new String[] { "0", "-5", "abc", "1.5", "" }) {
			MockEnvironment env = linkEnv().withProperty("headoffice.heartbeat-interval-seconds", value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env), value);
			assertTrue(e.getMessage().contains("headoffice.heartbeat-interval-seconds"), e.getMessage());
			assertTrue(e.getMessage().contains("'" + value + "'"), e.getMessage());
		}
		standalone(linkEnv().withProperty("headoffice.heartbeat-interval-seconds", "1"));
		standalone(linkEnv().withProperty("headoffice.heartbeat-interval-seconds", " 30 "));
	}

	@Test
	@DisplayName("Without headoffice.url (absent or blank) the other headoffice.* keys are not checked, on a head office too")
	void headOfficeLinkKeysIgnoredWithoutUrl() {
		MockEnvironment env = new MockEnvironment()
				.withProperty("headoffice.url", " ")
				.withProperty("headoffice.api-key", "")
				.withProperty("headoffice.heartbeat-interval-seconds", "0");
		assertRow(standalone(env), NodeType.STORE, L, L, L, L, L, none());
		assertRow(standalone(headOfficeEnv().withProperty("headoffice.heartbeat-interval-seconds", "0")),
				NodeType.HEAD_OFFICE, L, L, L, L, L, none());
	}

	@Test
	@DisplayName("Head office: headoffice.offline-after-seconds below 1 or not a whole number is refused; 1 and ' 180 ' accepted; not checked on a store")
	void offlineAfterSeconds() {
		for (String value : new String[] { "0", "-1", "3m", "" }) {
			MockEnvironment env = headOfficeEnv().withProperty("headoffice.offline-after-seconds", value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env), value);
			assertTrue(e.getMessage().contains("headoffice.offline-after-seconds"), e.getMessage());
			assertTrue(e.getMessage().contains("'" + value + "'"), e.getMessage());
		}
		standalone(headOfficeEnv().withProperty("headoffice.offline-after-seconds", "1"));
		standalone(headOfficeEnv().withProperty("headoffice.offline-after-seconds", " 180 "));
		assertEquals(NodeType.STORE,
				standalone(new MockEnvironment().withProperty("headoffice.offline-after-seconds", "0")).getNodeType());
	}

	@Test
	@DisplayName("headoffice.sales-push.from-date: yyyy-MM-dd (trimmed) or blank accepted, anything else refused; not checked without headoffice.url")
	void salesPushFromDate() {
		String key = "headoffice.sales-push.from-date";
		for (String value : new String[] { "2026-01-01", " 2026-09-30 ", "", "  " }) {
			standalone(linkEnv().withProperty(key, value));
		}
		for (String value : new String[] { "01/09/2026", "2026-13-01", "2026-09-01T00:00", "yesterday" }) {
			MockEnvironment env = linkEnv().withProperty(key, value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> erp(env), value);
			assertTrue(e.getMessage().startsWith("Invalid value '" + value + "' for property " + key), e.getMessage());
		}
		standalone(new MockEnvironment().withProperty(key, "yesterday"));
		assertEquals(LocalDate.of(2026, 9, 30), NodeOwnership.parseSalesPushFromDate(" 2026-09-30 "));
		assertNull(NodeOwnership.parseSalesPushFromDate(null));
		assertNull(NodeOwnership.parseSalesPushFromDate(" "));
	}

	@Test
	@DisplayName("Task 2.4: an explicit sales.upstream with HEAD_OFFICE needs headoffice.url; ERP alone, empty, or with the URL is accepted")
	void headOfficeUpstreamNeedsUrl() {
		for (String value : new String[] { "HEAD_OFFICE", " erp , head_office " }) {
			MockEnvironment env = new MockEnvironment().withProperty("sales.upstream", value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env), value);
			assertTrue(e.getMessage().startsWith("Missing value for property headoffice.url: required when sales.upstream"
					+ " includes HEAD_OFFICE ('" + value + "')"), e.getMessage());
			assertThrows(IllegalStateException.class, () -> erp(env), value);
		}
		assertEquals(EnumSet.of(SalesUpstream.ERP), erp(new MockEnvironment().withProperty("sales.upstream", "ERP"))
				.getSalesUpstreams());
		assertEquals(none(), standalone(new MockEnvironment().withProperty("sales.upstream", "")).getSalesUpstreams());
		assertEquals(EnumSet.of(SalesUpstream.HEAD_OFFICE),
				standalone(linkEnv().withProperty("sales.upstream", "HEAD_OFFICE")).getSalesUpstreams());
	}

	@Test
	@DisplayName("Task 2.4: the franchise customer profile derives HEAD_OFFICE and still starts without headoffice.url")
	void franchiseCustomerDerivedUpstreamNotChecked() {
		assertRow(franchiseCustomer(new MockEnvironment()), NodeType.STORE, HO, L, L, L, HO,
				EnumSet.of(SalesUpstream.HEAD_OFFICE));
	}

	@Test
	@DisplayName("Task 2.4: a head office with sales.upstream=HEAD_OFFICE keeps the head office message")
	void headOfficeUpstreamMessage() {
		MockEnvironment env = headOfficeEnv().withProperty("sales.upstream", "HEAD_OFFICE");
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env));
		assertTrue(e.getMessage().contains("never sells"), e.getMessage());
	}

	@Test
	@DisplayName("Task 2.4: batch-size 1 to 1000 and interval-seconds at least 1 accepted; other values refused; not checked without headoffice.url")
	void salesPushBatchSizeAndInterval() {
		for (String value : new String[] { "0", "1001", "-1", "abc", "2.5", "" }) {
			MockEnvironment env = linkEnv().withProperty("headoffice.sales-push.batch-size", value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env), value);
			assertTrue(e.getMessage().startsWith("Invalid value '" + value
					+ "' for property headoffice.sales-push.batch-size: a whole number from 1 to 1000"), e.getMessage());
		}
		for (String value : new String[] { "1", " 50 ", "1000" }) {
			standalone(linkEnv().withProperty("headoffice.sales-push.batch-size", value));
		}
		for (String value : new String[] { "0", "-5", "1m", "" }) {
			MockEnvironment env = linkEnv().withProperty("headoffice.sales-push.interval-seconds", value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> standalone(env), value);
			assertTrue(e.getMessage().contains("headoffice.sales-push.interval-seconds"), e.getMessage());
		}
		standalone(linkEnv().withProperty("headoffice.sales-push.interval-seconds", " 30 "));
		standalone(new MockEnvironment().withProperty("headoffice.sales-push.batch-size", "0")
				.withProperty("headoffice.sales-push.interval-seconds", "0"));
	}

	@Test
	@DisplayName("Task 2.6: headoffice.log-retention-days a whole number of days, at least 1; not checked without headoffice.url")
	void logRetentionDays() {
		for (String value : new String[] { "0", "-1", "30d", "" }) {
			MockEnvironment env = linkEnv().withProperty("headoffice.log-retention-days", value);
			IllegalStateException e = assertThrows(IllegalStateException.class, () -> erp(env), value);
			assertTrue(e.getMessage().startsWith("Invalid value '" + value
					+ "' for property headoffice.log-retention-days: a whole number of days, at least 1"), e.getMessage());
		}
		standalone(linkEnv().withProperty("headoffice.log-retention-days", "1"));
		standalone(linkEnv().withProperty("headoffice.log-retention-days", " 90 "));
		standalone(new MockEnvironment().withProperty("headoffice.log-retention-days", "0"));
	}

	// --- Through ApplicationModeService ---

	private static ApplicationModeService service(MockEnvironment env, boolean standalone, boolean franchiseAdmin,
			boolean franchiseCustomer) throws Exception {
		ApplicationModeService service = new ApplicationModeService();
		inject(service, "environment", env);
		inject(service, "standalone", standalone);
		inject(service, "franchiseAdmin", franchiseAdmin);
		inject(service, "franchiseCustomer", franchiseCustomer);
		service.initOwnership();
		return service;
	}

	private static void inject(Object target, String field, Object value) throws Exception {
		Field f = ApplicationModeService.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	@Test
	@DisplayName("ApplicationModeService exposes the resolved values; existing mode methods unchanged")
	void serviceDelegates() throws Exception {
		ApplicationModeService s = service(new MockEnvironment(), false, false, false);
		assertEquals(NodeType.STORE, s.getNodeType());
		assertEquals(ERP, s.ownerOf(DataDomain.CATALOGUE));
		assertEquals(L, s.ownerOf(DataDomain.LOYALTY));
		assertEquals(EnumSet.of(SalesUpstream.ERP), s.salesUpstreams());
		assertTrue(s.isErpMode());
		assertThrows(UnsupportedOperationException.class, () -> s.salesUpstreams().add(SalesUpstream.HEAD_OFFICE));
	}

	@Test
	@DisplayName("ApplicationModeService.isHeadOffice: true only with node.type=HEAD_OFFICE, false on the 4 profiles")
	void serviceIsHeadOffice() throws Exception {
		assertTrue(service(headOfficeEnv(), true, false, false).isHeadOffice());
		assertFalse(service(new MockEnvironment(), true, false, false).isHeadOffice());
		assertFalse(service(new MockEnvironment(), false, false, false).isHeadOffice());
		assertFalse(service(new MockEnvironment(), true, false, true).isHeadOffice());
		assertFalse(service(new MockEnvironment(), true, true, false).isHeadOffice());
	}

	@Test
	@DisplayName("ApplicationModeService.isHeadOfficeLinked: true only with a non-blank headoffice.url")
	void serviceIsHeadOfficeLinked() throws Exception {
		assertTrue(service(linkEnv(), true, false, false).isHeadOfficeLinked());
		assertTrue(service(linkEnv(), false, false, false).isHeadOfficeLinked());
		assertFalse(service(new MockEnvironment(), true, false, false).isHeadOfficeLinked());
		assertFalse(service(new MockEnvironment().withProperty("headoffice.url", " "), true, false, false)
				.isHeadOfficeLinked());
		assertFalse(service(headOfficeEnv(), true, false, false).isHeadOfficeLinked());
	}

	// --- Copies down (task 3.1) ---

	@Test
	@DisplayName("Task 3.1: an explicit ownership.promotions=HEAD_OFFICE without headoffice.url is refused on standalone and ERP flags")
	void headOfficePromotionsNeedUrl() {
		for (String value : new String[] { "HEAD_OFFICE", " head_office " }) {
			MockEnvironment env = new MockEnvironment().withProperty("ownership.promotions", value);
			IllegalStateException standalone = assertThrows(IllegalStateException.class, () -> standalone(env));
			assertTrue(standalone.getMessage().startsWith("Missing value for property headoffice.url: required when "
					+ "ownership.promotions is HEAD_OFFICE ('" + value + "')"), standalone.getMessage());
			assertThrows(IllegalStateException.class, () -> erp(env));
		}
		assertThrows(IllegalStateException.class,
				() -> standalone(new MockEnvironment().withProperty("headoffice.url", " ")
						.withProperty("ownership.promotions", "HEAD_OFFICE")), "a blank URL is no URL");
	}

	@Test
	@DisplayName("Task 3.1: promotions LOCAL without the URL, HEAD_OFFICE with it, and the 4 profiles without the key start as before")
	void headOfficePromotionsAccepted() {
		assertEquals(L, standalone(new MockEnvironment().withProperty("ownership.promotions", "LOCAL"))
				.ownerOf(DataDomain.PROMOTIONS));
		assertEquals(HO, standalone(linkEnv().withProperty("ownership.promotions", "HEAD_OFFICE"))
				.ownerOf(DataDomain.PROMOTIONS));
		assertEquals(HO, erp(linkEnv().withProperty("ownership.promotions", "HEAD_OFFICE"))
				.ownerOf(DataDomain.PROMOTIONS));
		assertEquals(L, standalone(new MockEnvironment()).ownerOf(DataDomain.PROMOTIONS));
		assertEquals(L, erp(new MockEnvironment()).ownerOf(DataDomain.PROMOTIONS));
		assertEquals(L, franchiseCustomer(new MockEnvironment()).ownerOf(DataDomain.PROMOTIONS));
		assertEquals(L, franchiseAdmin(new MockEnvironment()).ownerOf(DataDomain.PROMOTIONS));
		// Step 6: an explicit catalogue HEAD_OFFICE is checked like promotions (it was accepted without the URL before)
		assertThrows(IllegalStateException.class,
				() -> standalone(new MockEnvironment().withProperty("ownership.catalogue", "HEAD_OFFICE")));
		assertEquals(HO, standalone(linkEnv().withProperty("ownership.catalogue", "HEAD_OFFICE")).ownerOf(DataDomain.CATALOGUE));
	}

	@Test
	@DisplayName("Task 3.1: a head office keeps its own message for ownership.promotions=HEAD_OFFICE")
	void headOfficePromotionsOnHeadOffice() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> standalone(headOfficeEnv().withProperty("ownership.promotions", "HEAD_OFFICE")));
		assertTrue(e.getMessage().startsWith("Invalid value 'HEAD_OFFICE' for property ownership.promotions: on a head office"),
				e.getMessage());
	}

	@Test
	@DisplayName("Task 3.1: headoffice.pull.interval-seconds at least 1 accepted; below 1 or not a number refused; not checked without headoffice.url")
	void pullInterval() {
		for (String value : new String[] { "0", "-5", "abc", "", "1.5" }) {
			IllegalStateException e = assertThrows(IllegalStateException.class,
					() -> standalone(linkEnv().withProperty("headoffice.pull.interval-seconds", value)), value);
			assertTrue(e.getMessage().startsWith("Invalid value '" + value
					+ "' for property headoffice.pull.interval-seconds: a whole number of seconds, at least 1"),
					e.getMessage());
		}
		standalone(linkEnv().withProperty("headoffice.pull.interval-seconds", "1"));
		standalone(linkEnv().withProperty("headoffice.pull.interval-seconds", " 120 "));
		standalone(new MockEnvironment().withProperty("headoffice.pull.interval-seconds", "0"));
	}

	// --- Shared loyalty (step 4) ---

	@Test
	@DisplayName("Step 4: an explicit ownership.loyalty=HEAD_OFFICE without headoffice.url is refused on standalone and ERP flags")
	void headOfficeLoyaltyNeedsUrl() {
		for (String value : new String[] { "HEAD_OFFICE", " head_office " }) {
			MockEnvironment env = new MockEnvironment().withProperty("ownership.loyalty", value);
			IllegalStateException standalone = assertThrows(IllegalStateException.class, () -> standalone(env));
			assertTrue(standalone.getMessage().startsWith("Missing value for property headoffice.url: required when "
					+ "ownership.loyalty is HEAD_OFFICE ('" + value + "')"), standalone.getMessage());
			assertThrows(IllegalStateException.class, () -> erp(env));
		}
		assertThrows(IllegalStateException.class,
				() -> standalone(new MockEnvironment().withProperty("headoffice.url", " ")
						.withProperty("ownership.loyalty", "HEAD_OFFICE")), "a blank URL is no URL");
	}

	@Test
	@DisplayName("Step 4: loyalty LOCAL without the URL, HEAD_OFFICE with it; the 4 profiles keep loyalty LOCAL; a head office keeps its message")
	void headOfficeLoyaltyAccepted() throws Exception {
		assertEquals(L, standalone(new MockEnvironment().withProperty("ownership.loyalty", "LOCAL"))
				.ownerOf(DataDomain.LOYALTY));
		assertEquals(HO, standalone(linkEnv().withProperty("ownership.loyalty", "HEAD_OFFICE"))
				.ownerOf(DataDomain.LOYALTY));
		assertEquals(HO, erp(linkEnv().withProperty("ownership.loyalty", "HEAD_OFFICE")).ownerOf(DataDomain.LOYALTY));
		assertEquals(L, standalone(new MockEnvironment()).ownerOf(DataDomain.LOYALTY));
		assertEquals(L, erp(new MockEnvironment()).ownerOf(DataDomain.LOYALTY));
		assertEquals(L, franchiseCustomer(new MockEnvironment()).ownerOf(DataDomain.LOYALTY));
		assertEquals(L, franchiseAdmin(new MockEnvironment()).ownerOf(DataDomain.LOYALTY));
		assertTrue(service(linkEnv().withProperty("ownership.loyalty", "HEAD_OFFICE"), true, false, false)
				.isLoyaltyOwnedByHeadOffice());
		assertFalse(service(linkEnv(), true, false, false).isLoyaltyOwnedByHeadOffice());
		assertFalse(service(new MockEnvironment(), false, false, false).isLoyaltyOwnedByHeadOffice());
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> standalone(headOfficeEnv().withProperty("ownership.loyalty", "HEAD_OFFICE")));
		assertTrue(e.getMessage().startsWith("Invalid value 'HEAD_OFFICE' for property ownership.loyalty: on a head office"),
				e.getMessage());
	}

	@Test
	@DisplayName("Step 4: headoffice.loyalty-push.interval-seconds at least 1 accepted; below 1 or not a number refused; not checked without headoffice.url")
	void loyaltyPushInterval() {
		for (String value : new String[] { "0", "-5", "abc", "", "1.5" }) {
			IllegalStateException e = assertThrows(IllegalStateException.class,
					() -> standalone(linkEnv().withProperty("headoffice.loyalty-push.interval-seconds", value)), value);
			assertTrue(e.getMessage().startsWith("Invalid value '" + value
					+ "' for property headoffice.loyalty-push.interval-seconds: a whole number of seconds, at least 1"),
					e.getMessage());
		}
		standalone(linkEnv().withProperty("headoffice.loyalty-push.interval-seconds", " 30 "));
		standalone(new MockEnvironment().withProperty("headoffice.loyalty-push.interval-seconds", "0"));
	}

	@Test
	@DisplayName("ApplicationModeService fails at startup on an invalid value")
	void serviceFailsOnInvalidValue() {
		MockEnvironment env = new MockEnvironment().withProperty("ownership.loyalty", "ERP");
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service(env, true, false, false));
		assertTrue(e.getMessage().contains("ownership.loyalty"));
	}
}
