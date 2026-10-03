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

import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.scheduler.HeadOfficeHeartbeatScheduler;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;

/**
 * Head office plan, task 1.4: the store-side beans of the head office link (client, status, heartbeat) exist only
 * when headoffice.url is present and not blank. Without it the existing profiles behave as before. Uses a bare bean
 * registry: each bean definition is evaluated against its condition, nothing is created and no context is started.
 */
class OnHeadOfficeLinkConditionTest {

	private static final Class<?>[] LINK_BEANS = { HeadOfficeClient.class, HeadOfficeLinkStatus.class,
			HeadOfficeHeartbeatScheduler.class };

	/** True when the class gets a bean definition with this environment. */
	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	@Test
	@DisplayName("No URL, empty URL or blank URL: false; a URL (with or without trailing slash): true")
	void condition() {
		assertFalse(NodeOwnership.isHeadOfficeLinkSet(new MockEnvironment()));
		assertFalse(NodeOwnership.isHeadOfficeLinkSet(new MockEnvironment().withProperty("headoffice.url", "")));
		assertFalse(NodeOwnership.isHeadOfficeLinkSet(new MockEnvironment().withProperty("headoffice.url", "   ")));
		assertTrue(NodeOwnership.isHeadOfficeLinkSet(
				new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")));
		assertTrue(NodeOwnership.isHeadOfficeLinkSet(
				new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api/")));
	}

	@Test
	@DisplayName("Without a URL (absent or blank) none of the link beans is registered, even with a key set")
	void absentWithoutUrl() {
		for (MockEnvironment env : new MockEnvironment[] { new MockEnvironment(),
				new MockEnvironment().withProperty("headoffice.url", " ").withProperty("headoffice.api-key", "k") }) {
			for (Class<?> bean : LINK_BEANS) {
				assertFalse(registered(env, bean), bean.getSimpleName());
			}
		}
	}

	@Test
	@DisplayName("With a URL every link bean is registered")
	void registeredWithUrl() {
		MockEnvironment env = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api");
		for (Class<?> bean : LINK_BEANS) {
			assertTrue(registered(env, bean), bean.getSimpleName());
		}
	}

	@Test
	@DisplayName("The annotation carries the condition; every link bean carries the annotation")
	void annotations() {
		assertEquals(OnHeadOfficeLinkCondition.class,
				ConditionalOnHeadOfficeLink.class.getAnnotation(Conditional.class).value()[0]);
		for (Class<?> bean : LINK_BEANS) {
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOfficeLink.class), bean.getSimpleName());
		}
	}
}
