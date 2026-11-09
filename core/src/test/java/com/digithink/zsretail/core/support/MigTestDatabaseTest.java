package com.digithink.zsretail.core.support;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * Boot 4 migration, step 1.2 (decision 6): the guards of the throwaway database, without a server: another database or
 * another machine is refused before any connection; a server that does not answer skips, or fails when the migration
 * requires the startup tests. The port is one nothing listens on, so no SQL Server has to be stopped to prove it.
 */
class MigTestDatabaseTest {

	private static final String SECRET = "the-password-of-this-test";

	@TempDir
	Path configs;

	/** The flag of the run, put back after each test: it may be on for the whole suite. */
	private String required;

	@BeforeEach
	void notRequired() {
		required = System.getProperty(MigTestDatabase.REQUIRED_KEY);
		System.clearProperty(MigTestDatabase.REQUIRED_KEY);
	}

	@AfterEach
	void asTheRunSet() {
		if (required == null) {
			System.clearProperty(MigTestDatabase.REQUIRED_KEY);
		} else {
			System.setProperty(MigTestDatabase.REQUIRED_KEY, required);
		}
	}

	private Properties settings() throws Exception {
		Files.write(configs.resolve("machine.properties"),
				("spring.datasource.password=" + SECRET + "\n").getBytes(StandardCharsets.UTF_8));
		Properties settings = new Properties();
		settings.setProperty("migtest.server", "localhost");
		settings.setProperty("migtest.port", "1");
		settings.setProperty("migtest.database", MigTestDatabase.NAME);
		settings.setProperty("migtest.username", "sa");
		settings.setProperty("migtest.options", "encrypt=true;trustServerCertificate=true;loginTimeout=1");
		settings.setProperty("migtest.password-file", "machine.properties");
		return settings;
	}

	@Test
	@DisplayName("Another database is refused, whatever the settings say")
	void anotherDatabase() throws Exception {
		Properties settings = settings();
		settings.setProperty("migtest.database", "pos_db_prod");
		IllegalStateException refused = assertThrows(IllegalStateException.class, () -> new MigTestDatabase(settings, configs));
		assertTrue(refused.getMessage().contains("pos_db_prod"), refused.getMessage());
	}

	@Test
	@DisplayName("Another machine is refused, whatever the settings say")
	void anotherMachine() throws Exception {
		Properties settings = settings();
		settings.setProperty("migtest.server", "192.168.10.156");
		assertThrows(IllegalStateException.class, () -> new MigTestDatabase(settings, configs));
	}

	@Test
	@DisplayName("A server that does not answer skips, with a message that says why and that this is not green")
	void unreachableSkips() throws Exception {
		MigTestDatabase database = new MigTestDatabase(settings(), configs);
		TestAbortedException skipped = assertThrows(TestAbortedException.class, database::recreate);
		assertTrue(skipped.getMessage().contains("not green"), skipped.getMessage());
		assertFalse(skipped.getMessage().contains(SECRET), skipped.getMessage());
	}

	@Test
	@DisplayName("With the startup tests required, a server that does not answer fails")
	void unreachableFailsWhenRequired() throws Exception {
		System.setProperty(MigTestDatabase.REQUIRED_KEY, "true");
		MigTestDatabase database = new MigTestDatabase(settings(), configs);
		AssertionError failed = assertThrows(AssertionError.class, database::recreate);
		assertFalse(failed.getMessage().contains(SECRET), failed.getMessage());
	}

	private static StandardEnvironment environment(String key, String value) {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().addFirst(new MapPropertySource("test", Collections.singletonMap(key, value)));
		return environment;
	}

	@Test
	@DisplayName("A start with the address of a real system is refused before any bean exists, whatever its source")
	void addressesOfRealSystems() {
		for (String key : new String[] { "erp.emtop.base-url", "ERP_EMTOP_BASE_URL", "erp.zen.products.url", "headoffice.url" }) {
			StandardEnvironment environment = environment(key, "http://192.168.10.156:7048/BC");
			IllegalStateException refused = assertThrows(IllegalStateException.class,
					() -> MigTestApplication.nothingCanLeave(environment), key);
			assertTrue(refused.getMessage().contains(key), refused.getMessage());
		}
	}

	@Test
	@DisplayName("The closed local port and an empty address start; other keys are not addresses")
	void closedPort() {
		MigTestApplication.nothingCanLeave(environment("erp.emtop.base-url", MigTestApplication.CLOSED_PORT + "/test/ODataV4"));
		MigTestApplication.nothingCanLeave(environment("headoffice.url", ""));
		MigTestApplication.nothingCanLeave(environment("erp.emtop.company", "005-SCPC"));
		MigTestApplication.nothingCanLeave(environment("spring.datasource.url", "jdbc:sqlserver://localhost"));
	}

	@Test
	@DisplayName("No machine file: skipped, naming the file, not a null password sent to a server")
	void noMachineFile() throws Exception {
		Properties settings = settings();
		settings.setProperty("migtest.password-file", "absent.properties");
		TestAbortedException skipped = assertThrows(TestAbortedException.class, new MigTestDatabase(settings, configs)::recreate);
		assertTrue(skipped.getMessage().contains("absent.properties"), skipped.getMessage());
	}
}
