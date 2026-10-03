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

import com.digithink.zsretail.holink.scheduler.CopiesDownJob;
import com.digithink.zsretail.holink.service.CopiesDownPuller;
import com.digithink.zsretail.holink.service.PromotionDownHandler;
import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Head office plan, task 3.1: the pull beans exist only on a store with headoffice.url and at least one domain owned by
 * the head office; a domain's handler only when that domain is owned by the head office. A store without headoffice.url,
 * or with every domain local, gets no new bean. Bare bean registry: nothing is created and no context is started.
 */
class OnHeadOfficePullConditionTest {

	private static final Class<?>[] PULL_BEANS = { CopiesDownPuller.class, CopiesDownJob.class };

	private static MockEnvironment link() {
		return new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_abcde");
	}

	private static MockEnvironment standalone(MockEnvironment env) {
		return env.withProperty("application.standalone", "true");
	}

	static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	@Test
	@DisplayName("No pull: no URL, or every domain local (standalone and ERP flags, promotions LOCAL), or a head office")
	void withoutPull() {
		assertFalse(NodeOwnership.isHeadOfficePullSet(new MockEnvironment()));
		assertFalse(NodeOwnership.isHeadOfficePullSet(standalone(new MockEnvironment())));
		assertFalse(NodeOwnership.isHeadOfficePullSet(standalone(link())), "standalone: every domain local");
		assertFalse(NodeOwnership.isHeadOfficePullSet(link()), "ERP flags: ERP and local");
		assertFalse(NodeOwnership.isHeadOfficePullSet(standalone(link()).withProperty("ownership.promotions", "LOCAL")));
		assertFalse(NodeOwnership.isHeadOfficePullSet(standalone(link()).withProperty("sales.upstream", "HEAD_OFFICE")),
				"sales copies up only");
		assertFalse(NodeOwnership.isHeadOfficePullSet(
				standalone(new MockEnvironment()).withProperty("node.type", "HEAD_OFFICE")));
		assertFalse(NodeOwnership.isOwnedByHeadOffice(standalone(link()), DataDomain.PROMOTIONS));
		assertFalse(NodeOwnership.isOwnedByHeadOffice(new MockEnvironment(), DataDomain.PROMOTIONS));
	}

	@Test
	@DisplayName("Pull: URL and promotions owned by the head office (standalone or ERP flags, any case)")
	void withPull() {
		assertTrue(NodeOwnership.isHeadOfficePullSet(standalone(link()).withProperty("ownership.promotions", "HEAD_OFFICE")));
		assertTrue(NodeOwnership.isHeadOfficePullSet(link().withProperty("ownership.promotions", " head_office ")));
		assertTrue(NodeOwnership.isOwnedByHeadOffice(link().withProperty("ownership.promotions", "HEAD_OFFICE"),
				DataDomain.PROMOTIONS));
		assertFalse(NodeOwnership.isOwnedByHeadOffice(link().withProperty("ownership.promotions", "HEAD_OFFICE"),
				DataDomain.LOYALTY), "only the domain owned");
	}

	@Test
	@DisplayName("The pull beans are registered only when the condition is true")
	void beans() {
		MockEnvironment[] off = { new MockEnvironment(), standalone(link()), link(),
				standalone(link()).withProperty("ownership.promotions", "LOCAL"),
				standalone(link()).withProperty("sales.upstream", "HEAD_OFFICE") };
		for (MockEnvironment env : off) {
			for (Class<?> bean : PULL_BEANS) {
				assertFalse(registered(env, bean), bean.getSimpleName());
			}
		}
		MockEnvironment on = standalone(link()).withProperty("ownership.promotions", "HEAD_OFFICE");
		for (Class<?> bean : PULL_BEANS) {
			assertTrue(registered(on, bean), bean.getSimpleName());
		}
		assertTrue(registered(on, PromotionDownHandler.class), "the promotions handler with promotions owned");
		for (MockEnvironment env : off) {
			assertFalse(registered(env, PromotionDownHandler.class));
		}
		assertFalse(registered(link().withProperty("ownership.catalogue", "HEAD_OFFICE"), PromotionDownHandler.class),
				"another domain owned: the job exists, not the promotions handler");
		assertTrue(registered(link().withProperty("ownership.catalogue", "HEAD_OFFICE"), CopiesDownJob.class));
		assertEquals(DataDomain.PROMOTIONS,
				PromotionDownHandler.class.getAnnotation(ConditionalOnHeadOfficeOwned.class).value());
	}

	@Test
	@DisplayName("Each pull bean carries the annotation, which uses the pull condition")
	void annotations() {
		assertEquals(OnHeadOfficePullCondition.class,
				ConditionalOnHeadOfficePull.class.getAnnotation(Conditional.class).value()[0]);
		assertEquals(OnHeadOfficeOwnedCondition.class,
				ConditionalOnHeadOfficeOwned.class.getAnnotation(Conditional.class).value()[0]);
		for (Class<?> bean : PULL_BEANS) {
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficePull.class), bean.getSimpleName());
		}
	}
}
