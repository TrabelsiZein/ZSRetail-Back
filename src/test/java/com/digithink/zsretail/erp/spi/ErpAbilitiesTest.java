package com.digithink.zsretail.erp.spi;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.config.ErpConnectorEnvironmentPostProcessor;
import com.digithink.zsretail.config.InstallationTypeEnvironmentPostProcessor;
import com.digithink.zsretail.config.MachineFileEnvironmentPostProcessor;
import com.digithink.zsretail.support.Installations;

/**
 * Code cleanup, step 2, part 1: what each connector can do (ErpAbilities), read from the environment whichever way the
 * connector was chosen (erp.connector or the old enabled lines), and every file of deploy/ and configs/ giving the
 * abilities its old lines gave.
 */
class ErpAbilitiesTest {

	@TempDir
	Path folder;

	@Test
	@DisplayName("Each connector name: its abilities; an unknown name is refused")
	void byName() {
		assertEquals(EnumSet.noneOf(ErpAbility.class), ErpAbilities.of("none"));
		assertEquals(EnumSet.noneOf(ErpAbility.class), ErpAbilities.of("dynamicsnav"));
		assertEquals(EnumSet.of(ErpAbility.CATALOGUE_BY_STOCK_POINTS, ErpAbility.SUPPLY_INVOICES),
				ErpAbilities.of("navpospages"));
		assertThrows(IllegalArgumentException.class, () -> ErpAbilities.of("sap"));
		assertEquals(Collections.singletonList("navpospages"), ErpAbilities.connectorsWith(ErpAbility.SUPPLY_INVOICES));
		assertEquals(Collections.singletonList("navpospages"),
				ErpAbilities.connectorsWith(ErpAbility.CATALOGUE_BY_STOCK_POINTS));
	}

	/** A start the way Spring Boot runs it, the outside file holding these lines. */
	private StandardEnvironment start(String... outsideLines) throws IOException {
		Path file = folder.resolve("machine.properties");
		Files.write(file, Arrays.asList(outsideLines));
		StandardEnvironment env = new StandardEnvironment();
		env.getPropertySources().addFirst(new MapPropertySource("test-system-properties",
				Collections.singletonMap(MachineFileEnvironmentPostProcessor.MACHINE_FILE_KEY, file.toString())));
		SpringApplication application = new SpringApplication();
		new MachineFileEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ConfigFileApplicationListener().postProcessEnvironment(env, application);
		new InstallationTypeEnvironmentPostProcessor().postProcessEnvironment(env, application);
		new ErpConnectorEnvironmentPostProcessor().postProcessEnvironment(env, application);
		return env;
	}

	@Test
	@DisplayName("From the environment: erp.connector or the old lines give the same abilities")
	void fromEnvironment() throws IOException {
		for (String[] lines : Arrays.asList(new String[] { "erp.connector=navpospages" },
				new String[] { "erp.navpospages.enabled=true" })) {
			StandardEnvironment env = start(lines);
			assertEquals("navpospages", ErpAbilities.connectorInEffect(env), lines[0]);
			assertTrue(ErpAbilities.hasConnector(env), lines[0]);
			assertTrue(ErpAbilities.has(env, ErpAbility.CATALOGUE_BY_STOCK_POINTS), lines[0]);
			assertTrue(ErpAbilities.has(env, ErpAbility.SUPPLY_INVOICES), lines[0]);
		}
		for (String[] lines : Arrays.asList(new String[] { "erp.connector=DynamicsNav" },
				new String[] { "erp.dynamicsnav.enabled=true" })) {
			StandardEnvironment env = start(lines);
			assertEquals("dynamicsnav", ErpAbilities.connectorInEffect(env), lines[0]);
			assertTrue(ErpAbilities.hasConnector(env), lines[0]);
			assertFalse(ErpAbilities.has(env, ErpAbility.CATALOGUE_BY_STOCK_POINTS), lines[0]);
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

	private static boolean oldLineTrue(PropertyResolver env, String key) {
		String value = env.getProperty(key);
		return value != null && "true".equalsIgnoreCase(value.trim());
	}

	@Test
	@DisplayName("Every file of deploy/ and configs/: the abilities its old lines give")
	void existingFiles() {
		List<String> machines = Installations.machineFiles();
		List<String> configs = Installations.configFiles();
		assertTrue(machines.size() + configs.size() > 10);
		for (int i = 0; i < machines.size() + configs.size(); i++) {
			String name = i < machines.size() ? machines.get(i) : configs.get(i - machines.size());
			MockEnvironment env = i < machines.size() ? Installations.machine(name) : Installations.config(name);
			boolean pages = oldLineTrue(env, "erp.navpospages.enabled");
			boolean nav = oldLineTrue(env, "erp.dynamicsnav.enabled");
			assertEquals(pages, ErpAbilities.has(env, ErpAbility.CATALOGUE_BY_STOCK_POINTS), name);
			assertEquals(pages, ErpAbilities.has(env, ErpAbility.SUPPLY_INVOICES), name);
			assertEquals(nav || pages, ErpAbilities.hasConnector(env), name);
		}
	}
}
