package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.headoffice.controller.ErpReferenceLocationAPI;
import com.digithink.zsretail.headoffice.controller.HeadOfficeSupplyAPI;
import com.digithink.zsretail.headoffice.controller.HoDeliveryAPI;
import com.digithink.zsretail.headoffice.controller.HoNetworkStockAPI;
import com.digithink.zsretail.headoffice.controller.HoPriceListAPI;
import com.digithink.zsretail.headoffice.controller.HoSupplyInvoiceAPI;
import com.digithink.zsretail.headoffice.controller.HoSupplyPriceAPI;
import com.digithink.zsretail.headoffice.security.HeadOfficeWithoutStockFilter;
import com.digithink.zsretail.headoffice.service.ErpReferenceLocationService;
import com.digithink.zsretail.headoffice.service.HeadOfficeErpGuard;
import com.digithink.zsretail.headoffice.service.HeadOfficeErpJobs;
import com.digithink.zsretail.headoffice.service.HoCatalogueService;
import com.digithink.zsretail.headoffice.service.HoDeliveryService;
import com.digithink.zsretail.headoffice.service.HoNetworkStockService;
import com.digithink.zsretail.headoffice.service.HoPriceListService;
import com.digithink.zsretail.headoffice.service.HoSupplyInvoiceService;
import com.digithink.zsretail.headoffice.service.HoSupplyPriceService;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.support.TestModes;

/**
 * ERP catalogue, step 1 (ownership only): a head office whose catalogue comes from the ERP while its customers and its
 * supply stay its own. It starts, answers isErpCatalogueOnly, keeps every bean of a head office without an ERP and has
 * no ERP reference location. Every other partial combination is refused with the message of step 9, and a store with
 * the catalogue only from the ERP is refused. The real type files are read (no Spring context).
 */
class ErpCatalogueHeadOfficeTest {

	/** The beans of a head office without an ERP (@ConditionalOnHeadOfficeWithoutErp): catalogue feed, price lists, BLs, supply. */
	static final List<Class<?>> WITHOUT_ERP_BEANS = Arrays.asList(HoCatalogueService.class, HoPriceListService.class,
			HoPriceListAPI.class, HoDeliveryService.class, HoDeliveryAPI.class, HeadOfficeSupplyAPI.class,
			HoSupplyPriceService.class, HoSupplyPriceAPI.class, HoSupplyInvoiceService.class, HoSupplyInvoiceAPI.class,
			HoNetworkStockService.class, HoNetworkStockAPI.class);

	static final List<Class<?>> REFERENCE_LOCATION_BEANS = Arrays.asList(ErpReferenceLocationService.class,
			ErpReferenceLocationAPI.class);

	/** The head office type file with the catalogue from the ERP (customers and supply LOCAL, as the type file says). */
	private static MockEnvironment catalogueOnlyHeadOffice() {
		MockEnvironment env = Installations.type("headoffice");
		env.setProperty("ownership.catalogue", "ERP");
		return env;
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	private static String refusal(MockEnvironment env) {
		return assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env)).getMessage();
	}

	@Test
	@DisplayName("Head office, catalogue ERP, customers and supply LOCAL: starts; catalogue from the ERP, customers and supply not")
	void startsOnHeadOffice() {
		MockEnvironment env = catalogueOnlyHeadOffice();
		NodeOwnership ownership = NodeOwnership.resolve(env);
		assertEquals(DataOwner.ERP, ownership.ownerOf(DataDomain.CATALOGUE));
		assertEquals(DataOwner.LOCAL, ownership.ownerOf(DataDomain.CUSTOMERS));
		assertEquals(DataOwner.LOCAL, ownership.ownerOf(DataDomain.SUPPLY));
		assertTrue(ownership.isErpCatalogueOnly());
		assertTrue(NodeOwnership.isErpCatalogueOnlySet(env));
		assertTrue(ownership.hasErp());
		assertTrue(ownership.isCatalogueFromErp());
		assertFalse(ownership.isCustomersFromErp());
		assertFalse(ownership.isSupplyFromErp());
		assertTrue(NodeOwnership.isHeadOfficeWithoutErpSet(env));
		assertFalse(NodeOwnership.isHeadOfficeErpSet(env));

		ApplicationModeService mode = TestModes.of(env);
		assertTrue(mode.isHeadOffice());
		assertTrue(mode.isErpCatalogueOnly());
		assertTrue(mode.hasErp());
		assertTrue(mode.isCatalogueFromErp());
		assertFalse(mode.isCustomersFromErp());
		assertFalse(mode.isSupplyFromErp());
	}

	@Test
	@DisplayName("The same with the owner keys absent (LOCAL by default), any case, and on the Happyness head office without stock")
	void otherSpellings() {
		MockEnvironment absent = new MockEnvironment().withProperty("node.type", "head_office")
				.withProperty("ownership.catalogue", " erp ");
		assertTrue(NodeOwnership.resolve(absent).isErpCatalogueOnly());

		MockEnvironment happyness = Installations.config("local/happyness_ho.properties");
		happyness.setProperty("ownership.catalogue", "ERP");
		NodeOwnership ownership = NodeOwnership.resolve(happyness);
		assertTrue(ownership.isErpCatalogueOnly());
		assertTrue(NodeOwnership.isHeadOfficeWithoutStockSet(happyness), "the no-stock key is read as before");
		assertTrue(registered(happyness, HeadOfficeWithoutStockFilter.class));
	}

	@Test
	@DisplayName("Every other partial combination is refused with the step 9 message, on a head office and on a store")
	void otherCombinationsRefused() {
		String message = ". The ERP owns the catalogue, the customers and the supply together: set all three to ERP or"
				+ " none of them (docs/deployment-modes.md).";

		MockEnvironment store = Installations.type("store");
		store.setProperty("ownership.catalogue", "ERP");
		assertEquals("Invalid combination: ownership.catalogue=ERP with ownership.customers=LOCAL" + message,
				refusal(store), "a store with the catalogue only from the ERP");
		assertFalse(NodeOwnership.isErpCatalogueOnlySet(store), "a store is not resolved for the head office question");

		MockEnvironment withCustomers = catalogueOnlyHeadOffice();
		withCustomers.setProperty("ownership.customers", "ERP");
		assertEquals("Invalid combination: ownership.catalogue=ERP with ownership.supply=LOCAL" + message,
				refusal(withCustomers));

		MockEnvironment withSupply = catalogueOnlyHeadOffice();
		withSupply.setProperty("ownership.supply", "ERP");
		assertEquals("Invalid combination: ownership.catalogue=ERP with ownership.customers=LOCAL" + message,
				refusal(withSupply));

		MockEnvironment customersOnly = Installations.type("headoffice");
		customersOnly.setProperty("ownership.customers", "ERP");
		assertEquals("Invalid combination: ownership.customers=ERP with ownership.catalogue=LOCAL" + message,
				refusal(customersOnly));

		MockEnvironment supplyOnly = Installations.type("headoffice");
		supplyOnly.setProperty("ownership.supply", "ERP");
		assertEquals("Invalid combination: ownership.supply=ERP with ownership.catalogue=LOCAL" + message,
				refusal(supplyOnly));
	}

	@Test
	@DisplayName("Beans: those of a head office without an ERP exist, the reference location does not, the ERP job guard does")
	void beans() {
		MockEnvironment env = catalogueOnlyHeadOffice();
		for (Class<?> bean : WITHOUT_ERP_BEANS) {
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeWithoutErp.class), bean.getSimpleName());
			assertTrue(registered(env, bean), bean.getSimpleName());
		}
		for (Class<?> bean : REFERENCE_LOCATION_BEANS) {
			assertFalse(registered(env, bean), bean.getSimpleName());
		}
		assertTrue(registered(env, HeadOfficeErpJobs.class));
		assertTrue(registered(env, HeadOfficeErpGuard.class));

		// The head office with an ERP keeps its reference location and has none of the beans above (as before)
		MockEnvironment erpHeadOffice = Installations.machine("dev/headoffice-erp.properties");
		for (Class<?> bean : WITHOUT_ERP_BEANS) {
			assertFalse(registered(erpHeadOffice, bean), bean.getSimpleName());
		}
		for (Class<?> bean : REFERENCE_LOCATION_BEANS) {
			assertTrue(registered(erpHeadOffice, bean), bean.getSimpleName());
		}
	}

	@Test
	@DisplayName("Startup summary: says it on that head office only")
	void summary() {
		String phrase = "only the catalogue from the ERP (customers and supply kept here)";
		MockEnvironment env = catalogueOnlyHeadOffice();
		String line = InstallationSummary.summary(env, NodeOwnership.resolve(env));
		assertTrue(line.contains("catalogue ERP, customers LOCAL"), line);
		assertTrue(line.contains(phrase), line);
		for (String machine : Installations.machineFiles()) {
			MockEnvironment other = Installations.machine(machine);
			assertFalse(InstallationSummary.summary(other, NodeOwnership.resolve(other)).contains(phrase), machine);
		}
		for (String config : Installations.configFiles()) {
			MockEnvironment other = Installations.config(config);
			assertFalse(InstallationSummary.summary(other, NodeOwnership.resolve(other)).contains(phrase), config);
		}
	}
}
