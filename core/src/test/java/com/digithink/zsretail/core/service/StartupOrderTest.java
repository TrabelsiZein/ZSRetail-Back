package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Collections;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.AnnotatedGenericBeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotationBeanNameGenerator;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.erp.engine.JdbcCatalogueState;

/**
 * 3.2.0: the version guard runs before the beans that write at the start, on both applications: the data initializer
 * (it runs PaymentMethodsUpgrade) depends on it by name; the catalogue engine's state table asks for it first, a soft
 * dependency (a test context without the guard keeps working). Proved running on a database of 3.1.2 (refused, nothing
 * of the initializer written) when the step was built.
 */
class StartupOrderTest {

	@Test
	@DisplayName("ZZDataInitializer depends on the bean of AppVersionGuard, by its real name")
	void initializerAfterTheGuard() {
		assertNotNull(AppVersionGuard.class.getAnnotation(Component.class));
		String guard = new AnnotationBeanNameGenerator().generateBeanName(
				new AnnotatedGenericBeanDefinition(AppVersionGuard.class), new DefaultListableBeanFactory());
		assertEquals("appVersionGuard", guard);
		DependsOn dependsOn = ZZDataInitializer.class.getAnnotation(DependsOn.class);
		assertNotNull(dependsOn);
		assertArrayEquals(new String[] { guard }, dependsOn.value());
	}

	@Test
	@DisplayName("JdbcCatalogueState asks for the guard before its first statement")
	@SuppressWarnings("unchecked")
	void catalogueStateAfterTheGuard() throws Exception {
		JdbcTemplate jdbc = mock(JdbcTemplate.class);
		when(jdbc.queryForList(anyString(), eq(String.class))).thenReturn(Collections.emptyList());
		ObjectProvider<AppVersionGuard> guard = mock(ObjectProvider.class);
		JdbcCatalogueState state = new JdbcCatalogueState(jdbc, guard);
		Method prepare = JdbcCatalogueState.class.getDeclaredMethod("prepareTable");
		prepare.setAccessible(true);
		prepare.invoke(state);
		InOrder order = inOrder(guard, jdbc);
		order.verify(guard).getIfAvailable();
		order.verify(jdbc).queryForList(anyString(), eq(String.class));
	}
}
