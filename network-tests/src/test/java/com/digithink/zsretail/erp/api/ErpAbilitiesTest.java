package com.digithink.zsretail.erp.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ErpConnectorEnvironmentPostProcessor;
import com.digithink.zsretail.core.config.InstallationTypeEnvironmentPostProcessor;
import com.digithink.zsretail.core.config.MachineFileEnvironmentPostProcessor;
import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.support.Starts;

/**
 * Code cleanup, step 2, part 1: what each connector can do (ErpAbilities), read from the environment whichever way the
 * connector was chosen, and every file of configs/ giving the abilities its old lines gave. Code cleanup step 8:
 * erp.connector is the only line (the old ones are refused, ErpConnectorTest); each file says it.
 */
class ErpAbilitiesTest {

	@TempDir
	Path folder;

	@Test
	@DisplayName("Each connector name: its abilities; an unknown name is refused")
	void byName() {
		assertEquals(EnumSet.noneOf(ErpAbility.class), ErpAbilities.of("none"));
		assertEquals(EnumSet.noneOf(ErpAbility.class), ErpAbilities.of("emtop"));
		assertEquals(EnumSet.of(ErpAbility.HEAD_OFFICE_STOCK_POINTS, ErpAbility.SUPPLY_INVOICES),
				ErpAbilities.of("happyness"));
		// Item variants (ZEN), step 8: the points de stock; its supply invoices come at step 9
		assertEquals(EnumSet.of(ErpAbility.HEAD_OFFICE_STOCK_POINTS), ErpAbilities.of("zen"));
		assertThrows(IllegalArgumentException.class, () -> ErpAbilities.of("sap"));
		assertEquals(Collections.singletonList("happyness"), ErpAbilities.connectorsWith(ErpAbility.SUPPLY_INVOICES));
		assertEquals(Arrays.asList("happyness", "zen"), ErpAbilities.connectorsWith(ErpAbility.HEAD_OFFICE_STOCK_POINTS));
	}

	/** A start the way Spring Boot runs it, the outside file holding these lines. */
	private StandardEnvironment start(String... outsideLines) throws IOException {
		Path file = folder.resolve("machine.properties");
		Files.write(file, Arrays.asList(outsideLines));
		Map<String, Object> systemProperties = Collections.singletonMap(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY,
				file.toString());
		StandardEnvironment env = Starts.environment(Starts.applicationOf(systemProperties), systemProperties);
		SpringApplication application = new SpringApplication();
		new MachineFileEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ConfigFileApplicationListener().postProcessEnvironment(env, application);
		new InstallationTypeEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ErpConnectorEnvironmentPostProcessor().postProcessEnvironment(env, application);
		return env;
	}

	@Test
	@DisplayName("From the environment: erp.connector, trimmed and any case, gives the connector's abilities")
	void fromEnvironment() throws IOException {
		for (String[] lines : Arrays.asList(new String[] { "erp.connector=happyness" },
				new String[] { "erp.connector= HAPPYNESS " })) {
			StandardEnvironment env = start(lines);
			assertEquals("happyness", ErpAbilities.connectorInEffect(env), lines[0]);
			assertTrue(ErpAbilities.hasConnector(env), lines[0]);
			assertTrue(ErpAbilities.has(env, ErpAbility.HEAD_OFFICE_STOCK_POINTS), lines[0]);
			assertTrue(ErpAbilities.has(env, ErpAbility.SUPPLY_INVOICES), lines[0]);
		}
		for (String[] lines : Arrays.asList(new String[] { "erp.connector=Emtop" },
				new String[] { "erp.connector=emtop" })) {
			StandardEnvironment env = start(lines);
			assertEquals("emtop", ErpAbilities.connectorInEffect(env), lines[0]);
			assertTrue(ErpAbilities.hasConnector(env), lines[0]);
			assertFalse(ErpAbilities.has(env, ErpAbility.HEAD_OFFICE_STOCK_POINTS), lines[0]);
			assertFalse(ErpAbilities.has(env, ErpAbility.SUPPLY_INVOICES), lines[0]);
		}
		for (String[] lines : Arrays.asList(new String[] { "erp.connector=none" }, new String[] { "server.port=1" })) {
			StandardEnvironment env = start(lines);
			assertEquals("none", ErpAbilities.connectorInEffect(env), lines[0]);
			assertFalse(ErpAbilities.hasConnector(env), lines[0]);
			for (ErpAbility ability : ErpAbility.values()) {
				assertFalse(ErpAbilities.has(env, ability), lines[0] + " " + ability);
			}
		}
	}

	/** The connector a file's erp.connector line names, none without one. */
	private static String lineOf(PropertyResolver env) {
		String value = env.getProperty("erp.connector");
		return value == null ? "none" : value.trim().toLowerCase();
	}

	@Test
	@DisplayName("Every file of configs/: the abilities its erp.connector line gives")
	void existingFiles() {
		List<String> machines = Installations.machineFiles();
		List<String> configs = Installations.configFiles();
		assertTrue(machines.size() + configs.size() > 10);
		int withPages = 0;
		int withNav = 0;
		for (int i = 0; i < machines.size() + configs.size(); i++) {
			String name = i < machines.size() ? machines.get(i) : configs.get(i - machines.size());
			MockEnvironment env = i < machines.size() ? Installations.machine(name) : Installations.config(name);
			boolean pages = "happyness".equals(lineOf(env));
			boolean nav = "emtop".equals(lineOf(env));
			withPages += pages ? 1 : 0;
			withNav += nav ? 1 : 0;
			assertEquals(pages, ErpAbilities.has(env, ErpAbility.HEAD_OFFICE_STOCK_POINTS), name);
			assertEquals(pages, ErpAbilities.has(env, ErpAbility.SUPPLY_INVOICES), name);
			assertEquals(nav || pages, ErpAbilities.hasConnector(env), name);
		}
		assertEquals(1, withPages, "the Happyness head office of configs/customers");
		assertEquals(4, withNav, "the NAV files of configs/dev and configs/customers");
	}
}
