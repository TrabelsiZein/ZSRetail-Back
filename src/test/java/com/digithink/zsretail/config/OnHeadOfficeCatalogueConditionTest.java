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
import com.digithink.zsretail.headoffice.controller.HoSupplyPriceAPI;
import com.digithink.zsretail.headoffice.service.HoCatalogueService;
import com.digithink.zsretail.headoffice.service.HoDeliveryService;
import com.digithink.zsretail.headoffice.service.HoNetworkStockService;
import com.digithink.zsretail.headoffice.service.HoPriceListService;
import com.digithink.zsretail.headoffice.service.HoSupplyPriceService;
import com.digithink.zsretail.holink.controller.CatalogueNetworkAPI;
import com.digithink.zsretail.holink.scheduler.CopiesDownJob;
import com.digithink.zsretail.holink.service.CatalogueCopyWriter;
import com.digithink.zsretail.holink.service.CatalogueDownHandler;
import com.digithink.zsretail.holink.service.CatalogueRights;
import com.digithink.zsretail.holink.service.StoreCatalogueGuard;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;

/**
 * Head office plan, step 6: the catalogue beans of a store exist only on a standalone store with headoffice.url and an
 * explicit ownership.catalogue=HEAD_OFFICE. A franchise customer (catalogue derived HEAD_OFFICE for its legacy sync)
 * never gets them, even with headoffice.url; an explicit value with a franchise flag or an ERP, or without the URL,
 * stops the startup. The head office catalogue and price lists exist only on a head office without an ERP. Bare bean
 * registry: nothing is created and no context is started.
 */
class OnHeadOfficeCatalogueConditionTest {

	private static final Class<?>[] STORE_BEANS = { CatalogueCopyWriter.class, CatalogueDownHandler.class,
			CatalogueRights.class, StoreCatalogueGuard.class, CatalogueNetworkAPI.class };

	private static final Class<?>[] HEAD_OFFICE_BEANS = { HoCatalogueService.class, HoPriceListService.class,
			HoPriceListAPI.class, HoDeliveryService.class, HoDeliveryAPI.class, HoNetworkStockService.class,
			HoNetworkStockAPI.class, HoSupplyPriceService.class, HoSupplyPriceAPI.class }; // step 7A, 7B

	private static MockEnvironment link() {
		return new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
	}

	private static MockEnvironment standalone(MockEnvironment env) {
		return env.withProperty("application.standalone", "true");
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		return OnHeadOfficePullConditionTest.registered(env, beanClass);
	}

	private static NodeOwnership resolve(MockEnvironment env) {
		return NodeOwnership.resolve(env, Boolean.parseBoolean(env.getProperty("application.standalone", "false")),
				Boolean.parseBoolean(env.getProperty("franchise.admin", "false")),
				Boolean.parseBoolean(env.getProperty("franchise.customer", "false")));
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
	@DisplayName("Off: no URL, catalogue LOCAL or absent, the 4 profiles, a head office")
	void off() {
		MockEnvironment[] off = { new MockEnvironment(), standalone(new MockEnvironment()), standalone(link()), link(),
				standalone(link()).withProperty("ownership.catalogue", "LOCAL"),
				link().withProperty("ownership.catalogue", "ERP"),
				standalone(link()).withProperty("ownership.promotions", "HEAD_OFFICE"),
				standalone(link()).withProperty("ownership.loyalty", "HEAD_OFFICE"),
				standalone(new MockEnvironment()).withProperty("node.type", "HEAD_OFFICE"),
				standalone(new MockEnvironment()).withProperty("franchise.admin", "true") };
		for (MockEnvironment env : off) {
			assertFalse(NodeOwnership.isCatalogueFromHeadOffice(env));
			for (Class<?> bean : STORE_BEANS) {
				assertFalse(registered(env, bean), bean.getSimpleName());
			}
		}
	}

	@Test
	@DisplayName("A franchise customer never gets the catalogue beans, even with headoffice.url (derived HEAD_OFFICE)")
	void franchiseCustomer() {
		MockEnvironment customer = standalone(new MockEnvironment()).withProperty("franchise.customer", "true");
		MockEnvironment customerLinked = standalone(link()).withProperty("franchise.customer", "true");
		for (MockEnvironment env : new MockEnvironment[] { customer, customerLinked }) {
			assertEquals(DataOwner.HEAD_OFFICE, resolve(env).ownerOf(DataDomain.CATALOGUE), "derived for the legacy sync");
			assertFalse(NodeOwnership.isCatalogueFromHeadOffice(env));
			for (Class<?> bean : STORE_BEANS) {
				assertFalse(registered(env, bean), bean.getSimpleName());
			}
		}
		assertTrue(registered(customerLinked, CopiesDownJob.class), "as before step 6: the job, no catalogue handler");
	}

	@Test
	@DisplayName("The startup stops on an explicit ownership.catalogue=HEAD_OFFICE without the URL, with a franchise flag or"
			+ " with an ERP")
	void startupRefusals() {
		IllegalStateException noUrl = assertThrows(IllegalStateException.class,
				() -> resolve(standalone(new MockEnvironment()).withProperty("ownership.catalogue", "HEAD_OFFICE")));
		assertTrue(noUrl.getMessage().startsWith("Missing value for property headoffice.url: required when"
				+ " ownership.catalogue is HEAD_OFFICE"), noUrl.getMessage());
		IllegalStateException franchise = assertThrows(IllegalStateException.class,
				() -> resolve(standalone(link()).withProperty("franchise.customer", "true")
						.withProperty("ownership.catalogue", "HEAD_OFFICE")));
		assertTrue(franchise.getMessage().startsWith(
				"Invalid combination: ownership.catalogue=HEAD_OFFICE with franchise.customer=true"), franchise.getMessage());
		IllegalStateException admin = assertThrows(IllegalStateException.class,
				() -> resolve(standalone(link()).withProperty("franchise.admin", "true")
						.withProperty("ownership.catalogue", "HEAD_OFFICE")));
		assertTrue(admin.getMessage().startsWith(
				"Invalid combination: ownership.catalogue=HEAD_OFFICE with franchise.admin=true"), admin.getMessage());
		IllegalStateException erp = assertThrows(IllegalStateException.class,
				() -> resolve(link().withProperty("ownership.catalogue", "HEAD_OFFICE")));
		assertTrue(erp.getMessage().startsWith(
				"Invalid combination: ownership.catalogue=HEAD_OFFICE with application.standalone=false"), erp.getMessage());
		assertThrows(IllegalStateException.class,
				() -> NodeOwnership.isCatalogueFromHeadOffice(link().withProperty("ownership.catalogue", "HEAD_OFFICE")));
	}

	@Test
	@DisplayName("Head office side: the catalogue and price lists only on a head office without an ERP")
	void headOfficeBeans() {
		MockEnvironment standaloneHeadOffice = standalone(new MockEnvironment()).withProperty("node.type", "HEAD_OFFICE");
		MockEnvironment erpHeadOffice = new MockEnvironment().withProperty("node.type", "HEAD_OFFICE");
		for (Class<?> bean : HEAD_OFFICE_BEANS) {
			assertTrue(registered(standaloneHeadOffice, bean), bean.getSimpleName());
			assertFalse(registered(erpHeadOffice, bean), bean.getSimpleName());
			assertFalse(registered(standalone(link()).withProperty("ownership.catalogue", "HEAD_OFFICE"), bean));
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeStandalone.class), bean.getSimpleName());
		}
		assertEquals(OnHeadOfficeCatalogueCondition.class,
				ConditionalOnHeadOfficeCatalogue.class.getAnnotation(Conditional.class).value()[0]);
		assertEquals(OnHeadOfficeStandaloneCondition.class,
				ConditionalOnHeadOfficeStandalone.class.getAnnotation(Conditional.class).value()[0]);
	}
}
