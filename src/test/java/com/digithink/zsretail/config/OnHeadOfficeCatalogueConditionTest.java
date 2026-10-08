package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Conditional;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.headoffice.controller.HoDeliveryAPI;
import com.digithink.zsretail.headoffice.controller.HoNetworkStockAPI;
import com.digithink.zsretail.headoffice.controller.HoPriceListAPI;
import com.digithink.zsretail.headoffice.controller.HoSupplyInvoiceAPI;
import com.digithink.zsretail.headoffice.controller.HoSupplyPriceAPI;
import com.digithink.zsretail.headoffice.service.HoCatalogueService;
import com.digithink.zsretail.headoffice.service.HoDeliveryService;
import com.digithink.zsretail.headoffice.service.HoNetworkStockService;
import com.digithink.zsretail.headoffice.service.HoPriceListService;
import com.digithink.zsretail.headoffice.service.HoSupplyInvoiceService;
import com.digithink.zsretail.headoffice.service.HoSupplyPriceService;
import com.digithink.zsretail.holink.controller.CatalogueNetworkAPI;
import com.digithink.zsretail.holink.scheduler.CopiesDownJob;
import com.digithink.zsretail.holink.service.CatalogueCopyWriter;
import com.digithink.zsretail.holink.service.CatalogueDownHandler;
import com.digithink.zsretail.holink.service.CatalogueRights;
import com.digithink.zsretail.holink.service.StoreCatalogueGuard;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.support.TestModes;

/**
 * Head office plan, step 6: the catalogue beans of a store exist only on a standalone store with headoffice.url and an
 * explicit ownership.catalogue=HEAD_OFFICE; an explicit value with an ERP, or without the URL, stops the startup (and,
 * since task 9.4a, any franchise flag). The head office catalogue and price lists exist only on a head office without an ERP. Bare bean
 * registry: nothing is created and no context is started.
 */
class OnHeadOfficeCatalogueConditionTest {

	private static final Class<?>[] STORE_BEANS = { CatalogueCopyWriter.class, CatalogueDownHandler.class,
			CatalogueRights.class, StoreCatalogueGuard.class, CatalogueNetworkAPI.class };

	private static final Class<?>[] HEAD_OFFICE_BEANS = { HoCatalogueService.class, HoPriceListService.class,
			HoPriceListAPI.class, HoDeliveryService.class, HoDeliveryAPI.class, HoNetworkStockService.class,
			HoNetworkStockAPI.class, HoSupplyPriceService.class, HoSupplyPriceAPI.class,
			HoSupplyInvoiceService.class, HoSupplyInvoiceAPI.class }; // step 7A, 7B

	private static MockEnvironment link() {
		return new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
	}

	private static MockEnvironment standalone(MockEnvironment env) {
		return env; // task 9.3: without an ERP is the default (no owner key)
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		return OnHeadOfficePullConditionTest.registered(env, beanClass);
	}

	private static NodeOwnership resolve(MockEnvironment env) {
		return NodeOwnership.resolve(env);
	}

	@Test
	@DisplayName("On: a standalone store with headoffice.url and ownership.catalogue=HEAD_OFFICE (any case); the pull too")
	void on() {
		MockEnvironment on = standalone(link()).withProperty("ownership.catalogue", " head_office ");
		assertTrue(NodeOwnership.isCatalogueFromHeadOffice(on));
		assertTrue(NodeOwnership.isHeadOfficePullSet(on));
		assertEquals(DataOwner.HEAD_OFFICE, resolve(on).ownerOf(DataDomain.CATALOGUE));
		for (Class<?> bean : STORE_BEANS) {
			assertTrue(registered(on, bean), bean.getSimpleName());
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeCatalogue.class), bean.getSimpleName());
		}
		assertTrue(registered(on, CopiesDownJob.class));
		for (Class<?> bean : HEAD_OFFICE_BEANS) {
			assertFalse(registered(on, bean), bean.getSimpleName());
		}
	}

	@Test
	@DisplayName("Off: no URL, catalogue LOCAL or absent, the standalone and ERP profiles, a head office")
	void off() {
		MockEnvironment[] off = { new MockEnvironment(), standalone(new MockEnvironment()), standalone(link()), link(),
				standalone(link()).withProperty("ownership.catalogue", "LOCAL"),
				TestModes.erpOwners(link()), // an ERP store, linked
				standalone(link()).withProperty("ownership.promotions", "HEAD_OFFICE"),
				standalone(link()).withProperty("ownership.loyalty", "HEAD_OFFICE"),
				standalone(new MockEnvironment()).withProperty("node.type", "HEAD_OFFICE") };
		for (MockEnvironment env : off) {
			assertFalse(NodeOwnership.isCatalogueFromHeadOffice(env));
			for (Class<?> bean : STORE_BEANS) {
				assertFalse(registered(env, bean), bean.getSimpleName());
			}
		}
	}

	@Test
	@DisplayName("The startup stops on an explicit ownership.catalogue=HEAD_OFFICE without the URL or with an ERP, and on any"
			+ " franchise flag (task 9.4a)")
	void startupRefusals() {
		IllegalStateException noUrl = assertThrows(IllegalStateException.class,
				() -> resolve(standalone(new MockEnvironment()).withProperty("ownership.catalogue", "HEAD_OFFICE")));
		assertTrue(noUrl.getMessage().startsWith("Missing value for property headoffice.url: required when"
				+ " ownership.catalogue is HEAD_OFFICE"), noUrl.getMessage());
		IllegalStateException franchise = assertThrows(IllegalStateException.class,
				() -> resolve(standalone(link()).withProperty("franchise.customer", "true")
						.withProperty("ownership.catalogue", "HEAD_OFFICE")));
		assertTrue(franchise.getMessage().startsWith(
				"Invalid value 'true' for property franchise.customer: the franchise profiles were removed"),
				franchise.getMessage());
		IllegalStateException erp = assertThrows(IllegalStateException.class,
				() -> resolve(TestModes.erpOwners(link()).withProperty("ownership.catalogue", "HEAD_OFFICE")));
		assertTrue(erp.getMessage().startsWith(
				"Invalid combination: ownership.customers=ERP with ownership.catalogue=HEAD_OFFICE"), erp.getMessage());
		assertThrows(IllegalStateException.class,
				() -> NodeOwnership.isCatalogueFromHeadOffice(TestModes.erpOwners(link()).withProperty("ownership.catalogue", "HEAD_OFFICE")));
	}

	@Test
	@DisplayName("Head office side: the catalogue and price lists only on a head office without an ERP")
	void headOfficeBeans() {
		MockEnvironment standaloneHeadOffice = standalone(new MockEnvironment()).withProperty("node.type", "HEAD_OFFICE");
		MockEnvironment erpHeadOffice = TestModes.erpOwners(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"));
		for (Class<?> bean : HEAD_OFFICE_BEANS) {
			assertTrue(registered(standaloneHeadOffice, bean), bean.getSimpleName());
			assertFalse(registered(erpHeadOffice, bean), bean.getSimpleName());
			assertFalse(registered(standalone(link()).withProperty("ownership.catalogue", "HEAD_OFFICE"), bean));
			// Invoices from the ERP, step (b): the BLs, supply prices and invoices carry @ConditionalOnHeadOfficeOwnSupply
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeWithoutErp.class)
					|| bean.isAnnotationPresent(ConditionalOnHeadOfficeOwnSupply.class), bean.getSimpleName());
		}
		assertEquals(OnHeadOfficeCatalogueCondition.class,
				ConditionalOnHeadOfficeCatalogue.class.getAnnotation(Conditional.class).value()[0]);
		assertEquals(OnHeadOfficeWithoutErpCondition.class,
				ConditionalOnHeadOfficeWithoutErp.class.getAnnotation(Conditional.class).value()[0]);
	}
}
