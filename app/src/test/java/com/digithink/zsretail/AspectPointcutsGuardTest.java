package com.digithink.zsretail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.aspectj.lang.annotation.After;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.filter.AnnotationTypeFilter;

/**
 * Code cleanup step 5: the aspects name the classes they wrap by their full name, in a string. A class moved to another
 * package or project would leave the aspect silently doing nothing. Every execution(...) clause of every advice of
 * every aspect of the application must match at least one method of the class it names.
 */
class AspectPointcutsGuardTest {

	static final Pattern EXECUTION = Pattern.compile("execution\\(([^()]*\\([^()]*\\))\\)");
	static final List<Class<? extends Annotation>> ADVICES = Arrays.asList(Around.class, Before.class, After.class,
			AfterReturning.class, AfterThrowing.class, Pointcut.class);

	/** The aspects of the application, whatever their conditions. */
	static List<Class<?>> aspects() throws ClassNotFoundException {
		AnnotationTypeFilter aspect = new AnnotationTypeFilter(Aspect.class);
		ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
			@Override
			protected boolean isCandidateComponent(MetadataReader reader) throws IOException {
				return aspect.match(reader, getMetadataReaderFactory());
			}
		};
		List<Class<?>> aspects = new ArrayList<>();
		for (BeanDefinition candidate : scanner.findCandidateComponents("com.digithink.zsretail")) {
			aspects.add(Class.forName(candidate.getBeanClassName()));
		}
		return aspects;
	}

	/** The pointcut expression of an advice or pointcut annotation, or null. */
	static String expression(Annotation annotation) {
		if (annotation instanceof Around) {
			return ((Around) annotation).value();
		}
		if (annotation instanceof Before) {
			return ((Before) annotation).value();
		}
		if (annotation instanceof After) {
			return ((After) annotation).value();
		}
		if (annotation instanceof AfterReturning) {
			AfterReturning advice = (AfterReturning) annotation;
			return advice.pointcut().isEmpty() ? advice.value() : advice.pointcut();
		}
		if (annotation instanceof AfterThrowing) {
			AfterThrowing advice = (AfterThrowing) annotation;
			return advice.pointcut().isEmpty() ? advice.value() : advice.pointcut();
		}
		if (annotation instanceof Pointcut) {
			return ((Pointcut) annotation).value();
		}
		return null;
	}

	/** True when the execution clause matches at least one method of the class it names. */
	static boolean matchesSomething(String clause) throws ClassNotFoundException {
		String signature = clause.substring(0, clause.indexOf('('));
		String declaring = signature.substring(signature.lastIndexOf(' ') + 1, signature.lastIndexOf('.'));
		Class<?> type = Class.forName(declaring);
		AspectJExpressionPointcut pointcut = new AspectJExpressionPointcut();
		pointcut.setExpression("execution(" + clause + ")");
		for (Method method : type.getMethods()) {
			if (pointcut.matches(method, type)) {
				return true;
			}
		}
		return false;
	}

	@Test
	@DisplayName("Every execution(...) clause of the aspects matches a method of the class it names")
	void pointcutsMatch() throws Exception {
		List<Class<?>> aspects = aspects();
		assertTrue(aspects.size() >= 2, "aspects: " + aspects);
		List<String> clauses = new ArrayList<>();
		List<String> broken = new ArrayList<>();
		for (Class<?> aspect : aspects) {
			for (Method advice : aspect.getDeclaredMethods()) {
				for (Annotation annotation : advice.getAnnotations()) {
					String expression = ADVICES.contains(annotation.annotationType()) ? expression(annotation) : null;
					if (expression == null) {
						continue;
					}
					Matcher matcher = EXECUTION.matcher(expression);
					while (matcher.find()) {
						String clause = matcher.group(1);
						clauses.add(clause);
						boolean ok;
						try {
							ok = matchesSomething(clause);
						} catch (ClassNotFoundException e) {
							ok = false;
						}
						if (!ok) {
							broken.add(aspect.getSimpleName() + "." + advice.getName() + ": execution(" + clause + ")");
						}
					}
				}
			}
		}
		assertTrue(clauses.size() >= 10, "execution clauses read: " + clauses);
		assertEquals(new ArrayList<String>(), broken, "A pointcut names a class or method that is not there any more");
	}

	@Test
	@DisplayName("The guard itself: a clause on a missing method or class matches nothing")
	void guardCatches() throws Exception {
		assertTrue(matchesSomething("* com.digithink.zsretail.core.erp.service.ErpSyncJobRunner.run(..)"));
		assertTrue(!matchesSomething("* com.digithink.zsretail.core.erp.service.ErpSyncJobRunner.noSuchMethod(..)"));
	}
}
