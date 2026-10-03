package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.annotation.Order;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.headoffice.controller.HeadOfficeHeartbeatAPI;
import com.digithink.zsretail.headoffice.controller.HeadOfficePingAPI;
import com.digithink.zsretail.headoffice.controller.HeadOfficeSalesAPI;
import com.digithink.zsretail.headoffice.controller.StoreAPI;
import com.digithink.zsretail.headoffice.security.HeadOfficeApiSecurityConfig;
import com.digithink.zsretail.headoffice.security.StoreApiKeyFilter;
import com.digithink.zsretail.headoffice.service.SalesCopyReceiver;
import com.digithink.zsretail.headoffice.service.StoreService;

/**
 * Head office plan, tasks 1.2 to 1.4: the stores beans, the /ho/** store key filter, GET /ho/ping and
 * POST /ho/heartbeat exist only on a head office, and so do the sales copy receiver and POST /ho/sales/* (task 2.3);
 * the /ho/** security chain exists on both. node.type is read as at startup (trimmed,
 * case-insensitive). The registration check uses a bare bean registry: each bean definition is evaluated against its
 * condition, nothing is created and no context is started.
 */
class OnHeadOfficeConditionTest {

	private static final Class<?>[] STORE_BEANS = { StoreService.class, StoreAPI.class, StoreApiKeyFilter.class,
			HeadOfficePingAPI.class, HeadOfficeHeartbeatAPI.class, SalesCopyReceiver.class, HeadOfficeSalesAPI.class };

	/** True when the class gets a bean definition with this environment. */
	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	@Test
	@DisplayName("Head office: the head office beans are registered, whatever the case and spacing of node.type")
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
	@DisplayName("The annotation carries the condition; every head office bean carries the annotation")
	void annotations() {
		assertEquals(OnHeadOfficeCondition.class,
				ConditionalOnHeadOffice.class.getAnnotation(Conditional.class).value()[0]);
		for (Class<?> bean : STORE_BEANS) {
			assertTrue(bean.isAnnotationPresent(ConditionalOnHeadOffice.class), bean.getSimpleName());
		}
	}

	@Test
	@DisplayName("The /ho/** chain exists on a store too (so /ho/** answers 401 there); its filter is optional and the disabled servlet registration is head office only")
	void hoChainStartsOnStore() throws Exception {
		for (MockEnvironment env : new MockEnvironment[] { new MockEnvironment(),
				new MockEnvironment().withProperty("node.type", "HEAD_OFFICE") }) {
			assertTrue(registered(env, HeadOfficeApiSecurityConfig.class));
		}
		assertFalse(HeadOfficeApiSecurityConfig.class.getDeclaredField("storeApiKeyFilter").getAnnotation(Autowired.class)
				.required(), "the filter must be optional");
		assertTrue(HeadOfficeApiSecurityConfig.class
				.getDeclaredMethod("storeApiKeyFilterRegistration", StoreApiKeyFilter.class)
				.isAnnotationPresent(ConditionalOnHeadOffice.class));
		assertEquals(0, HeadOfficeApiSecurityConfig.class.getAnnotation(Order.class).value(),
				"the /ho/** chain must come before the main chain (order 1)");
	}
}
