package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Conditional;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.holink.repository.JpaSalesDocumentSource;
import com.digithink.zsretail.holink.scheduler.SalesPushJob;
import com.digithink.zsretail.holink.service.SalesCopyFinder;
import com.digithink.zsretail.holink.service.SalesPushService;
import com.digithink.zsretail.holink.service.SalesPushSettings;

/**
 * Head office plan, tasks 2.1 and 2.4 (decision 4): the sales copy beans (search, push job) exist only when headoffice.url is set and the sales
 * upstreams include HEAD_OFFICE (sales.upstream, or derived from the mode flags). A linked store without that upstream
 * keeps the heartbeat only. Bare bean registry: nothing is created and no context is started.
 */
class OnHeadOfficeSalesPushConditionTest {

	private static final Class<?>[] PUSH_BEANS = { SalesPushSettings.class, SalesCopyFinder.class,
			JpaSalesDocumentSource.class, SalesPushService.class, SalesPushJob.class };

	private static MockEnvironment link() {
		return new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
	}

	private static MockEnvironment standalone(MockEnvironment env) {
		return env.withProperty("application.standalone", "true");
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	@Test
	@DisplayName("No head office upstream: false (no URL; URL without sales.upstream on standalone and ERP flags; ERP or empty)")
	void withoutHeadOfficeUpstream() {
		assertFalse(NodeOwnership.isHeadOfficeSalesPushSet(new MockEnvironment()));
		assertFalse(NodeOwnership.isHeadOfficeSalesPushSet(
				standalone(new MockEnvironment()).withProperty("sales.upstream", "HEAD_OFFICE")), "no URL");
		assertFalse(NodeOwnership.isHeadOfficeSalesPushSet(standalone(link())), "standalone: sales go nowhere");
		assertFalse(NodeOwnership.isHeadOfficeSalesPushSet(link()), "ERP flags: sales go to the ERP");
		assertFalse(NodeOwnership.isHeadOfficeSalesPushSet(standalone(link()).withProperty("sales.upstream", "ERP")));
		assertFalse(NodeOwnership.isHeadOfficeSalesPushSet(link().withProperty("sales.upstream", "")));
		assertFalse(NodeOwnership.isHeadOfficeSalesPushSet(standalone(link()).withProperty("franchise.admin", "true")));
	}

	@Test
	@DisplayName("Head office upstream with the URL: true (explicit on standalone or ERP flags, any case; derived on franchise customer)")
	void withHeadOfficeUpstream() {
		assertTrue(NodeOwnership.isHeadOfficeSalesPushSet(standalone(link()).withProperty("sales.upstream", "HEAD_OFFICE")));
		assertTrue(NodeOwnership.isHeadOfficeSalesPushSet(link().withProperty("sales.upstream", " erp , head_office ")));
		assertTrue(NodeOwnership.isHeadOfficeSalesPushSet(
				standalone(link()).withProperty("franchise.customer", " true ")), "franchise customer: derived");
	}

	@Test
	@DisplayName("The sales copy beans are registered only when the condition is true")
	void beans() {
		MockEnvironment[] off = { new MockEnvironment(), standalone(link()), link(),
				standalone(new MockEnvironment()).withProperty("sales.upstream", "HEAD_OFFICE") };
		for (MockEnvironment env : off) {
			for (Class<?> bean : PUSH_BEANS) {
				assertFalse(registered(env, bean), bean.getSimpleName());
			}
		}
		MockEnvironment on = standalone(link()).withProperty("sales.upstream", "HEAD_OFFICE");
		for (Class<?> bean : PUSH_BEANS) {
			assertTrue(registered(on, bean), bean.getSimpleName());
		}
	}

	@Test
	@DisplayName("The annotation carries the condition; every sales copy bean carries the annotation")
	void annotations() {
		assertEquals(OnHeadOfficeSalesPushCondition.class,
				ConditionalOnHeadOfficeSalesPush.class.getAnnotation(Conditional.class).value()[0]);
		for (Class<?> bean : PUSH_BEANS) {
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeSalesPush.class), bean.getSimpleName());
		}
	}
}
