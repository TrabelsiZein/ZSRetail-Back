package com.digithink.zsretail.core.support;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import jakarta.persistence.Entity;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Boot 4 migration, step 1.8: every {@code @Query} of every repository of a started application, run on real SQL Server
 * through Hibernate 6, twice: once with null for every parameter that can be null (the {@code :param is null} filters),
 * once with a sample value of each parameter's type. Each call in a transaction rolled back, on the empty tables of a test
 * start: what it proves is that SQL Server accepts the statement Hibernate writes and the values it binds, not the rows.
 * <p>
 * A collection is never null (an empty or null {@code in} list is not a filter of ours), a page, a sort and a primitive
 * get their sample in both runs.
 */
public final class QuerySweep {

	private QuerySweep() {
	}

	/** The failures, "Repository.method(nulls|samples): the root cause", sorted; and the count of calls in the first line. */
	public static List<String> run(MigTestApplication started) {
		EntityManager entityManager = started.bean(EntityManagerFactory.class).createEntityManager();
		TransactionTemplate transaction = new TransactionTemplate(started.bean(PlatformTransactionManager.class));
		Map<String, String> failures = new TreeMap<>();
		int calls = 0;
		for (Map.Entry<String, Object[]> query : queriesOf(started).entrySet()) {
			Object repository = query.getValue()[0];
			Method method = (Method) query.getValue()[1];
			for (boolean nulls : new boolean[] { true, false }) {
				Object[] arguments = arguments(method, nulls, entityManager);
				calls++;
				String failure = call(transaction, repository, method, arguments);
				if (failure != null) {
					failures.put(query.getKey() + "(" + (nulls ? "nulls" : "samples") + ")", failure);
				}
			}
		}
		entityManager.close();
		List<String> lines = new ArrayList<>();
		lines.add("calls " + calls);
		failures.forEach((where, why) -> lines.add(where + ": " + why));
		return lines;
	}

	/** The @Query methods the sweep runs, "Repository.method/parameters", sorted: for the message of a changed count. */
	public static List<String> queries(MigTestApplication started) {
		return new ArrayList<>(queriesOf(started).keySet());
	}

	private static Map<String, Object[]> queriesOf(MigTestApplication started) {
		Map<String, Object[]> queries = new TreeMap<>();
		for (Object repository : started.context().getBeansOfType(Repository.class).values()) {
			for (Class<?> type : ours(repository.getClass().getInterfaces())) {
				for (Method method : sorted(type.getMethods())) {
					// A bridge (an override with a narrower return type) carries the annotation too: the method once
					if (method.getAnnotation(Query.class) != null && !method.isBridge()) {
						queries.put(type.getSimpleName() + "." + method.getName() + "/"
								+ Arrays.toString(method.getParameterTypes()).replace("class ", "").replace("interface ", ""),
								new Object[] { repository, method });
					}
				}
			}
		}
		return queries;
	}

	private static List<Class<?>> ours(Class<?>[] interfaces) {
		List<Class<?>> types = new ArrayList<>();
		for (Class<?> type : interfaces) {
			if (type.getName().startsWith("com.digithink.") && Repository.class.isAssignableFrom(type)) {
				types.add(type);
			}
		}
		return types;
	}

	private static List<Method> sorted(Method[] methods) {
		List<Method> list = new ArrayList<>(Arrays.asList(methods));
		list.sort(Comparator.comparing(Method::toGenericString));
		return list;
	}

	private static String call(TransactionTemplate transaction, Object repository, Method method, Object[] arguments) {
		try {
			transaction.executeWithoutResult(status -> {
				status.setRollbackOnly();
				try {
					Object result = method.invoke(repository, arguments);
					if (result instanceof Stream) {
						try (Stream<?> stream = (Stream<?>) result) {
							stream.count();
						}
					}
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(e);
				} catch (InvocationTargetException e) {
					throw e.getCause() instanceof RuntimeException ? (RuntimeException) e.getCause()
							: new IllegalStateException(e.getCause());
				}
			});
			return null;
		} catch (RuntimeException e) {
			Throwable root = e;
			while (root.getCause() != null && root.getCause() != root) {
				root = root.getCause();
			}
			String message = String.valueOf(root.getMessage());
			int end = message.indexOf('\n');
			return root.getClass().getSimpleName() + ": " + (end < 0 ? message : message.substring(0, end));
		}
	}

	private static Object[] arguments(Method method, boolean nulls, EntityManager entityManager) {
		Class<?>[] types = method.getParameterTypes();
		Type[] generics = method.getGenericParameterTypes();
		Object[] arguments = new Object[types.length];
		for (int i = 0; i < types.length; i++) {
			Class<?> type = types[i];
			boolean always = type.isPrimitive() || Collection.class.isAssignableFrom(type) || Pageable.class.isAssignableFrom(type)
					|| Sort.class.isAssignableFrom(type);
			arguments[i] = nulls && !always ? null : sample(type, generics[i], entityManager);
		}
		return arguments;
	}

	private static Object sample(Class<?> type, Type generic, EntityManager entityManager) {
		if (type == String.class) {
			return "x";
		}
		if (type == Long.class || type == long.class) {
			return 1L;
		}
		if (type == Integer.class || type == int.class) {
			return 1;
		}
		if (type == Short.class || type == short.class) {
			return (short) 1;
		}
		if (type == Double.class || type == double.class) {
			return 1.0;
		}
		if (type == Float.class || type == float.class) {
			return 1.0f;
		}
		if (type == BigDecimal.class) {
			return BigDecimal.ONE;
		}
		if (type == Boolean.class || type == boolean.class) {
			return Boolean.TRUE;
		}
		if (type == LocalDate.class) {
			return LocalDate.of(2026, 11, 10);
		}
		if (type == LocalDateTime.class) {
			return LocalDateTime.of(2026, 11, 10, 10, 0);
		}
		if (type == LocalTime.class) {
			return LocalTime.of(10, 0);
		}
		if (type == Instant.class) {
			return Instant.parse("2026-11-10T09:00:00Z");
		}
		if (type == Date.class) {
			return new Date(Instant.parse("2026-11-10T09:00:00Z").toEpochMilli());
		}
		if (type.isEnum()) {
			return type.getEnumConstants()[0];
		}
		if (Pageable.class.isAssignableFrom(type)) {
			return PageRequest.of(0, 5);
		}
		if (Sort.class.isAssignableFrom(type)) {
			return Sort.unsorted();
		}
		if (Collection.class.isAssignableFrom(type)) {
			Type element = generic instanceof ParameterizedType ? ((ParameterizedType) generic).getActualTypeArguments()[0]
					: String.class;
			Class<?> elementClass = element instanceof Class ? (Class<?>) element
					: element instanceof ParameterizedType ? (Class<?>) ((ParameterizedType) element).getRawType() : String.class;
			Object one = sample(elementClass, element, entityManager);
			return Set.class.isAssignableFrom(type) ? Collections.singleton(one) : Collections.singletonList(one);
		}
		if (type.getAnnotation(Entity.class) != null) {
			try {
				return entityManager.getReference(type, 1L);
			} catch (RuntimeException notALongId) {
				return null;
			}
		}
		return null;
	}
}
