package com.digithink.zsretail.erp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.emtop.connector.DynamicsNavConnector;
import com.digithink.zsretail.connector.happyness.connector.NavPosPagesConnector;
import com.digithink.zsretail.core.config.ErpConnectorEnvironmentPostProcessor;
import com.digithink.zsretail.core.config.InstallationTypeEnvironmentPostProcessor;
import com.digithink.zsretail.core.config.MachineFileEnvironmentPostProcessor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpAbilities;
import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;
import com.digithink.zsretail.erp.api.NoOpErpConnector;
import com.digithink.zsretail.support.Starts;

/**
 * Code cleanup step 7, proof: a connector declares itself. A third connector, "thirdco", exists only here: its
 * descriptor ({@link Descriptor}) and a bean of its own ({@link ThirdConnector}). Its descriptor is registered in
 * src/test/resources/fake-connector/META-INF/spring.factories, a folder added to a class loader for this test only (no
 * other test sees a third connector). With it, and nothing changed in erp-api or core, erp.connector=thirdco is accepted,
 * its abilities are known and its bean is chosen, the others not; without it the same file is refused.
 */
class ThirdConnectorTest {

	static final String NAME = "thirdco";

	/** The third connector as it declares itself: no old keys, one ability, a summary address. */
	public static class Descriptor implements ErpConnectorDescriptor {

		@Override
		public String name() {
			return NAME;
		}

		@Override
		public Set<ErpAbility> abilities() {
			return EnumSet.of(ErpAbility.SUPPLY_INVOICES);
		}

		@Override
		public String summaryLabel() {
			return "ERP";
		}

		@Override
		public String summaryKey() {
			return "erp.thirdco.base-url";
		}
	}

	/** A bean of the third connector, chosen as the two connectors choose theirs. */
	@Component
	@ConditionalOnErpConnector(NAME)
	public static class ThirdConnector {
	}

	@TempDir
	Path folder;

	/** A start the way Spring Boot runs it, the outside file holding these lines (a store). */
	private StandardEnvironment start(String... outsideLines) throws Exception {
		Path file = folder.resolve("machine.properties");
		Files.write(file, Arrays.asList(outsideLines));
		Map<String, Object> systemProperties = Collections.singletonMap(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY,
				file.toString());
		StandardEnvironment env = Starts.environment("store", systemProperties);
		SpringApplication application = new SpringApplication();
		new MachineFileEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ConfigFileApplicationListener().postProcessEnvironment(env, application);
		new InstallationTypeEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ErpConnectorEnvironmentPostProcessor().postProcessEnvironment(env, application);
		return env;
	}

	private static boolean registered(StandardEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	/** Runs the call with the fake connector's folder added to the thread's class loader. */
	private static <T> T withThirdConnector(Callable<T> call) throws Exception {
		URL folder = ThirdConnectorTest.class.getResource("/fake-connector/");
		Thread thread = Thread.currentThread();
		ClassLoader before = thread.getContextClassLoader();
		try (URLClassLoader loader = new URLClassLoader(new URL[] { folder }, ThirdConnectorTest.class.getClassLoader())) {
			thread.setContextClassLoader(loader);
			return call.call();
		} finally {
			thread.setContextClassLoader(before);
		}
	}

	@Test
	@DisplayName("Without its descriptor, erp.connector=thirdco is refused, naming the connectors found")
	void unknownWithoutDescriptor() {
		assertFalse(ErpConnectors.names().contains(NAME));
		IllegalStateException refused = assertThrows(IllegalStateException.class, () -> start("erp.connector=thirdco"));
		assertTrue(refused.getMessage().startsWith("Invalid value 'thirdco' for property erp.connector: none | emtop |"
				+ " happyness."),
				refused.getMessage());
	}

	@Test
	@DisplayName("Its descriptor alone: its name accepted, its abilities known, its bean chosen, the others not")
	void descriptorAlone() throws Exception {
		withThirdConnector(() -> {
			assertEquals(Arrays.asList("none", "emtop", "happyness", NAME), ErpConnectors.names());
			StandardEnvironment env = start("erp.connector=thirdco", "erp.thirdco.base-url=http://erp.test");
			assertEquals(NAME, ErpConnectors.inEffect(env));
			assertEquals(EnumSet.of(ErpAbility.SUPPLY_INVOICES), ErpAbilities.of(NAME));
			assertTrue(ErpAbilities.has(env, ErpAbility.SUPPLY_INVOICES));
			assertFalse(ErpAbilities.has(env, ErpAbility.HEAD_OFFICE_STOCK_POINTS));
			assertEquals(Arrays.asList("happyness", NAME), ErpAbilities.connectorsWith(ErpAbility.SUPPLY_INVOICES));
			assertEquals("ERP http://erp.test", ErpConnectorEnvironmentPostProcessor.summaryAddress(env));
			assertTrue(registered(env, ThirdConnector.class));
			assertFalse(registered(env, NoOpErpConnector.class));
			assertFalse(registered(env, DynamicsNavConnector.class));
			assertFalse(registered(env, NavPosPagesConnector.class));
			// The two others are chosen as before, beside it
			StandardEnvironment emtop = start("erp.connector=emtop");
			assertEquals("emtop", ErpConnectors.inEffect(emtop));
			assertFalse(registered(emtop, ThirdConnector.class));
			assertTrue(registered(emtop, DynamicsNavConnector.class));
			return null;
		});
	}
}
