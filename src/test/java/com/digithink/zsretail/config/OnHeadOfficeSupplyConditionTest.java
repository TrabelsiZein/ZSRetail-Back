package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Conditional;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.headoffice.controller.HeadOfficeSupplyAPI;
import com.digithink.zsretail.holink.controller.DeliveryReceptionAPI;
import com.digithink.zsretail.holink.scheduler.CopiesDownJob;
import com.digithink.zsretail.holink.scheduler.SupplyPushJob;
import com.digithink.zsretail.holink.service.DeliveryReceptionService;
import com.digithink.zsretail.holink.service.SupplyDownHandler;
import com.digithink.zsretail.holink.service.SupplyPushService;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.DataOwner;
import com.digithink.zsretail.service.franchise.FranchiseSupplyReceptionService;

/**
 * Head office plan, step 7A: the supply beans of a store (BL reception, the SUPPLY handler, the SUPPLY_PUSH job) exist
 * only on a standalone store with headoffice.url and an explicit ownership.supply=HEAD_OFFICE (with
 * ownership.catalogue=HEAD_OFFICE, required at startup). A franchise customer (supply derived HEAD_OFFICE for its
 * legacy reception) never gets them, even with headoffice.url, and keeps FranchiseSupplyReceptionService. The startup
 * refusals. The head office receiver exists only on a head office without an ERP. Bare bean registry.
 */
class OnHeadOfficeSupplyConditionTest {

	private static final Class<?>[] STORE_BEANS = { DeliveryReceptionService.class, SupplyDownHandler.class,
			SupplyPushService.class, SupplyPushJob.class, DeliveryReceptionAPI.class };

	private static MockEnvironment link() {
		return new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
	}

	private static MockEnvironment standalone(MockEnvironment env) {
		return env.withProperty("application.standalone", "true");
	}

	/** The store of step 7A: standalone, linked, catalogue and supply from the head office. */
	private static MockEnvironment supplied() {
		return standalone(link()).withProperty("ownership.catalogue", "HEAD_OFFICE").withProperty("ownership.supply",
				" head_office ");
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
	@DisplayName("On: a standalone store with headoffice.url, the catalogue and the supply from the head office; the pull too")
	void on() {
		MockEnvironment on = supplied();
		assertEquals(DataOwner.HEAD_OFFICE, resolve(on).ownerOf(DataDomain.SUPPLY));
		assertTrue(NodeOwnership.isSupplyFromHeadOffice(on));
		assertTrue(NodeOwnership.isHeadOfficePullSet(on));
		for (Class<?> bean : STORE_BEANS) {
			assertTrue(registered(on, bean), bean.getSimpleName());
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeSupply.class), bean.getSimpleName());
		}
		assertTrue(registered(on, CopiesDownJob.class));
		assertFalse(registered(on, FranchiseSupplyReceptionService.class));
		assertFalse(registered(on, HeadOfficeSupplyAPI.class));
		assertEquals(OnHeadOfficeSupplyCondition.class,
				ConditionalOnHeadOfficeSupply.class.getAnnotation(Conditional.class).value()[0]);
	}

	@Test
	@DisplayName("Off: no URL, supply LOCAL, ERP or absent, the catalogue alone, loyalty alone, the 4 profiles, a head office")
	void off() {
		MockEnvironment[] off = { new MockEnvironment(), standalone(new MockEnvironment()), standalone(link()), link(),
				standalone(link()).withProperty("ownership.catalogue", "HEAD_OFFICE"),
				standalone(link()).withProperty("ownership.catalogue", "HEAD_OFFICE").withProperty("ownership.supply",
						"LOCAL"),
				link().withProperty("ownership.supply", "ERP"),
				standalone(link()).withProperty("ownership.loyalty", "HEAD_OFFICE"),
				standalone(new MockEnvironment()).withProperty("node.type", "HEAD_OFFICE"),
				new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"),
				standalone(new MockEnvironment()).withProperty("franchise.admin", "true") };
		for (MockEnvironment env : off) {
			assertFalse(NodeOwnership.isSupplyFromHeadOffice(env));
			for (Class<?> bean : STORE_BEANS) {
				assertFalse(registered(env, bean), bean.getSimpleName());
			}
		}
	}

	@Test
	@DisplayName("A franchise customer never gets them, even with headoffice.url; its legacy supply reception stays")
	void franchiseCustomer() {
		MockEnvironment customer = standalone(new MockEnvironment()).withProperty("franchise.customer", "true");
		MockEnvironment customerLinked = standalone(link()).withProperty("franchise.customer", "true");
		for (MockEnvironment env : new MockEnvironment[] { customer, customerLinked }) {
			assertEquals(DataOwner.HEAD_OFFICE, resolve(env).ownerOf(DataDomain.SUPPLY), "derived for the legacy flow");
			assertFalse(NodeOwnership.isSupplyFromHeadOffice(env));
			for (Class<?> bean : STORE_BEANS) {
				assertFalse(registered(env, bean), bean.getSimpleName());
			}
			assertTrue(registered(env, FranchiseSupplyReceptionService.class), "legacy reception untouched");
		}
	}

	@Test
	@DisplayName("The startup stops on an explicit ownership.supply=HEAD_OFFICE without the URL, with a franchise flag, with an ERP,"
			+ " or without ownership.catalogue=HEAD_OFFICE; a bad supply-push interval is refused with the URL only")
	void startupRefusals() {
		assertStartsWith("Missing value for property headoffice.url: required when ownership.supply is HEAD_OFFICE",
				standalone(new MockEnvironment()).withProperty("ownership.catalogue", "LOCAL")
						.withProperty("ownership.supply", "HEAD_OFFICE"));
		assertStartsWith("Invalid combination: ownership.supply=HEAD_OFFICE with franchise.customer=true",
				standalone(link()).withProperty("franchise.customer", "true").withProperty("ownership.supply",
						"HEAD_OFFICE"));
		assertStartsWith("Invalid combination: ownership.supply=HEAD_OFFICE with franchise.admin=true",
				standalone(link()).withProperty("franchise.admin", "true").withProperty("ownership.supply", "HEAD_OFFICE"));
		assertStartsWith("Invalid combination: ownership.supply=HEAD_OFFICE with application.standalone=false",
				link().withProperty("ownership.supply", "HEAD_OFFICE"));
		assertStartsWith("Invalid combination: ownership.supply=HEAD_OFFICE without ownership.catalogue=HEAD_OFFICE",
				standalone(link()).withProperty("ownership.supply", "HEAD_OFFICE"));
		assertStartsWith("Invalid combination: ownership.supply=HEAD_OFFICE without ownership.catalogue=HEAD_OFFICE",
				standalone(link()).withProperty("ownership.catalogue", "LOCAL").withProperty("ownership.supply",
						"HEAD_OFFICE"));
		assertStartsWith("Invalid value '0' for property headoffice.supply-push.interval-seconds",
				supplied().withProperty("headoffice.supply-push.interval-seconds", "0"));
		resolve(standalone(new MockEnvironment()).withProperty("headoffice.supply-push.interval-seconds", "0"));
		resolve(supplied().withProperty("headoffice.supply-push.interval-seconds", "30"));
	}

	private static void assertStartsWith(String start, MockEnvironment env) {
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> resolve(env));
		assertTrue(e.getMessage().startsWith(start), e.getMessage());
	}

	@Test
	@DisplayName("Head office side: the receiver of the confirmations only on a head office without an ERP")
	void headOfficeReceiver() {
		assertTrue(registered(standalone(new MockEnvironment()).withProperty("node.type", "HEAD_OFFICE"),
				HeadOfficeSupplyAPI.class));
		assertFalse(registered(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"), HeadOfficeSupplyAPI.class));
		assertFalse(registered(supplied(), HeadOfficeSupplyAPI.class));
		assertTrue(HeadOfficeSupplyAPI.class.isAnnotationPresent(ConditionalOnHeadOfficeStandalone.class));
	}
}
