package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.headoffice.service.HoDeliveryService;
import com.digithink.zsretail.headoffice.service.HoPriceListService;
import com.digithink.zsretail.holink.scheduler.HeartbeatJob;
import com.digithink.zsretail.holink.scheduler.SalesPushJob;
import com.digithink.zsretail.holink.service.CatalogueDownHandler;
import com.digithink.zsretail.holink.service.DeliveryReceptionService;
import com.digithink.zsretail.holink.service.StoreCatalogueGuard;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.NodeType;
import com.digithink.zsretail.model.enumeration.SalesUpstream;

/**
 * Head office plan, task 8.1: the presets network-headoffice and network-store give a franchise network as a head office
 * and stores (design 2.3, franchise column). The real profile files are read from the classpath, no context; the table
 * is the one of docs/deployment-modes.md, "Presets". Since task 9.4a the legacy franchise profiles no longer exist.
 */
class NetworkPresetTruthTableTest {

	private static MockEnvironment profile(String name) throws Exception {
		Properties properties = new Properties();
		try (InputStream in = NetworkPresetTruthTableTest.class.getResourceAsStream("/application-" + name + ".properties")) {
			assertNotNull(in, "profile " + name);
			properties.load(in);
		}
		MockEnvironment env = new MockEnvironment();
		for (String key : properties.stringPropertyNames()) {
			env.setProperty(key, properties.getProperty(key));
		}
		return env;
	}

	private static NodeOwnership resolve(MockEnvironment env) {
		return NodeOwnership.resolve(env, Boolean.parseBoolean(env.getProperty("application.standalone", "false")));
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	private static void owners(NodeOwnership ownership, DataOwner catalogue, DataOwner customers, DataOwner promotions,
			DataOwner loyalty, DataOwner supply) {
		assertEquals(catalogue, ownership.ownerOf(DataDomain.CATALOGUE), "catalogue");
		assertEquals(customers, ownership.ownerOf(DataDomain.CUSTOMERS), "customers");
		assertEquals(promotions, ownership.ownerOf(DataDomain.PROMOTIONS), "promotions");
		assertEquals(loyalty, ownership.ownerOf(DataDomain.LOYALTY), "loyalty");
		assertEquals(supply, ownership.ownerOf(DataDomain.SUPPLY), "supply");
	}

	/** Link, sales push, pull, catalogue, supply, head office without ERP; never a head office with ERP. */
	private static void switches(MockEnvironment env, boolean link, boolean salesPush, boolean pull, boolean catalogue,
			boolean supply, boolean headOfficeStandalone) {
		assertEquals(link, NodeOwnership.isHeadOfficeLinkSet(env), "link");
		assertEquals(salesPush, NodeOwnership.isHeadOfficeSalesPushSet(env), "sales push");
		assertEquals(pull, NodeOwnership.isHeadOfficePullSet(env), "pull");
		assertEquals(catalogue, NodeOwnership.isCatalogueFromHeadOffice(env), "catalogue from the head office");
		assertEquals(supply, NodeOwnership.isSupplyFromHeadOffice(env), "supply from the head office");
		assertEquals(headOfficeStandalone, NodeOwnership.isHeadOfficeStandaloneSet(env), "head office without ERP");
		assertFalse(NodeOwnership.isHeadOfficeErpSet(env), "head office with ERP");
		assertEquals("true", env.getProperty("application.standalone"));
		assertEquals("false", env.getProperty("pos.pricing.enable-sales-price-group"));
		assertNull(env.getProperty("franchise.admin"), "no franchise key (task 9.4a)");
		assertNull(env.getProperty("franchise.customer"), "no franchise key (task 9.4a)");
	}

	@Test
	@DisplayName("network-headoffice: head office without ERP, everything local, sales nowhere")
	void headOfficePreset() throws Exception {
		MockEnvironment env = profile("network-headoffice");
		NodeOwnership ownership = resolve(env);
		assertEquals(NodeType.HEAD_OFFICE, ownership.getNodeType());
		owners(ownership, DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.LOCAL);
		assertEquals(Collections.emptySet(), ownership.getSalesUpstreams());
		switches(env, false, false, false, false, false, true);
	}

	@Test
	@DisplayName("network-store: store, catalogue and supply from the head office, promotions, loyalty and customers local, sales to the head office")
	void storePreset() throws Exception {
		MockEnvironment env = profile("network-store");
		NodeOwnership ownership = resolve(env);
		assertEquals(NodeType.STORE, ownership.getNodeType());
		owners(ownership, DataOwner.HEAD_OFFICE, DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.LOCAL, DataOwner.HEAD_OFFICE);
		assertEquals(EnumSet.of(SalesUpstream.HEAD_OFFICE), ownership.getSalesUpstreams());
		switches(env, true, true, true, true, true, false);
	}

	@Test
	@DisplayName("Beans: the head office beans only on network-headoffice, the store beans only on network-store")
	void beans() throws Exception {
		MockEnvironment headOffice = profile("network-headoffice");
		MockEnvironment store = profile("network-store");
		for (Class<?> bean : new Class<?>[] { HeartbeatJob.class, SalesPushJob.class, CatalogueDownHandler.class,
				StoreCatalogueGuard.class, DeliveryReceptionService.class }) {
			assertFalse(registered(headOffice, bean), bean.getSimpleName());
			assertTrue(registered(store, bean), bean.getSimpleName());
		}
		for (Class<?> bean : new Class<?>[] { HoPriceListService.class, HoDeliveryService.class }) {
			assertTrue(registered(headOffice, bean), bean.getSimpleName());
			assertFalse(registered(store, bean), bean.getSimpleName());
		}
	}
}
