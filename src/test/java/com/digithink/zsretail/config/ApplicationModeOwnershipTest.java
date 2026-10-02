package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
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
	@DisplayName("Precedence: franchise customer beats franchise admin, which beats standalone and ERP")
	void precedence() {
		assertRow(NodeOwnership.resolve(new MockEnvironment(), false, true, true), NodeType.STORE, HO, L, L, L, HO,
				EnumSet.of(SalesUpstream.HEAD_OFFICE));
		assertRow(NodeOwnership.resolve(new MockEnvironment(), false, true, false), NodeType.STORE, L, L, L, L, L,
				none());
	}

	// --- Explicit keys ---

	@Test
	@DisplayName("An explicit owner overrides only its own domain")
	void explicitOwnerOverridesOneDomain() {
		MockEnvironment env = new MockEnvironment().withProperty("ownership.promotions", "HEAD_OFFICE");
		assertRow(erp(env), NodeType.STORE, ERP, ERP, HO, L, ERP, EnumSet.of(SalesUpstream.ERP));
	}

	@Test
	@DisplayName("Every domain and the node type can be set explicitly")
	void allKeysExplicit() {
		MockEnvironment env = new MockEnvironment()
				.withProperty("node.type", "HEAD_OFFICE")
				.withProperty("ownership.catalogue", "ERP")
				.withProperty("ownership.customers", "HEAD_OFFICE")
				.withProperty("ownership.promotions", "LOCAL")
				.withProperty("ownership.loyalty", "HEAD_OFFICE")
				.withProperty("ownership.supply", "LOCAL")
				.withProperty("sales.upstream", "HEAD_OFFICE");
		assertRow(standalone(env), NodeType.HEAD_OFFICE, ERP, HO, L, HO, L, EnumSet.of(SalesUpstream.HEAD_OFFICE));
	}

	@Test
	@DisplayName("sales.upstream: a comma list gives both, an empty value gives none even in ERP mode")
	void salesUpstreamList() {
		assertEquals(EnumSet.of(SalesUpstream.ERP, SalesUpstream.HEAD_OFFICE),
				erp(new MockEnvironment().withProperty("sales.upstream", "ERP, HEAD_OFFICE")).getSalesUpstreams());
		assertEquals(none(), erp(new MockEnvironment().withProperty("sales.upstream", "")).getSalesUpstreams());
		assertEquals(none(), erp(new MockEnvironment().withProperty("sales.upstream", "  ")).getSalesUpstreams());
	}

	@Test
	@DisplayName("Values are trimmed and case-insensitive")
	void lenientCase() {
		MockEnvironment env = new MockEnvironment()
				.withProperty("node.type", " head_office ")
				.withProperty("ownership.loyalty", "Head_Office");
		NodeOwnership o = standalone(env);
		assertEquals(NodeType.HEAD_OFFICE, o.getNodeType());
		assertEquals(HO, o.ownerOf(DataDomain.LOYALTY));
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
	@DisplayName("ApplicationModeService fails at startup on an invalid value")
	void serviceFailsOnInvalidValue() {
		MockEnvironment env = new MockEnvironment().withProperty("ownership.loyalty", "ERP");
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> service(env, true, false, false));
		assertTrue(e.getMessage().contains("ownership.loyalty"));
	}
}
