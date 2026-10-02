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

import com.digithink.zsretail.headoffice.controller.StoreAPI;
import com.digithink.zsretail.headoffice.service.StoreService;

/**
 * Head office plan, task 1.2: the stores beans exist only on a head office. node.type is read as at startup
 * (trimmed, case-insensitive). The registration check uses a bare bean registry: each bean definition is evaluated
 * against its condition, nothing is created and no context is started.
 */
class OnHeadOfficeConditionTest {

	private static final Class<?>[] STORE_BEANS = { StoreService.class, StoreAPI.class };

	/** True when the class gets a bean definition with this environment. */
	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	@Test
	@DisplayName("Head office: StoreService and StoreAPI are registered, whatever the case and spacing of node.type")
	void registeredOnHeadOffice() {
		for (String value : new String[] { "HEAD_OFFICE", " head_office ", "Head_Office" }) {
			MockEnvironment env = new MockEnvironment().withProperty("node.type", value);
			for (Class<?> bean : STORE_BEANS) {
				assertTrue(registered(env, bean), bean.getSimpleName() + " with '" + value + "'");
			}
		}
	}

	@Test
	@DisplayName("Store (node.type absent or STORE): nothing is registered, so the endpoints do not exist")
	void absentOnStore() {
		for (MockEnvironment env : new MockEnvironment[] { new MockEnvironment(),
				new MockEnvironment().withProperty("node.type", "STORE") }) {
			for (Class<?> bean : STORE_BEANS) {
				assertFalse(registered(env, bean), bean.getSimpleName());
			}
		}
	}

	@Test
	@DisplayName("An unknown node.type fails like at startup, naming the key")
	void unknownValueFails() {
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> registered(new MockEnvironment().withProperty("node.type", "SHOP"), StoreService.class));
		assertTrue(e.getMessage().contains("node.type"), e.getMessage());
	}

	@Test
	@DisplayName("The annotation carries the condition; both stores beans carry the annotation")
	void annotations() {
		assertEquals(OnHeadOfficeCondition.class,
				ConditionalOnHeadOffice.class.getAnnotation(Conditional.class).value()[0]);
		for (Class<?> bean : STORE_BEANS) {
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOffice.class), bean.getSimpleName());
		}
	}
}
