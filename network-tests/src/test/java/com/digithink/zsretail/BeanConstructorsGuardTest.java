package com.digithink.zsretail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

/**
 * 3.5.0: a component with two constructors and none marked {@code @Autowired} (and no constructor without parameters)
 * stops the start: Spring cannot choose, looks for the default constructor and fails ("No default constructor found").
 * TicketTemplatesManaged did it (ticket templates step 5): every store linked to a head office failed to start, and the
 * suite was green because the tests build the class with {@code new}. Every component of every project (whatever its
 * installation conditions, so every installation type at once): Spring's own choice of constructor
 * ({@link AutowiredAnnotationBeanPostProcessor#determineCandidateConstructors}, the step that failed) finds one, or the
 * class has a constructor without parameters. Not a substitute for a start: a missing bean still shows only there.
 */
class BeanConstructorsGuardTest {

	/** The components (@Component and what it marks: @Service, @Repository class, @Configuration, the APIs) of main code. */
	static List<Class<?>> components() throws ClassNotFoundException {
		AnnotationTypeFilter filter = new AnnotationTypeFilter(Component.class);
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
				return filter.match(reader, getMetadataReaderFactory()) && reader.getClassMetadata().isConcrete();
			}
		};
		List<Class<?>> classes = new ArrayList<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("com.digithink.zsretail")) {
			Class<?> type = Class.forName(candidate.getBeanClassName());
			if (fromMainCode(type) && !type.isInterface() && !Modifier.isAbstract(type.getModifiers())) {
				classes.add(type);
			}
		}
		return classes;
	}

	/** Not a class of a test folder or of a test-jar (the tests' own configurations). */
	static boolean fromMainCode(Class<?> type) {
		CodeSource source = type.getProtectionDomain().getCodeSource();
		String location = source == null || source.getLocation() == null ? "" : source.getLocation().toString();
		return !location.contains("test-classes") && !location.endsWith("-tests.jar");
	}

	/** Null when Spring can build the class, else why not. */
	static String problem(Class<?> type) {
		AutowiredAnnotationBeanPostProcessor processor = new AutowiredAnnotationBeanPostProcessor();
		processor.setBeanFactory(new DefaultListableBeanFactory());
		Constructor<?>[] chosen;
		try {
			chosen = processor.determineCandidateConstructors(type, type.getName());
		} catch (RuntimeException e) {
			return type.getName() + ": " + e.getMessage();
		}
		if (chosen != null && chosen.length > 0) {
			return null;
		}
		for (Constructor<?> constructor : type.getDeclaredConstructors()) {
			if (constructor.getParameterCount() == 0) {
				return null;
			}
		}
		return type.getName() + ": " + type.getDeclaredConstructors().length
				+ " constructors, none @Autowired and none without parameters";
	}

	@Test
	@DisplayName("Spring can choose a constructor for every component of every project")
	void everyComponentHasAConstructorSpringCanUse() throws ClassNotFoundException {
		List<Class<?>> classes = components();
		assertTrue(classes.size() > 300, "the scan found the components (" + classes.size() + ")");
		List<String> problems = new ArrayList<>();
		for (Class<?> type : classes) {
			String problem = problem(type);
			if (problem != null) {
				problems.add(problem);
			}
		}
		assertEquals(List.of(), problems);
	}

	/** The guard itself: the shape that failed, and the two that work. */
	@Test
	@DisplayName("Two constructors without @Autowired are refused; one, or one marked, is accepted")
	void theRuleItself() {
		assertTrue(problem(TwoConstructors.class) != null);
		assertEquals(null, problem(OneConstructor.class));
		assertEquals(null, problem(TwoConstructorsOneMarked.class));
	}

	static class TwoConstructors {
		TwoConstructors(String a) {
		}

		TwoConstructors(Integer b) {
		}
	}

	static class OneConstructor {
		OneConstructor(String a) {
		}
	}

	static class TwoConstructorsOneMarked {
		@org.springframework.beans.factory.annotation.Autowired
		TwoConstructorsOneMarked(String a) {
		}

		TwoConstructorsOneMarked(Integer b) {
		}
	}
}
