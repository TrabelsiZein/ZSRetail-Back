package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.support.Starts;

/**
 * Code cleanup, step 1: erp.connector, one line that chooses the ERP connector (ErpConnectorEnvironmentPostProcessor).
 * Code cleanup step 8: the only line. Each value, absent, unknown, the old enabled lines refused wherever they are,
 * every outside file of configs/ starting, and the startup summary. Starts run the way Spring Boot runs them (the
 * post-processors around ConfigFileApplicationListener, on the real application.properties and type files).
 */
class ErpConnectorTest {

	private static final String CHOSEN_BY = " The ERP connector is chosen by erp.connector only (none | emtop | happyness;"
			+ " no line: none).";

	@TempDir
	Path folder;

	private static String refusal(Runnable call) {
		return assertThrows(IllegalStateException.class, call::run).getMessage();
	}

	/** A start: the system properties given (above everything, like -D), the outside file, the configuration files. */
	private static StandardEnvironment start(Map<String, Object> systemProperties) {
		return start(Starts.environment(Starts.applicationOf(systemProperties), systemProperties));
	}

	private static StandardEnvironment start(StandardEnvironment env) {
		SpringApplication application = new SpringApplication();
		new MachineFileEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ConfigFileApplicationListener().postProcessEnvironment(env, application);
		new InstallationTypeEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ErpConnectorEnvironmentPostProcessor().postProcessEnvironment(env, application);
		return env;
	}

	private Path outsideFile(String... lines) {
		Path file = folder.resolve("machine.properties");
		try {
			Files.write(file, Arrays.asList(lines));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		return file;
	}

	private StandardEnvironment startWith(Map<String, Object> systemProperties, String... outsideLines) {
		Map<String, Object> properties = new HashMap<>(systemProperties);
		properties.put(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY, outsideFile(outsideLines).toString());
		return start(properties);
	}

	private StandardEnvironment startWith(String... outsideLines) {
		return startWith(Collections.emptyMap(), outsideLines);
	}

	@Test
	@DisplayName("Each value (trimmed, any case) is the connector in effect; absent is none; nothing else is added")
	void eachValue() {
		StandardEnvironment nav = startWith("erp.connector= emtop ");
		assertEquals("emtop", ErpConnectorEnvironmentPostProcessor.inEffect(nav));
		assertEquals("happyness",
				ErpConnectorEnvironmentPostProcessor.inEffect(startWith("spring.profiles.active=headoffice", "erp.connector=Happyness")));
		assertEquals("none", ErpConnectorEnvironmentPostProcessor.inEffect(startWith("erp.connector=NONE")));
		assertEquals("none", ErpConnectorEnvironmentPostProcessor.inEffect(startWith("server.port=1")));
		List<String> names = new ArrayList<>();
		nav.getPropertySources().forEach(source -> names.add(source.getName()));
		assertFalse(names.contains("erpConnector"), "no translation any more: " + names);
	}

	@Test
	@DisplayName("Unknown value: refused naming the key and the values")
	void unknown() {
		assertEquals("Invalid value ' nav ' for property erp.connector: none | emtop | happyness.",
				refusal(() -> ErpConnectorEnvironmentPostProcessor.inEffect(new MockEnvironment()
						.withProperty("erp.connector", " nav "))));
		assertEquals("Invalid value '' for property erp.connector: none | emtop | happyness.",
				refusal(() -> startWith("erp.connector=")));
	}

	@Test
	@DisplayName("An old enabled line, whatever its value, refuses the startup: the line, where it is, what to write")
	void oldLinesRefused() {
		Path file = outsideFile();
		assertEquals("Old line no longer read: erp.navpospages.enabled=true in the outside file " + file
				+ ": write erp.connector=happyness instead." + CHOSEN_BY,
				refusal(() -> startWith("spring.profiles.active=headoffice", "erp.navpospages.enabled=true")));
		assertEquals("Old line no longer read: erp.dynamicsnav.enabled=false in the outside file " + file
				+ ": remove the line." + CHOSEN_BY, refusal(() -> startWith("erp.dynamicsnav.enabled=false")));
		// Never ignored, even beside erp.connector and agreeing with it
		assertEquals("Old line no longer read: erp.dynamicsnav.enabled=TRUE in the outside file " + file
				+ ": write erp.connector=emtop instead." + CHOSEN_BY,
				refusal(() -> startWith("erp.connector=emtop", "erp.dynamicsnav.enabled= TRUE ")));
		// Several, in several sources: all of them, the source of each
		assertEquals("Old lines no longer read: erp.dynamicsnav.enabled=false in the outside file " + file
				+ ": remove the line; erp.navpospages.enabled=true in the property source test-system-properties: write"
				+ " erp.connector=happyness instead; erp.navpospages.enabled=false in the outside file " + file
				+ ": remove the line." + CHOSEN_BY,
				refusal(() -> startWith(Collections.singletonMap("erp.navpospages.enabled", " true"),
						"erp.dynamicsnav.enabled=false", "erp.navpospages.enabled=false")));
	}

	@Test
	@DisplayName("An old enabled line as an environment variable, or a configuration file, is refused the same way")
	void oldLineInOtherSources() throws IOException {
		StandardEnvironment env = Starts.environment("store", new HashMap<>());
		env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						Collections.singletonMap("ERP_DYNAMICSNAV_ENABLED", "true")));
		assertEquals("Old line no longer read: erp.dynamicsnav.enabled=true in an environment variable: write"
				+ " erp.connector=emtop instead." + CHOSEN_BY, refusal(() -> start(env)));

		Path config = folder.resolve("config");
		Files.createDirectories(config);
		Files.write(config.resolve("application.properties"), Collections.singletonList("erp.navpospages.enabled=false"));
		String message = refusal(() -> start(Collections.singletonMap("spring.config.additional-location",
				config.toUri().toString())));
		assertTrue(message.startsWith("Old line no longer read: erp.navpospages.enabled=false in the configuration file "),
				message);
		assertTrue(message.contains("application.properties: remove the line."), message);
	}

	@Test
	@DisplayName("Any key of an old block refuses the startup, each with its new name, source by source")
	void oldKeysRefused() {
		Path file = outsideFile();
		assertEquals("Old keys no longer read, in the outside file " + file + ": erp.dynamicsnav.base-url is now"
				+ " erp.emtop.base-url, erp.dynamicsnav.company is now erp.emtop.company. Rename them: the keys of a"
				+ " connector are erp.<its name>.*.",
				refusal(() -> startWith("erp.connector=emtop", "erp.dynamicsnav.company=X",
						"erp.dynamicsnav.base-url=http://nav")));
		// An enabled line and keys in two sources: the enabled line first, then each source
		assertEquals("Old line no longer read: erp.navpospages.enabled=true in the outside file " + file
				+ ": write erp.connector=happyness instead." + CHOSEN_BY + " Old keys no longer read, in the property"
				+ " source test-system-properties: erp.navpospages.password is now erp.happyness.password. Old keys no"
				+ " longer read, in the outside file " + file + ": erp.navpospages.base-url is now erp.happyness.base-url,"
				+ " erp.navpospages.invoices.years is now erp.happyness.invoices.years. Rename them: the keys of a"
				+ " connector are erp.<its name>.*.",
				refusal(() -> startWith(Collections.singletonMap("erp.navpospages.password", "p"),
						"spring.profiles.active=headoffice", "erp.navpospages.enabled=true",
						"erp.navpospages.base-url=http://bc", "erp.navpospages.invoices.years=2021")));
		// An environment variable, named as written
		StandardEnvironment env = Starts.environment("store", new HashMap<>());
		env.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
						Collections.singletonMap("ERP_DYNAMICSNAV_BASEURL", "http://nav")));
		assertEquals("Old keys no longer read, in an environment variable: ERP_DYNAMICSNAV_BASEURL is now"
				+ " ERP_EMTOP_BASEURL. Rename them: the keys of a connector are erp.<its name>.*.",
				refusal(() -> start(env)));
	}

	@Test
	@DisplayName("The new keys are read: erp.emtop.* gives the summary address")
	void newKeys() {
		StandardEnvironment env = startWith("erp.connector=emtop", "erp.emtop.base-url=http://nav.test:7048/NAV/ODataV4");
		assertEquals("NAV http://nav.test:7048/NAV/ODataV4", ErpConnectorEnvironmentPostProcessor.summaryAddress(env));
	}

	@Test
	@DisplayName("Every outside file of configs/ starts; each of configs/local, upgraded as docs/deploy-note.md says")
	void existingFilesStart() throws IOException {
		List<Path> files = new ArrayList<>();
		Installations.machineFiles().forEach(file -> files.add(Installations.MACHINES.resolve(file)));
		files.add(Installations.MACHINES.resolve("machine-model.properties"));
		for (String local : Installations.configFiles()) {
			Path upgraded = folder.resolve(Installations.CONFIGS.resolve(local).getFileName());
			try (OutputStream out = Files.newOutputStream(upgraded)) {
				Installations.upgraded(Installations.file(Installations.CONFIGS.resolve(local))).store(out, null);
			}
			files.add(upgraded);
		}
		assertTrue(files.size() > 10, files.toString());
		for (Path file : files) {
			StandardEnvironment env = start(Collections.singletonMap(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY,
					file.toAbsolutePath().toString()));
			Properties lines = Installations.file(file);
			assertEquals(lines.getProperty("erp.connector", "none").trim(), ErpConnectorEnvironmentPostProcessor.inEffect(env),
					file.toString());
		}
	}

	@Test
	@DisplayName("Startup summary: names the connector in effect")
	void summary() {
		MockEnvironment nav = Installations.machine("dev/store-a-erp.properties");
		String line = InstallationSummary.summary(nav, NodeOwnership.resolve(nav));
		assertTrue(line.contains("ERP connector emtop, NAV "), line);

		MockEnvironment plain = Installations.machine("dev/store-b.properties");
		assertTrue(InstallationSummary.summary(plain, NodeOwnership.resolve(plain)).contains("ERP connector none, "));

		StandardEnvironment pages = startWith("spring.profiles.active=headoffice", "erp.connector=happyness",
				"ownership.catalogue=ERP");
		line = InstallationSummary.summary(pages, NodeOwnership.resolve(pages));
		assertTrue(line.contains("ERP connector happyness, "), line);
		assertFalse(line.contains("NAV "), line);
	}
}
