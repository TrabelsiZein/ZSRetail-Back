package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.digithink.zsretail.support.Installations;

/**
 * Head office plan, task 9.3: the machine file of an installation (MachineFileEnvironmentPostProcessor). Where it is
 * found, the refusals when it is missing, unreadable or names no preset, where it sits among the property sources, and
 * that every machine file kept in deploy/ names a known preset and never application.standalone.
 */
class MachineFileTest {

	@TempDir
	Path folder;

	private static String refusal(Runnable call) {
		return assertThrows(IllegalStateException.class, call::run).getMessage();
	}

	@Test
	@DisplayName("Found by the system property, else in Tomcat as conf/zsretail/<context name>.properties; nothing: refused, saying what is missing")
	void locate() {
		assertEquals(Paths.get("D:/x/store.properties"),
				MachineFileEnvironmentPostProcessor.locate(" D:/x/store.properties ", "zsretailws", "C:/tomcat"));
		assertEquals(Paths.get("C:/tomcat", "conf", "zsretail", "zsretailws.properties"),
				MachineFileEnvironmentPostProcessor.locate(null, "zsretailws", "C:/tomcat"));
		assertEquals(Paths.get("C:/tomcat", "conf", "zsretail", "zsretailho.properties"),
				MachineFileEnvironmentPostProcessor.locate("  ", "zsretailho", "C:/tomcat"));
		String message = refusal(() -> MachineFileEnvironmentPostProcessor.locate(null, null, null));
		assertTrue(message.startsWith("No machine file: start with -Dzsretail.machine-file=<path of the file>"), message);
		assertTrue(message.contains("conf/zsretail/<context name>.properties"), message);
		assertTrue(message.contains("store | store-erp | headoffice | headoffice-erp | network-store | network-store-erp"));
		assertTrue(refusal(() -> MachineFileEnvironmentPostProcessor.locate(null, "zsretailws", null))
				.startsWith("No machine file"), "outside Tomcat the context name alone is not enough");
	}

	@Test
	@DisplayName("Tomcat context names: ROOT, a plain name, a nested path")
	void contextNames() {
		assertEquals("ROOT", invokeContextName(""));
		assertEquals("ROOT", invokeContextName("/"));
		assertEquals("zsretailws", invokeContextName("/zsretailws"));
		assertEquals("pos#store1", invokeContextName("/pos/store1"));
	}

	private static String invokeContextName(String path) {
		try {
			java.lang.reflect.Method method = com.digithink.zsretail.POSMainApp.class.getDeclaredMethod("contextName",
					String.class);
			method.setAccessible(true);
			return (String) method.invoke(null, path);
		} catch (ReflectiveOperationException e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	@DisplayName("A missing file and a file without a preset, an unknown preset or two are refused, naming the presets")
	void refusals() throws Exception {
		Path missing = folder.resolve("missing.properties");
		assertTrue(refusal(() -> MachineFileEnvironmentPostProcessor.load(missing))
				.startsWith("Machine file not found: " + missing));
		for (String active : new String[] { null, "", "standalone-dev", "store,network-store", "dynamics-prod" }) {
			String message = refusal(() -> MachineFileEnvironmentPostProcessor.checkPreset(missing, active));
			assertTrue(message.contains("must name one preset: spring.profiles.active=store | store-erp | headoffice |"
					+ " headoffice-erp | network-store | network-store-erp"), message);
		}
		for (String preset : NodeOwnership.PRESETS) {
			MachineFileEnvironmentPostProcessor.checkPreset(missing, " " + preset + " ");
		}
	}

	@Test
	@DisplayName("Loaded above the configuration files and below the system properties; the path read is kept")
	void propertySource() throws Exception {
		Path file = folder.resolve("store-x.properties");
		Files.write(file, Collections.singletonList("spring.profiles.active=network-store\nserver.port=557\n"));
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
				Collections.singletonMap("zsretail.machine-file", file.toString())));
		env.getPropertySources().addLast(new MapPropertySource("applicationConfig: [classpath:/application.properties]",
				Collections.singletonMap("server.port", "444")));
		new MachineFileEnvironmentPostProcessor().postProcessEnvironment(env, null);
		List<String> order = new ArrayList<>();
		for (PropertySource<?> source : env.getPropertySources()) {
			order.add(source.getName());
		}
		assertEquals(order.indexOf(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME) + 1,
				order.indexOf("machineFile"), order.toString());
		assertTrue(order.indexOf("machineFile") < order.indexOf("applicationConfig: [classpath:/application.properties]"));
		assertEquals("557", env.getProperty("server.port"));
		assertEquals("network-store", env.getProperty("spring.profiles.active"));
		assertEquals(file.toString(), env.getProperty("zsretail.machine-file.loaded"));
	}

	@Test
	@DisplayName("Every machine file of deploy/ names one known preset and never application.standalone; the model names none")
	void deployFolder() {
		List<String> files = Installations.machineFiles();
		assertTrue(files.size() >= 9, files.toString());
		for (String file : files) {
			java.util.Properties properties = Installations.file(Installations.DEPLOY.resolve(file));
			MachineFileEnvironmentPostProcessor.checkPreset(Paths.get(file), properties.getProperty("spring.profiles.active"));
			assertNull(properties.getProperty("application.standalone"), file);
		}
		assertEquals("CHANGE_ME", Installations.file(Installations.DEPLOY.resolve("machine-model.properties"))
				.getProperty("spring.profiles.active"));
	}
}
