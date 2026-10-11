package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.env.Environment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.type.AnnotationMetadata;
import org.springframework.core.type.MethodMetadata;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.ProjectSortingGuardTest;
import com.digithink.zsretail.support.ClassRenames;
import com.digithink.zsretail.support.Installations;

/**
 * Code cleanup step 4, proof: the beans the installation questions decide (the classes and @Bean methods that carry an
 * annotation whose condition is a class of config, apart from the two ERP ones), in every file of deploy/ and configs/,
 * the six shapes of task 9.3 and a few head office shapes, compared with the record made on the code before the change
 * (src/test/resources/config/installation-beans.txt). Written so that it runs unchanged before and after: it names no
 * annotation. -Dinstallation.proof.write=true writes the record again (only on the code before the change). Code cleanup
 * step 6: the beans are named as before the moves (support/ClassRenames), so the record stays as it was.
 */
class InstallationBeansProofTest {

	static final Path RECORD = Paths.get("src", "test", "resources", "config", "installation-beans.txt");
	static final List<String> ERP_CONDITIONS = Arrays.asList("OnErpAbilityCondition", "OnErpConnectorCondition");

	/** Every class and @Bean method these questions decide, by name: a lost or new annotation shows here. */
	static final int EXPECTED_DECIDED = 82;

	/** True for an annotation whose condition is an installation question (a class of config, not an ERP one). */
	static boolean isQuestion(Annotation annotation) {
		Conditional conditional = annotation.annotationType().getAnnotation(Conditional.class);
		if (conditional == null) {
			return false;
		}
		for (Class<? extends Condition> condition : conditional.value()) {
			if (condition.getPackage().getName().equals("com.digithink.zsretail.core.config")
					&& !ERP_CONDITIONS.contains(condition.getSimpleName())) {
				return true;
			}
		}
		return false;
	}

	static boolean decided(Annotation[] annotations) {
		return Arrays.stream(annotations).anyMatch(InstallationBeansProofTest::isQuestion);
	}

	/** The classes (by name) and @Bean methods (class#method) the questions decide, sorted. */
	static Map<String, Object> decidedBeans() throws ClassNotFoundException {
		AnnotationTypeFilter components = new AnnotationTypeFilter(Component.class);
		// Every component, whatever its conditions (the scanner would evaluate them on its own environment)
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
				return components.match(reader, getMetadataReaderFactory());
			}
		};
		Map<String, Object> beans = new java.util.TreeMap<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("com.digithink.zsretail")) {
			Class<?> type = Class.forName(candidate.getBeanClassName());
			if (decided(type.getAnnotations())) {
				beans.put(ClassRenames.oldName(type.getName()), type);
			}
			for (Method method : type.getDeclaredMethods()) {
				if (method.isAnnotationPresent(Bean.class) && decided(method.getAnnotations())) {
					beans.put(ClassRenames.oldName(type.getName()) + "#" + method.getName(), method);
				}
			}
		}
		return beans;
	}

	private static boolean registered(MockEnvironment env, Class<?> beanClass) {
		DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
		new AnnotatedBeanDefinitionReader(registry, env).register(beanClass);
		return registry.getBeanNamesForType(beanClass, true, false).length == 1;
	}

	/** A @Bean method: every condition on it, evaluated as Spring evaluates them (all must match). */
	private static boolean registered(MockEnvironment env, Method method) {
		MethodMetadata metadata = AnnotationMetadata.introspect(method.getDeclaringClass()).getAnnotatedMethods(Bean.class.getName()).stream()
				.filter(candidate -> candidate.getMethodName().equals(method.getName())).findFirst().get();
		ConditionContext context = new ConditionContext() {
			@Override
			public BeanDefinitionRegistry getRegistry() {
				return new DefaultListableBeanFactory();
			}

			@Override
			public ConfigurableListableBeanFactory getBeanFactory() {
				return new DefaultListableBeanFactory();
			}

			@Override
			public Environment getEnvironment() {
				return env;
			}

			@Override
			public ResourceLoader getResourceLoader() {
				return new DefaultResourceLoader();
			}

			@Override
			public ClassLoader getClassLoader() {
				return InstallationBeansProofTest.class.getClassLoader();
			}
		};
		for (Annotation annotation : method.getAnnotations()) {
			Conditional conditional = annotation.annotationType().getAnnotation(Conditional.class);
			if (conditional != null) {
				for (Class<? extends Condition> condition : conditional.value()) {
					if (!BeanUtils.instantiateClass(condition).matches(context, metadata)) {
						return false;
					}
				}
			}
		}
		return true;
	}

	private static MockEnvironment headOffice(String... lines) {
		MockEnvironment env = Installations.type("headoffice");
		for (String line : lines) {
			int eq = line.indexOf('=');
			env.setProperty(line.substring(0, eq), line.substring(eq + 1));
		}
		return env;
	}

	/**
	 * A file of configs/ as committed (git show HEAD:configs/...), over its type file and application.properties like
	 * Installations.config: a local change of the file (configs/local is edited on this machine) does not change the
	 * record. The file as it is on disk when git cannot answer.
	 */
	static MockEnvironment committedConfig(String file) {
		java.util.Properties committed = new java.util.Properties();
		try {
			Process git = new ProcessBuilder("git", "show", "HEAD:configs/" + file).start();
			try (java.io.InputStream in = git.getInputStream()) {
				committed.load(new java.io.InputStreamReader(in, StandardCharsets.ISO_8859_1));
			}
			if (git.waitFor() != 0 || committed.isEmpty()) {
				return Installations.config(file);
			}
		} catch (IOException e) {
			return Installations.config(file);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Installations.config(file);
		}
		String type = committed.getProperty("spring.profiles.active", Installations.DEFAULT_TYPE);
		MockEnvironment env = Installations.type(type.trim());
		committed.stringPropertyNames().forEach(key -> env.setProperty(key, committed.getProperty(key)));
		return env;
	}

	/** Every configuration of the repository, the six shapes, and head office shapes no file has. */
	static Map<String, MockEnvironment> configurations() {
		Map<String, MockEnvironment> environments = new LinkedHashMap<>();
		Installations.machineFiles().forEach(file -> environments.put("deploy/" + file, Installations.machine(file)));
		Installations.configFiles().forEach(file -> environments.put("configs/" + file, committedConfig(file)));
		Installations.variants().forEach(variant -> environments.put("shape " + variant, Installations.preset(variant)));
		environments.put("head office, stock off", headOffice("headoffice.stock.enabled=false"));
		environments.put("head office, catalogue only from the ERP", headOffice("ownership.catalogue=ERP"));
		environments.put("head office, catalogue only from the ERP, its invoices", headOffice("ownership.catalogue=ERP",
				"erp.navpospages.enabled=true", "headoffice.supply.source=ERP"));
		environments.put("head office, catalogue only from the ERP, stock off", headOffice("ownership.catalogue=ERP",
				"headoffice.stock.enabled=false"));
		return environments;
	}

	/**
	 * Code cleanup step 6.5: whether a bean is in the application of a type (each configuration is checked in its own):
	 * core, erp-api and the connectors are in both, store's classes in the store application, the head office's in the
	 * head office application.
	 */
	static boolean inApplication(Object bean, String type) {
		Class<?> declaring = bean instanceof Method ? ((Method) bean).getDeclaringClass() : (Class<?>) bean;
		String project = ProjectSortingGuardTest.projectOf(declaring.getName().substring("com.digithink.zsretail.".length()));
		switch (project) {
		case "store":
			return "store".equals(type);
		case "headoffice":
			return "headoffice".equals(type);
		default:
			return true;
		}
	}

	/** One line per configuration: its name, then the beans that exist in its application, sorted by name. */
	static List<String> record() throws ClassNotFoundException {
		Map<String, Object> beans = decidedBeans();
		List<String> lines = new ArrayList<>();
		lines.add("decided " + beans.size() + ": " + String.join(" ", beans.keySet()));
		for (Map.Entry<String, MockEnvironment> configuration : configurations().entrySet()) {
			TreeSet<String> present = new TreeSet<>();
			String type = configuration.getValue().getProperty("spring.profiles.active");
			for (Map.Entry<String, Object> bean : beans.entrySet()) {
				if (!inApplication(bean.getValue(), type)) {
					continue;
				}
				boolean exists = bean.getValue() instanceof Class ? registered(configuration.getValue(), (Class<?>) bean.getValue())
						: registered(configuration.getValue(), (Method) bean.getValue());
				if (exists) {
					present.add(bean.getKey());
				}
			}
			lines.add(configuration.getKey() + " | " + present.size() + ": " + String.join(" ", present));
		}
		return lines;
	}

	@Test
	@DisplayName("The beans the installation questions decide, in every configuration, equal the record made before the change")
	void sameBeansAsRecorded() throws Exception {
		List<String> now = record();
		if (Boolean.getBoolean("installation.proof.write")) {
			Files.createDirectories(RECORD.getParent());
			Files.write(RECORD, now, StandardCharsets.UTF_8);
		}
		List<String> recorded = Files.readAllLines(RECORD, StandardCharsets.UTF_8);
		assertEquals(EXPECTED_DECIDED, decidedBeans().size(), now.get(0));
		assertTrue(recorded.size() > 20, "configurations recorded: " + recorded.size());
		for (int i = 0; i < Math.max(now.size(), recorded.size()); i++) {
			assertEquals(i < recorded.size() ? recorded.get(i) : "(none)", i < now.size() ? now.get(i) : "(none)",
					"line " + (i + 1));
		}
	}
}
