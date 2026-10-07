package com.digithink.zsretail.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import com.digithink.zsretail.support.Installations;

/**
 * Configuration step C1 (was task 9.3): the optional outside file of an installation
 * (MachineFileEnvironmentPostProcessor) and the installation type in effect (InstallationTypeEnvironmentPostProcessor).
 * Where the file is found, the refusals, where it sits among the property sources, and starts run the way Spring Boot
 * runs them (the two post-processors around ConfigFileApplicationListener, on the real application.properties and type
 * files).
 */
class MachineFileTest {

	@TempDir
	Path folder;

	private static String refusal(Runnable call) {
		return assertThrows(IllegalStateException.class, call::run).getMessage();
	}

	/**
	 * The environment of a start: the system properties given (above everything, like -D), the outside file, the
	 * configuration files of the classpath, then the type check.
	 */
	private static StandardEnvironment start(Map<String, Object> systemProperties) {
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test-system-properties", systemProperties));
		SpringApplication application = new SpringApplication();
		new MachineFileEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ConfigFileApplicationListener().postProcessEnvironment(env, application);
		new InstallationTypeEnvironmentPostProcessor().postProcessEnvironment(env, application);
		return env;
	}

	private Path outsideFile(String name, String... lines) throws Exception {
		Path file = folder.resolve(name);
		Files.write(file, Arrays.asList(lines));
		return file;
	}

	@Test
	@DisplayName("Found by the system property, else in Tomcat as conf/zsretail/<context name>.properties when it exists; else none")
	void locate() throws Exception {
		assertEquals(Paths.get("D:/x/store.properties"),
				MachineFileEnvironmentPostProcessor.locate(" D:/x/store.properties ", "zsretailws", "C:/tomcat"));
		assertNull(MachineFileEnvironmentPostProcessor.locate(null, "zsretailws", folder.toString()),
				"no Tomcat file: no outside file");
		Path conf = Files.createDirectories(folder.resolve("conf").resolve("zsretail"));
		Files.write(conf.resolve("zsretailho.properties"), Collections.singletonList("server.port=1"));
		assertEquals(conf.resolve("zsretailho.properties"),
				MachineFileEnvironmentPostProcessor.locate("  ", "zsretailho", folder.toString()));
		assertNull(MachineFileEnvironmentPostProcessor.locate(null, null, null));
		assertNull(MachineFileEnvironmentPostProcessor.locate(null, "zsretailws", null),
				"outside Tomcat the context name alone is not enough");
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
	@DisplayName("A file named by -Dzsretail.machine-file that does not exist stops the startup")
	void missingNamedFile() {
		Path missing = folder.resolve("missing.properties");
		assertTrue(refusal(() -> MachineFileEnvironmentPostProcessor.load(missing))
				.startsWith("Outside file not found: " + missing));
		assertTrue(refusal(() -> start(Map.of("zsretail.machine-file", missing.toString())))
				.startsWith("Outside file not found: " + missing));
	}

	@Test
	@DisplayName("C1: a start with no outside file runs the default type, store, from its type file; where it runs (port,"
			+ " database) is not in the WAR (since aa6fa48)")
	void noOutsideFileDefaultType() {
		StandardEnvironment env = start(new HashMap<>());
		assertArrayEquals(new String[] { "store" }, env.getActiveProfiles());
		assertEquals("STORE", env.getProperty("node.type"));
		assertEquals("LOCAL", env.getProperty("ownership.catalogue"));
		assertNull(env.getProperty("server.port"), "the port is in the outside file only");
		assertNull(env.getProperty("spring.datasource.url"), "the database is in the outside file only");
		assertEquals("com.microsoft.sqlserver.jdbc.SQLServerDriver", env.getProperty("spring.datasource.driverClassName"));
		assertNull(env.getProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY));
		NodeOwnership.resolve(env);
	}

	@Test
	@DisplayName("C1: the type named by -Dspring.profiles.active alone (no outside file) is used; no port without the outside file")
	void typeWithoutOutsideFile() {
		StandardEnvironment env = start(Map.of("spring.profiles.active", "headoffice"));
		assertArrayEquals(new String[] { "headoffice" }, env.getActiveProfiles());
		assertEquals("HEAD_OFFICE", env.getProperty("node.type"));
		assertNull(env.getProperty("server.port"));
	}

	@Test
	@DisplayName("C1: an unknown profile, two profiles or an old preset name are refused, naming the two types")
	void unknownProfileRefused() throws Exception {
		for (String active : new String[] { "dynamics-prod", "store,headoffice", "store-erp", "network-store" }) {
			String message = refusal(() -> start(Map.of("spring.profiles.active", active)));
			assertTrue(message.contains("it must be one installation type, spring.profiles.active=store | headoffice"),
					message);
		}
		Path file = outsideFile("old.properties", "spring.profiles.active=network-store-erp");
		String message = refusal(() -> start(Map.of("zsretail.machine-file", file.toString())));
		assertTrue(message.startsWith("The active profile is [network-store-erp]"), message);
		assertTrue(message.contains("(" + file + ")"), message);
		assertTrue(refusal(() -> InstallationTypeEnvironmentPostProcessor.check(new String[0], null))
				.startsWith("The active profile is []"));
		for (String type : NodeOwnership.TYPES) {
			InstallationTypeEnvironmentPostProcessor.check(new String[] { type }, null);
		}
	}

	@Test
	@DisplayName("C1: a key of the outside file wins over the type file and application.properties; a key it leaves out comes from the type file")
	void outsideKeyWins() throws Exception {
		Path file = outsideFile("ho.properties", "spring.profiles.active=headoffice", "server.port=999",
				"spring.datasource.url=jdbc:sqlserver://localhost;databaseName=pos_ho_x", "ownership.catalogue=ERP",
				"ownership.customers=ERP", "ownership.supply=ERP");
		StandardEnvironment env = start(Map.of("zsretail.machine-file", file.toString()));
		assertArrayEquals(new String[] { "headoffice" }, env.getActiveProfiles());
		assertEquals("999", env.getProperty("server.port"));
		assertEquals("jdbc:sqlserver://localhost;databaseName=pos_ho_x", env.getProperty("spring.datasource.url"));
		assertEquals("ERP", env.getProperty("ownership.catalogue"));
		assertEquals("HEAD_OFFICE", env.getProperty("node.type"), "left out: from the type file");
		assertEquals("LOCAL", env.getProperty("ownership.promotions"), "left out: from the type file");
		assertEquals("com.microsoft.sqlserver.jdbc.SQLServerDriver", env.getProperty("spring.datasource.driverClassName"),
				"left out: from application.properties");
		assertEquals(file.toString(), env.getProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY));
		assertTrue(NodeOwnership.isHeadOfficeErpSet(env));

		Path noType = outsideFile("store.properties", "server.port=557");
		StandardEnvironment store = start(Map.of("zsretail.machine-file", noType.toString()));
		assertArrayEquals(new String[] { "store" }, store.getActiveProfiles(), "an outside file without a type: store");
		assertEquals("557", store.getProperty("server.port"));
	}

	@Test
	@DisplayName("Loaded above the configuration files and below the system properties; the path read is kept")
	void propertySource() throws Exception {
		Path file = outsideFile("store-x.properties", "spring.profiles.active=store", "server.port=557");
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
		assertEquals("store", env.getProperty("spring.profiles.active"));
		assertEquals(file.toString(), env.getProperty("zsretail.machine-file.loaded"));
	}

	@Test
	@DisplayName("Every outside file of deploy/ names store or headoffice (or none) and never application.standalone; the model names none")
	void deployFolder() {
		List<String> files = Installations.machineFiles();
		assertTrue(files.size() >= 9, files.toString());
		for (String file : files) {
			java.util.Properties properties = Installations.file(Installations.DEPLOY.resolve(file));
			String type = properties.getProperty("spring.profiles.active");
			if (type != null) {
				InstallationTypeEnvironmentPostProcessor.check(new String[] { type.trim() }, file);
			}
			assertNull(properties.getProperty("application.standalone"), file);
		}
		assertNull(Installations.file(Installations.DEPLOY.resolve("machine-model.properties"))
				.getProperty("spring.profiles.active"));
	}

	@Test
	@DisplayName("C1: the startup summary names the type, the database, the owners, the sales, the NAV address and the outside file, never a password")
	void startupSummary() {
		org.springframework.mock.env.MockEnvironment env = Installations.machine("dev/store-test-nav.properties");
		env.setProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY, "D:/deploy/dev/store-test-nav.properties");
		String line = InstallationSummary.summary(env, NodeOwnership.resolve(env));
		assertEquals("Installation: type STORE, database pos_db_test, catalogue ERP, customers ERP, promotions LOCAL,"
				+ " loyalty LOCAL, supply ERP, sales to [ERP], NAV http://192.168.10.166:24/test4/ODataV4,"
				+ " outside file D:/deploy/dev/store-test-nav.properties", line);
		org.springframework.mock.env.MockEnvironment plain = Installations.type("store");
		assertTrue(InstallationSummary.summary(plain, NodeOwnership.resolve(plain))
				.endsWith("sales to nowhere, no outside file"));
		assertTrue(!line.contains("password"));
	}
}
