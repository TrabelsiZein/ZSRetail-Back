package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.Collections;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Conditional;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.headoffice.controller.ErpReferenceLocationAPI;
import com.digithink.zsretail.headoffice.service.ErpReferenceLocationService;
import com.digithink.zsretail.headoffice.service.HeadOfficeErpGuard;
import com.digithink.zsretail.headoffice.service.HeadOfficeErpJobs;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.model.enumeration.NodeType;
import com.digithink.zsretail.support.TestModes;

/**
 * Head office plan, task 3.4: a head office with an ERP starts. The real profile files are read
 * (application-*.properties from the classpath, no context): headoffice-dynamics-dev is accepted, a head office with
 * catalogue, customers and supply owned by the ERP and sales going nowhere; the reference location beans exist only
 * there; the export guard on every head office, never on a store.
 */
class HeadOfficeErpProfileTest {

	/** Task 9.3: the old profile names, now presets with their machine files of deploy/. */
	private static MockEnvironment profile(String name) {
		switch (name) {
			case "headoffice-dynamics-dev":
				return Installations.machine("dev/headoffice-erp.properties");
			case "headoffice-dev":
				return Installations.machine("dev/headoffice.properties");
			case "dynamics-test":
				return Installations.machine("dev/store-test-nav.properties");
			case "dynamics-dev":
				return Installations.machine("dev/store-a-erp.properties");
			case "dynamics-prod":
				return Installations.machine("customers/erp-prod.properties");
			case "standalone-dev":
				return Installations.machine("dev/store-a.properties");
			default:
				throw new IllegalArgumentException(name);
		}
	}

	private static NodeOwnership resolve(MockEnvironment env) {
		return NodeOwnership.resolve(env);
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	@Test
	@DisplayName("headoffice-dynamics-dev starts: head office, ERP owners, sales nowhere, same database and port, NAV settings of the TEST instance (dynamics-test), never dev or prod")
	void profileAccepted() throws Exception {
		MockEnvironment env = profile("headoffice-dynamics-dev");
		NodeOwnership ownership = resolve(env);
		assertEquals(NodeType.HEAD_OFFICE, ownership.getNodeType());
		assertEquals(DataOwner.ERP, ownership.ownerOf(DataDomain.CATALOGUE));
		assertEquals(DataOwner.ERP, ownership.ownerOf(DataDomain.CUSTOMERS));
		assertEquals(DataOwner.LOCAL, ownership.ownerOf(DataDomain.PROMOTIONS));
		assertEquals(DataOwner.LOCAL, ownership.ownerOf(DataDomain.LOYALTY));
		assertEquals(DataOwner.ERP, ownership.ownerOf(DataDomain.SUPPLY));
		assertEquals(Collections.emptySet(), ownership.getSalesUpstreams());
		assertTrue(NodeOwnership.isHeadOfficeErpSet(env));
		assertEquals("true", env.getProperty("erp.dynamicsnav.enabled"));
		assertEquals("true", env.getProperty("erp.sync.enabled"));
		MockEnvironment plain = profile("headoffice-dev");
		assertEquals(plain.getProperty("spring.datasource.url"), env.getProperty("spring.datasource.url"));
		assertEquals(plain.getProperty("server.port"), env.getProperty("server.port"));
		// The TEST NAV instance only: the dev and prod profiles point to the customer's production NAV
		MockEnvironment test = profile("dynamics-test");
		for (String key : new String[] { "erp.dynamicsnav.base-url", "erp.dynamicsnav.company", "erp.dynamicsnav.domain",
				"erp.dynamicsnav.username", "erp.dynamicsnav.password" }) {
			assertEquals(test.getProperty(key), env.getProperty(key), key);
		}
		for (String production : new String[] { "dynamics-dev", "dynamics-prod" }) {
			String url = profile(production).getProperty("erp.dynamicsnav.base-url");
			assertFalse(env.getProperty("erp.dynamicsnav.base-url").equalsIgnoreCase(url),
					"never the NAV of " + production);
			assertFalse(env.getProperty("erp.dynamicsnav.base-url").contains(java.net.URI.create(url).getHost()),
					"never the host of " + production);
		}
	}

	@Test
	@DisplayName("Reference location beans only on a head office with ERP; the export guard on every head office, never on a store")
	void beans() throws Exception {
		MockEnvironment erpHeadOffice = profile("headoffice-dynamics-dev");
		MockEnvironment headOffice = profile("headoffice-dev");
		MockEnvironment[] stores = { profile("dynamics-dev"), profile("standalone-dev") };
		for (Class<?> bean : new Class<?>[] { ErpReferenceLocationService.class, ErpReferenceLocationAPI.class }) {
			assertTrue(registered(erpHeadOffice, bean), bean.getSimpleName());
			assertFalse(registered(headOffice, bean), bean.getSimpleName());
			for (MockEnvironment store : stores) {
				assertFalse(registered(store, bean), bean.getSimpleName());
			}
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeErp.class));
		}
		for (Class<?> bean : new Class<?>[] { HeadOfficeErpJobs.class, HeadOfficeErpGuard.class }) {
			assertTrue(registered(erpHeadOffice, bean), bean.getSimpleName());
			assertTrue(registered(headOffice, bean), bean.getSimpleName());
			for (MockEnvironment store : stores) {
				assertFalse(registered(store, bean), bean.getSimpleName());
			}
		}
		assertFalse(NodeOwnership.isHeadOfficeErpSet(headOffice));
		assertFalse(NodeOwnership.isHeadOfficeErpSet(stores[0]), "an ERP store is not a head office");
		assertEquals(OnHeadOfficeErpCondition.class,
				ConditionalOnHeadOfficeErp.class.getAnnotation(Conditional.class).value()[0]);
		// Task 9.3: no owner key is a head office without an ERP; the ERP owners make it one with an ERP
		assertFalse(NodeOwnership.isHeadOfficeErpSet(new MockEnvironment().withProperty("node.type", "head_office")),
				"no owner key: without an ERP");
		assertTrue(NodeOwnership.isHeadOfficeErpSet(
				TestModes.erpOwners(new MockEnvironment().withProperty("node.type", "head_office"))), "the ERP owners");
	}
}
