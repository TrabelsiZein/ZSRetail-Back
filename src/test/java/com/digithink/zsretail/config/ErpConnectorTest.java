package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.support.Installations;

/**
 * Code cleanup, step 1: erp.connector, one line that chooses the ERP connector, translated into the two old enabled
 * lines (ErpConnectorEnvironmentPostProcessor). Each value, absent, unknown, a contradicting old line, every outside
 * file of deploy/ and configs/ resolving as before, and the startup summary. Starts run the way Spring Boot runs them
 * (the post-processors around ConfigFileApplicationListener, on the real application.properties and type files).
 */
class ErpConnectorTest {

	private static final String DYNAMICS_NAV = "erp.dynamicsnav.enabled";
	private static final String NAVPOSPAGES = "erp.navpospages.enabled";

	@TempDir
	Path folder;

	private static String refusal(Runnable call) {
		return assertThrows(IllegalStateException.class, call::run).getMessage();
	}

	/** A start: the system properties given (above everything, like -D), the outside file, the configuration files. */
	private static StandardEnvironment start(Map<String, Object> systemProperties, boolean translate) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test-system-properties", systemProperties));
		SpringApplication application = new SpringApplication();
		new MachineFileEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ConfigFileApplicationListener().postProcessEnvironment(env, application);
		new InstallationTypeEnvironmentPostProcessor().postProcessEnvironment(env, application);
		if (translate) {
			new ErpConnectorEnvironmentPostProcessor().postProcessEnvironment(env, application);
		}
		return env;
	}

	private StandardEnvironment startWith(Map<String, Object> systemProperties, String... outsideLines)
			{
		Path file = folder.resolve("machine.properties");
		try {
			Files.write(file, Arrays.asList(outsideLines));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Map<String, Object> properties = new HashMap<>(systemProperties);
		properties.put(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY, file.toString());
		return start(properties, true);
	}

	private StandardEnvironment startWith(String... outsideLines) {
		return startWith(Collections.emptyMap(), outsideLines);
	}

	@Test
	@DisplayName("Each value (trimmed, any case) gives the two old lines")
	void translate() {
		assertEquals("{erp.dynamicsnav.enabled=true, erp.navpospages.enabled=false}",
				ErpConnectorEnvironmentPostProcessor.translate(" DynamicsNav ").toString());
		assertEquals("{erp.dynamicsnav.enabled=false, erp.navpospages.enabled=true}",
				ErpConnectorEnvironmentPostProcessor.translate("NAVPOSPAGES").toString());
		assertEquals("{erp.dynamicsnav.enabled=false, erp.navpospages.enabled=false}",
				ErpConnectorEnvironmentPostProcessor.translate("none\t").toString());
	}

	@Test
	@DisplayName("In a start: the outside file's erp.connector wins over the type file's old line")
	void eachValueInAStart() {
		StandardEnvironment nav = startWith("erp.connector= dynamicsnav ");
		assertEquals("true", nav.getProperty(DYNAMICS_NAV), "over erp.dynamicsnav.enabled=false of the type file");
		assertEquals("false", nav.getProperty(NAVPOSPAGES));
		assertEquals("dynamicsnav", ErpConnectorEnvironmentPostProcessor.inEffect(nav));

		StandardEnvironment pages = startWith("spring.profiles.active=headoffice", "erp.connector=NavPosPages");
		assertEquals("false", pages.getProperty(DYNAMICS_NAV));
		assertEquals("true", pages.getProperty(NAVPOSPAGES));
		assertEquals("navpospages", ErpConnectorEnvironmentPostProcessor.inEffect(pages));

		StandardEnvironment none = startWith("erp.connector=none");
		assertEquals("false", none.getProperty(DYNAMICS_NAV));
		assertEquals("false", none.getProperty(NAVPOSPAGES));
		assertEquals("none", ErpConnectorEnvironmentPostProcessor.inEffect(none));

		// The translation sits just above the source that gives erp.connector
		List<String> names = new ArrayList<>();
		nav.getPropertySources().forEach(source -> names.add(source.getName()));
		assertEquals(names.indexOf(MachineFileEnvironmentPostProcessor.PROPERTY_SOURCE_NAME) - 1,
				names.indexOf(ErpConnectorEnvironmentPostProcessor.PROPERTY_SOURCE_NAME), names.toString());
	}

	@Test
	@DisplayName("An old line that agrees is accepted, in the same source or above")
	void agreeingOldLine() {
		StandardEnvironment env = startWith(Collections.singletonMap(NAVPOSPAGES, "false"), "erp.connector=dynamicsnav",
				"erp.dynamicsnav.enabled= TRUE ");
		assertEquals("dynamicsnav", ErpConnectorEnvironmentPostProcessor.inEffect(env));
	}

	@Test
	@DisplayName("Absent: nothing translated, the old lines act as before")
	void absent() {
		StandardEnvironment env = startWith("erp.dynamicsnav.enabled=true");
		assertNull(env.getPropertySources().get(ErpConnectorEnvironmentPostProcessor.PROPERTY_SOURCE_NAME));
		assertEquals("true", env.getProperty(DYNAMICS_NAV));
		assertNull(env.getProperty(NAVPOSPAGES));
		assertEquals("dynamicsnav", ErpConnectorEnvironmentPostProcessor.inEffect(env));
		assertEquals("none", ErpConnectorEnvironmentPostProcessor.inEffect(startWith("server.port=1")));
	}

	@Test
	@DisplayName("Unknown value: refused naming the key and the values")
	void unknown() {
		assertEquals("Invalid value ' nav ' for property erp.connector: none | dynamicsnav | navpospages (or leave it"
				+ " out and use the old lines erp.dynamicsnav.enabled and erp.navpospages.enabled).",
				refusal(() -> ErpConnectorEnvironmentPostProcessor.translate(" nav ")));
		assertTrue(refusal(() -> startWith("erp.connector=")).startsWith("Invalid value '' for property erp.connector"));
	}

	@Test
	@DisplayName("Contradiction with an old line of the same or a higher source: refused naming both keys")
	void contradiction() {
		assertEquals("Invalid combination: erp.connector=dynamicsnav with erp.dynamicsnav.enabled=false (machineFile)."
				+ " erp.connector=dynamicsnav means erp.dynamicsnav.enabled=true: remove the old line, or make it agree.",
				refusal(() -> startWith("erp.connector=dynamicsnav", "erp.dynamicsnav.enabled=false")));
		assertEquals("Invalid combination: erp.connector=none with erp.navpospages.enabled=true"
				+ " (test-system-properties). erp.connector=none means erp.navpospages.enabled=false: remove the old"
				+ " line, or make it agree.",
				refusal(() -> startWith(Collections.singletonMap(NAVPOSPAGES, " true"), "erp.connector=none")));
	}

	/** Every key of every source of a start, resolved (the message when a placeholder cannot be). */
	private static Map<String, String> resolved(StandardEnvironment env) {
		Map<String, String> keys = new TreeMap<>();
		for (PropertySource<?> source : env.getPropertySources()) {
			if (source instanceof EnumerablePropertySource
					&& !source.getName().equals(ErpConnectorEnvironmentPostProcessor.PROPERTY_SOURCE_NAME)) {
				for (String key : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
					String value;
					try {
						value = env.getProperty(key);
					} catch (IllegalArgumentException e) {
						value = "! " + e.getMessage();
					}
					keys.put(key, value);
				}
			}
		}
		return keys;
	}

	@Test
	@DisplayName("Every outside file of deploy/ and configs/ resolves as before, key by key")
	void existingFilesUnchanged() {
		List<Path> files = new ArrayList<>();
		Installations.machineFiles().forEach(file -> files.add(Installations.DEPLOY.resolve(file)));
		files.add(Installations.DEPLOY.resolve("machine-model.properties"));
		Installations.configFiles().forEach(file -> files.add(Installations.CONFIGS.resolve(file)));
		assertTrue(files.size() > 10, files.toString());
		for (Path file : files) {
			Map<String, Object> property = Collections.singletonMap(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY,
					file.toAbsolutePath().toString());
			StandardEnvironment before = start(property, false);
			StandardEnvironment after = start(property, true);
			assertEquals(resolved(before), resolved(after), file.toString());
			assertNull(after.getPropertySources().get(ErpConnectorEnvironmentPostProcessor.PROPERTY_SOURCE_NAME),
					file.toString());
			for (String key : new String[] { DYNAMICS_NAV, NAVPOSPAGES }) {
				assertEquals(before.getProperty(key), after.getProperty(key), file + " " + key);
			}
		}
	}

	@Test
	@DisplayName("Startup summary: names the connector in effect, whichever way it was set")
	void summary() {
		MockEnvironment old = Installations.machine("dev/store-a-erp.properties");
		String line = InstallationSummary.summary(old, NodeOwnership.resolve(old));
		assertTrue(line.contains("ERP connector dynamicsnav, NAV "), line);

		MockEnvironment plain = Installations.machine("dev/store-b.properties");
		assertTrue(InstallationSummary.summary(plain, NodeOwnership.resolve(plain)).contains("ERP connector none, "));

		StandardEnvironment pages = startWith("spring.profiles.active=headoffice", "erp.connector=navpospages",
				"ownership.catalogue=ERP");
		line = InstallationSummary.summary(pages, NodeOwnership.resolve(pages));
		assertTrue(line.contains("ERP connector navpospages, "), line);
		assertFalse(line.contains("NAV "), line);
	}
}
