package com.digithink.zsretail.core.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assertions;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.objenesis.SpringObjenesis;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Boot 4 migration, step 1.2: golden JSON. What a mapper writes for an object against a file recorded on 2.3.9 / Jackson 2,
 * the reference for the Jackson 3 steps: every name and value, number and date text included, one property per line so a
 * diff reads.
 * <p>
 * The order of the properties is left out: Jackson puts the ones that come from a getter alone (UserAccount.isEnabled) in
 * the order reflection gives, which changes between two runs of the JVM, and no reader depends on it. The one text whose
 * order counts is the one SalesPushService hashes (its mapper sorts): {@link #assertExactly}.
 * <p>
 * {@code -Dzsretail.golden.record=true} writes the files again, into the src/test/resources of the project that owns them.
 * Only when the difference is the intended one, and the diff goes in the commit (as for the records of network-tests).
 * <p>
 * {@link #sample} fills every field of a class with a value of its type, so a golden covers every property without a
 * hand-written fixture per class, and a new field shows up as a difference to look at.
 */
public final class GoldenJson {

	public static final String RECORD_KEY = "zsretail.golden.record";

	private static final Path RESOURCES = Paths.get("src", "test", "resources");

	private GoldenJson() {
	}

	/** {@code actual} (compact JSON) against the file, whatever the order of its properties; when recording, written. */
	public static void assertMatches(String resource, String actual) {
		assertExactly(resource, sortedProperties(actual));
	}

	/** {@code actual} against the file, byte for byte: only for a text whose order is fixed and matters (a hash). */
	public static void assertExactly(String resource, String actual) {
		String shown = lines(actual);
		if (Boolean.getBoolean(RECORD_KEY)) {
			write(resource, shown);
			return;
		}
		Assertions.assertEquals(read(resource), shown, resource + " (-D" + RECORD_KEY + "=true records it again)");
	}

	/** {@code actual} against a file another project owns, as {@link #assertMatches}: compared, never recorded from here. */
	public static void assertSameAs(String resource, String actual) {
		Assertions.assertEquals(read(resource), lines(sortedProperties(actual)), resource);
	}

	/** The JSON with the properties of every object sorted, nothing else touched; a body that is not JSON comes back as it is. */
	static String sortedProperties(String json) {
		return json.startsWith("{") || json.startsWith("[") ? new Sorter(json).value() : json;
	}

	/** Reads compact JSON by hand: Jackson is what is under test, and its parsing would rewrite the numbers. */
	private static final class Sorter {
		private final String json;
		private int at;

		Sorter(String json) {
			this.json = json;
		}

		String value() {
			char c = json.charAt(at);
			return c == '{' ? object() : c == '[' ? array() : c == '"' ? string() : literal();
		}

		private String object() {
			Map<String, String> properties = new TreeMap<>();
			at++;
			while (json.charAt(at) != '}') {
				String name = string();
				at++; // the colon
				properties.put(name, value());
				if (json.charAt(at) == ',') {
					at++;
				}
			}
			at++;
			StringJoiner out = new StringJoiner(",", "{", "}");
			properties.forEach((name, value) -> out.add(name + ":" + value));
			return out.toString();
		}

		private String array() {
			StringJoiner out = new StringJoiner(",", "[", "]");
			at++;
			while (json.charAt(at) != ']') {
				out.add(value());
				if (json.charAt(at) == ',') {
					at++;
				}
			}
			at++;
			return out.toString();
		}

		private String string() {
			int start = at++;
			while (json.charAt(at) != '"') {
				at += json.charAt(at) == '\\' ? 2 : 1;
			}
			return json.substring(start, ++at);
		}

		private String literal() {
			int start = at;
			while (at < json.length() && ",]}".indexOf(json.charAt(at)) < 0) {
				at++;
			}
			return json.substring(start, at);
		}
	}

	/**
	 * The mapper of Spring MVC in both applications, as Boot builds it with no spring.jackson key (no file sets one):
	 * what the till and the head office's controllers read and write. The startup tests compare it with the real bean.
	 */
	public static ObjectMapper bootMapper() {
		AtomicReference<ObjectMapper> mapper = new AtomicReference<>();
		new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
				.run(context -> mapper.set(context.getBean(ObjectMapper.class)));
		return mapper.get();
	}

	/** The file, whichever project's test-jar holds it, with Unix line ends. */
	public static String read(String resource) {
		try (InputStream in = GoldenJson.class.getResourceAsStream("/" + resource)) {
			if (in == null) {
				throw new IllegalStateException("No golden file " + resource + ": -D" + RECORD_KEY + "=true records it");
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static void write(String resource, String text) {
		try {
			Path file = RESOURCES.resolve(resource);
			Files.createDirectories(file.getParent());
			Files.write(file, text.getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** Compact JSON, one property or array element per line, nothing else changed; any other text as it is. */
	public static String lines(String json) {
		if (!json.startsWith("{") && !json.startsWith("[")) {
			return json.endsWith("\n") ? json : json + "\n";
		}
		StringBuilder out = new StringBuilder();
		String indent = "";
		boolean inString = false;
		for (int i = 0; i < json.length(); i++) {
			char c = json.charAt(i);
			if (inString) {
				out.append(c);
				if (c == '\\') {
					out.append(json.charAt(++i));
				} else if (c == '"') {
					inString = false;
				}
			} else if (c == '"') {
				inString = true;
				out.append(c);
			} else if ((c == '{' || c == '[') && i + 1 < json.length() && json.charAt(i + 1) != '}' && json.charAt(i + 1) != ']') {
				indent += "  ";
				out.append(c).append('\n').append(indent);
			} else if ((c == '}' || c == ']') && json.charAt(i - 1) != '{' && json.charAt(i - 1) != '[') {
				indent = indent.substring(2);
				out.append('\n').append(indent).append(c);
			} else if (c == ',') {
				out.append(c).append('\n').append(indent);
			} else {
				out.append(c);
			}
		}
		return out.append('\n').toString();
	}

	// ─── Samples ────────────────────────────────────────────────────────────────────────────────────────────────────

	/** The same values every time: every field of the class and its parents set, nested objects included. */
	public static <T> T sample(Class<T> type) {
		return type.cast(value(type, type, new HashSet<>()));
	}

	/** An object with every field set; a class already being filled (a parent and its lines) stays null: no cycle. */
	private static Object filled(Class<?> type, Set<Class<?>> path) {
		if (!path.add(type)) {
			return null;
		}
		Object object = new SpringObjenesis().newInstance(type);
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
					continue;
				}
				field.setAccessible(true);
				try {
					field.set(object, value(field.getType(), field.getGenericType(), path, field.getName()));
				} catch (IllegalAccessException e) {
					throw new IllegalStateException(field.toString(), e);
				}
			}
		}
		path.remove(type);
		return object;
	}

	private static Object value(Class<?> type, Type generic, Set<Class<?>> path) {
		return value(type, generic, path, type.getSimpleName());
	}

	private static Object value(Class<?> type, Type generic, Set<Class<?>> path, String name) {
		if (type == String.class) {
			return name;
		}
		if (type == boolean.class || type == Boolean.class) {
			return Boolean.TRUE;
		}
		if (type == int.class || type == Integer.class) {
			return 7;
		}
		if (type == long.class || type == Long.class) {
			return 11L;
		}
		if (type == double.class || type == Double.class) {
			return 1.5d;
		}
		if (type == float.class || type == Float.class) {
			return 2.5f;
		}
		if (type == short.class || type == Short.class) {
			return (short) 3;
		}
		if (type == byte.class || type == Byte.class) {
			return (byte) 1;
		}
		if (type == char.class || type == Character.class) {
			return 'c';
		}
		if (type == BigDecimal.class) {
			return new BigDecimal("2.50");
		}
		if (type == LocalDateTime.class) {
			return LocalDateTime.of(2026, 3, 4, 5, 6, 7);
		}
		if (type == LocalDate.class) {
			return LocalDate.of(2026, 3, 4);
		}
		if (type == LocalTime.class) {
			return LocalTime.of(5, 6, 7);
		}
		if (type == OffsetDateTime.class) {
			return OffsetDateTime.of(2026, 3, 4, 5, 6, 7, 0, ZoneOffset.ofHours(1));
		}
		if (type == Date.class) {
			return Date.from(LocalDateTime.of(2026, 3, 4, 5, 6, 7).toInstant(ZoneOffset.UTC));
		}
		if (type.isEnum()) {
			return type.getEnumConstants()[0];
		}
		if (type.isArray()) {
			Object array = Array.newInstance(type.getComponentType(), 1);
			Array.set(array, 0, value(type.getComponentType(), type.getComponentType(), path, name));
			return array;
		}
		if (Collection.class.isAssignableFrom(type)) {
			Object element = element(generic, 0, path, name);
			Collection<Object> collection = Set.class.isAssignableFrom(type) ? new LinkedHashSet<>() : new ArrayList<>();
			if (element != null) {
				collection.add(element);
			}
			return collection;
		}
		if (Map.class.isAssignableFrom(type)) {
			Object key = element(generic, 0, path, name + "Key");
			Object mapped = element(generic, 1, path, name + "Value");
			Map<Object, Object> map = new LinkedHashMap<>();
			if (key != null && mapped != null) {
				map.put(key, mapped);
			}
			return map;
		}
		if (type.isInterface() || Modifier.isAbstract(type.getModifiers()) || type == Object.class) {
			return null;
		}
		if (type.getName().startsWith("java.")) {
			return null;
		}
		return filled(type, path);
	}

	/** The element of a generic collection or map: its type argument, plain or itself generic. */
	private static Object element(Type generic, int index, Set<Class<?>> path, String name) {
		if (!(generic instanceof ParameterizedType)) {
			return null;
		}
		Type argument = ((ParameterizedType) generic).getActualTypeArguments()[index];
		if (argument instanceof Class) {
			return value((Class<?>) argument, argument, path, name);
		}
		if (argument instanceof ParameterizedType) {
			return value((Class<?>) ((ParameterizedType) argument).getRawType(), argument, path, name);
		}
		return null;
	}

}
