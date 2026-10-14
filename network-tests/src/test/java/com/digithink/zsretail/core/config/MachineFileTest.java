package com.digithink.zsretail.core.config;

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
import org.springframework.core.env.SystemEnvironmentPropertySource;

import com.digithink.zsretail.headoffice.HeadOfficeApplication;
import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.support.Starts;

/**
 * Configuration step C1 (was task 9.3): the optional outside file of an installation
 * (MachineFileEnvironmentPostProcessor) and the installation type in effect (InstallationTypeEnvironmentPostProcessor;
 * code cleanup step 8: the application's, a type line refused).
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
	 * configuration files of the classpath, then the type check. Code cleanup step 6.5: as the application of the type
	 * the start names (support/Starts), store when it names none.
	 */
	private static StandardEnvironment start(Map<String, Object> systemProperties) {
		return startAs(Starts.applicationOf(systemProperties), systemProperties);
	}

	/** A start as the application of this type, whatever the start names. */
	private static StandardEnvironment startAs(String type, Map<String, Object> systemProperties) {
		StandardEnvironment env = Starts.environment(type, systemProperties);
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
		return ZsRetailApplication.contextName(path);
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
	@DisplayName("C1: a start of the store application with no outside file runs the type store, from its type file;"
			+ " where it runs (port, database) is not in the WAR (since aa6fa48)")
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
	@DisplayName("C1: the head office application with no outside file runs the type headoffice; no port without the outside file")
	void headOfficeWithoutOutsideFile() {
		StandardEnvironment env = startAs("headoffice", new HashMap<>());
		assertArrayEquals(new String[] { "headoffice" }, env.getActiveProfiles());
		assertEquals("HEAD_OFFICE", env.getProperty("node.type"));
		assertNull(env.getProperty("server.port"));
	}

	@Test
	@DisplayName("6.5: the application is the type: no file names it; step 8: the application alone states node.type")
	void applicationIsTheType() throws Exception {
		StandardEnvironment headOffice = startAs("headoffice", new HashMap<>());
		assertArrayEquals(new String[] { "headoffice" }, headOffice.getActiveProfiles());
		assertEquals("HEAD_OFFICE", headOffice.getProperty("node.type"));
		assertEquals("headoffice", headOffice.getProperty("spring.profiles.active"), "the key reads as before");
		Path newFile = outsideFile("new-ho.properties", "server.port=889");
		StandardEnvironment fromNewFile = startAs("headoffice", Map.of("zsretail.machine-file", newFile.toString()));
		assertArrayEquals(new String[] { "headoffice" }, fromNewFile.getActiveProfiles());
		assertEquals("889", fromNewFile.getProperty("server.port"));
		for (String type : NodeOwnership.TYPES) {
			assertEquals(1, startAs(type, new HashMap<>()).getPropertySources().stream()
					.filter(source -> source.containsProperty("node.type") && !"configurationProperties".equals(source.getName()))
					.count(), type + ": node.type from the application's defaults only, no type file states it");
		}
	}

	@Test
	@DisplayName("Step 8: a type line from any source, whatever its type, is refused: the line, where it is, remove it")
	void typeLinesRefused() throws Exception {
		Path store = outsideFile("store-1.properties", "spring.profiles.active=store");
		assertEquals("Old line no longer read: spring.profiles.active=store in the outside file " + store + ". An"
				+ " installation no longer names its type: the application is its type (this is the store application)."
				+ " Remove the line.", refusal(() -> startAs("store", Map.of("zsretail.machine-file", store.toString()))));
		Path old = outsideFile("old-store.properties", "spring.profiles.active=store", "node.type=STORE");
		assertEquals("Old lines no longer read: spring.profiles.active=store in the outside file " + old + "; node.type=STORE"
				+ " in the outside file " + old + ". An installation no longer names its type: the application is its type"
				+ " (this is the head office application). Remove the lines. It names a store: a store runs the store"
				+ " application, deploy its WAR in this Tomcat (under zsretailws).",
				refusal(() -> startAs("headoffice", Map.of("zsretail.machine-file", old.toString()))));
		Path nodeType = outsideFile("node.properties", "node.type= head_office ");
		assertEquals("Old line no longer read: node.type=head_office in the outside file " + nodeType + ". An installation"
				+ " no longer names its type: the application is its type (this is the store application). Remove the"
				+ " line. It names a head office: a head office runs the head office application, deploy its WAR in this"
				+ " Tomcat (under zsretailws).",
				refusal(() -> startAs("store", Map.of("zsretail.machine-file", nodeType.toString()))));
		assertEquals("Old line no longer read: spring.profiles.active=headoffice in the property source"
				+ " test-system-properties. An installation no longer names its type: the application is its type (this is"
				+ " the head office application). Remove the line.",
				refusal(() -> startAs("headoffice", Map.of("spring.profiles.active", "headoffice"))));
		// An old profile name, an environment variable: the same
		assertTrue(refusal(() -> startAs("store", Map.of("spring.profiles.active", "dynamics-prod")))
				.startsWith("Old line no longer read: spring.profiles.active=dynamics-prod in the property source"));
		StandardEnvironment variable = Starts.environment("store", new HashMap<>());
		variable.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						Collections.singletonMap("SPRING_PROFILES_ACTIVE", "store")));
		String message = refusal(() -> {
			SpringApplication application = new SpringApplication();
			new ConfigFileApplicationListener().postProcessEnvironment(variable, application);
			new InstallationTypeEnvironmentPostProcessor().postProcessEnvironment(variable, application);
		});
		assertTrue(message.startsWith("Old line no longer read: spring.profiles.active=store in an environment variable."),
				message);
	}

	@Test
	@DisplayName("Step 8: the application's main class in the message when there is one")
	void applicationNamed() throws Exception {
		Path file = outsideFile("ho.properties", "spring.profiles.active=headoffice");
		StandardEnvironment env = Starts.environment("headoffice", Map.of("zsretail.machine-file", file.toString()));
		SpringApplication application = new SpringApplication(HeadOfficeApplication.class);
		new MachineFileEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ConfigFileApplicationListener().postProcessEnvironment(env, application);
		String message = refusal(
				() -> new InstallationTypeEnvironmentPostProcessor().postProcessEnvironment(env, application));
		assertTrue(message.contains("(this is the head office application, HeadOfficeApplication)"), message);
	}

	@Test
	@DisplayName("A profile added another way (spring.profiles.include) is refused; without an application, one type")
	void otherProfilesRefused() {
		String message = refusal(() -> startAs("store", Map.of("spring.profiles.include", "dynamics-prod")));
		assertTrue(message.startsWith("The active profiles are [") && message.contains("dynamics-prod")
				&& message.contains("the store application runs with its profile store only. Remove what adds a profile"),
				message);
		assertTrue(refusal(() -> InstallationTypeEnvironmentPostProcessor.check(new String[0], null))
				.startsWith("The active profile is []"));
		assertTrue(refusal(() -> InstallationTypeEnvironmentPostProcessor.check(new String[] { "store-erp" }, null))
				.contains("it must be one installation type"));
		for (String type : NodeOwnership.TYPES) {
			InstallationTypeEnvironmentPostProcessor.check(new String[] { type }, null);
		}
	}

	@Test
	@DisplayName("C1: a key of the outside file wins over the type file and application.properties; a key it leaves out comes from the type file")
	void outsideKeyWins() throws Exception {
		Path file = outsideFile("ho.properties", "server.port=999",
				"spring.datasource.url=jdbc:sqlserver://localhost;databaseName=pos_ho_x", "ownership.catalogue=ERP",
				"ownership.customers=ERP", "ownership.supply=ERP");
		StandardEnvironment env = startAs("headoffice", Map.of("zsretail.machine-file", file.toString()));
		assertArrayEquals(new String[] { "headoffice" }, env.getActiveProfiles());
		assertEquals("999", env.getProperty("server.port"));
		assertEquals("jdbc:sqlserver://localhost;databaseName=pos_ho_x", env.getProperty("spring.datasource.url"));
		assertEquals("ERP", env.getProperty("ownership.catalogue"));
		assertEquals("HEAD_OFFICE", env.getProperty("node.type"), "from the application");
		assertEquals("LOCAL", env.getProperty("ownership.promotions"), "left out: from the type file");
		assertEquals("com.microsoft.sqlserver.jdbc.SQLServerDriver", env.getProperty("spring.datasource.driverClassName"),
				"left out: from application.properties");
		assertEquals(file.toString(), env.getProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY));
		assertTrue(NodeOwnership.isHeadOfficeErpSet(env));

		Path noType = outsideFile("store.properties", "server.port=557");
		StandardEnvironment store = start(Map.of("zsretail.machine-file", noType.toString()));
		assertArrayEquals(new String[] { "store" }, store.getActiveProfiles(), "the store application");
		assertEquals("557", store.getProperty("server.port"));
	}

	@Test
	@DisplayName("Loaded above the configuration files and below the system properties; the path read is kept")
	void propertySource() throws Exception {
		Path file = outsideFile("store-x.properties", "ownership.loyalty=HEAD_OFFICE", "server.port=557");
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
		assertEquals("HEAD_OFFICE", env.getProperty("ownership.loyalty"));
		assertEquals(file.toString(), env.getProperty("zsretail.machine-file.loaded"));
	}

	@Test
	@DisplayName("Every outside file of configs/ and the model name no type and never application.standalone; the name gives the application")
	void deployFolder() {
		List<String> files = Installations.machineFiles();
		assertTrue(files.size() >= 9, files.toString());
		List<String> headOffices = new ArrayList<>();
		for (String file : files) {
			java.util.Properties properties = Installations.file(Installations.MACHINES.resolve(file));
			assertNull(properties.getProperty("spring.profiles.active"), file);
			assertNull(properties.getProperty("node.type"), file);
			assertNull(properties.getProperty("application.standalone"), file);
			if ("headoffice".equals(Installations.typeOf(file))) {
				headOffices.add(file);
			}
		}
		assertEquals(Arrays.asList("customers/happyness-headoffice.properties", "customers/zen-headoffice.properties",
				"dev/headoffice-erp.properties",
				"dev/headoffice.properties",
				"happyness-from-scratch/headoffice.properties", "rehearsal/headoffice.properties"), headOffices);
		assertEquals("headoffice", Installations.typeOf("local/test_happyness_ho.properties"));
		assertEquals("store", Installations.typeOf("local/test_happyness_store.properties"));
		java.util.Properties model = Installations.file(Installations.MACHINES.resolve("machine-model.properties"));
		assertNull(model.getProperty("spring.profiles.active"));
		assertNull(model.getProperty("node.type"));
	}

	@Test
	@DisplayName("C1: the startup summary names the type, the database, the owners, the sales, the NAV address and the outside file, never a password")
	void startupSummary() {
		org.springframework.mock.env.MockEnvironment env = Installations.machine("dev/store-test-nav.properties");
		env.setProperty(MachineFileEnvironmentPostProcessor.LOADED_KEY, "D:/configs/dev/store-test-nav.properties");
		String line = InstallationSummary.summary(env, NodeOwnership.resolve(env));
		assertEquals("Installation: type STORE, database pos_db_test, catalogue ERP, customers ERP, promotions LOCAL,"
				+ " loyalty LOCAL, supply ERP, sales to [ERP], ERP connector emtop, NAV http://192.168.10.166:24/test4/ODataV4,"
				+ " outside file D:/configs/dev/store-test-nav.properties", line);
		org.springframework.mock.env.MockEnvironment plain = Installations.type("store");
		assertTrue(InstallationSummary.summary(plain, NodeOwnership.resolve(plain))
				.endsWith("sales to nowhere, ERP connector none, no outside file"));
		assertTrue(!line.contains("password"));
	}
}
