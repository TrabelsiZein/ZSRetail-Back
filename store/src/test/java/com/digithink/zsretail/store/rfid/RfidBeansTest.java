package com.digithink.zsretail.store.rfid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.repository.config.AnnotationRepositoryConfigurationSource;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.support.Questions;
import com.digithink.zsretail.store.rfid.reader.HopelandRfidReader;
import com.digithink.zsretail.store.rfid.repository.PosMachineRepository;
import com.digithink.zsretail.store.rfid.repository.RfidReaderRepository;
import com.digithink.zsretail.store.rfid.service.PosMachineService;
import com.digithink.zsretail.store.rfid.service.RfidReadService;
import com.digithink.zsretail.store.rfid.service.RfidReaderService;

/**
 * RFID steps 3 to 5: with rfid.enabled false (or absent) no RFID bean exists, the repositories included: Spring Data's
 * repository scanner (the one the application runs) evaluates their condition like the component scan does. With
 * rfid.enabled=true they all exist. Plain bean registry and Spring Data's configuration source, no Spring context.
 */
class RfidBeansTest {

	/** The RFID beans (InstallationBeansProofTest records them in every configuration). */
	static final List<Class<?>> SERVICES = Arrays.asList(RfidReaderService.class, PosMachineService.class,
			RfidReadService.class, HopelandRfidReader.class);

	private static MockEnvironment store(String rfid) {
		MockEnvironment env = new MockEnvironment();
		if (rfid != null) {
			env.setProperty("rfid.enabled", rfid);
		}
		return env;
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	/** Only its annotation is read: the RFID package, scanned as the application's JPA repositories are. */
	@EnableJpaRepositories(basePackages = "com.digithink.zsretail.store.rfid")
	static class RfidRepositoriesScan {
	}

	/** The repositories Spring Data finds in the RFID package for this environment. */
	private static Set<String> repositories(MockEnvironment env) {
		DefaultResourceLoader loader = new DefaultResourceLoader();
		AnnotationRepositoryConfigurationSource source = new AnnotationRepositoryConfigurationSource(
				AnnotationMetadata.introspect(RfidRepositoriesScan.class), EnableJpaRepositories.class, loader, env,
				new DefaultListableBeanFactory());
		Set<String> names = new TreeSet<>();
		for (BeanDefinition candidate : source.getCandidates(loader)) {
			names.add(candidate.getBeanClassName());
		}
		return names;
	}

	@Test
	@DisplayName("Off (absent, false): no RFID service, no RFID repository")
	void off() {
		for (String value : Arrays.asList(null, "false")) {
			MockEnvironment env = store(value);
			for (Class<?> service : SERVICES) {
				assertTrue(!registered(env, service), service.getSimpleName() + " with " + value);
			}
			assertEquals(Collections.emptySet(), repositories(env), "repositories with " + value);
		}
	}

	@Test
	@DisplayName("On: the services and the two repositories exist")
	void on() {
		MockEnvironment env = store("true");
		for (Class<?> service : SERVICES) {
			assertTrue(registered(env, service), service.getSimpleName());
		}
		assertEquals(new TreeSet<>(Arrays.asList(PosMachineRepository.class.getName(), RfidReaderRepository.class.getName())),
				repositories(env));
	}

	@Test
	@DisplayName("Each service and repository asks Installation.RFID")
	void question() {
		for (Class<?> type : Arrays.asList(RfidReaderService.class, PosMachineService.class, RfidReadService.class,
				HopelandRfidReader.class, RfidReaderRepository.class,
				PosMachineRepository.class)) {
			assertTrue(Questions.asks(type, Installation.RFID), type.getSimpleName());
		}
	}
}
