package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigFileApplicationListener;
import org.springframework.core.env.StandardEnvironment;

import com.digithink.zsretail.connector.happyness.config.NavPosPagesStartupCheck;
import com.digithink.zsretail.support.Installations;
import com.digithink.zsretail.support.Starts;

/**
 * Code cleanup step 8: the outside file of each kind of installation in 2.2.2, an EMTOP store, a Happyness head office
 * and a Happyness store (their lines as configs/customers/erp-prod.properties and configs/local had them before step 8,
 * the values made up), is refused with one message naming every old line, where it is and what to write; the file
 * upgraded as docs/deploy-note.md says ("Upgrade from 2.2.2 to 2.3.0: the outside file", Installations.upgraded) starts, with the
 * same values under the new keys (a dead key dropped: OldLines). Starts run the way Spring Boot runs them (the
 * post-processors around ConfigFileApplicationListener, on the real application.properties and type files).
 */
class OutsideFileUpgradeTest {

	static final String[] EMTOP_STORE = { "spring.profiles.active=store", "node.type=STORE", "ownership.catalogue=ERP",
			"ownership.customers=ERP", "ownership.promotions=LOCAL", "ownership.loyalty=LOCAL", "ownership.supply=ERP",
			"sales.upstream=ERP", "erp.dynamicsnav.enabled=true", "erp.dynamicsnav.base-url=http://nav.test:7048/NAV/ODataV4",
			"erp.dynamicsnav.company=COMPANY", "erp.dynamicsnav.domain=DOMAIN", "erp.dynamicsnav.username=POS",
			"erp.dynamicsnav.password=secret", "erp.sync.enabled=true", "erp.sync.scheduler.delay=10000" };

	static final String[] HAPPYNESS_HEAD_OFFICE = { "spring.profiles.active=headoffice", "headoffice.stock.enabled=false",
			"ownership.catalogue=ERP", "headoffice.supply.source=ERP", "erp.navpospages.enabled=true", "erp.sync.enabled=true",
			"erp.navpospages.base-url=http://bc.test:7048/BC140/ODataV4", "erp.navpospages.company=HAPPYNESS",
			"erp.navpospages.domain=DOMAIN", "erp.navpospages.username=user", "erp.navpospages.password=secret",
			"erp.navpospages.location-code=FRANCHISE", "erp.navpospages.default-vat=19",
			"erp.navpospages.price-includes-vat=true", "erp.navpospages.invoices.years=2021",
			"erp.navpospages.invoices.seller-name=Happyness" };

	static final String[] HAPPYNESS_STORE = { "spring.profiles.active=store", "node.type=STORE",
			"ownership.catalogue=HEAD_OFFICE", "ownership.customers=LOCAL", "ownership.promotions=HEAD_OFFICE",
			"ownership.loyalty=HEAD_OFFICE", "ownership.supply=HEAD_OFFICE", "sales.upstream=HEAD_OFFICE",
			"erp.dynamicsnav.enabled=false", "headoffice.url=http://localhost:888/zsretail/api", "headoffice.api-key=key" };

	static final String CHOSEN_BY = " The ERP connector is chosen by erp.connector only (none | emtop | happyness | zen;"
			+ " no line: none).";
	static final String RENAME = " Rename them: the keys of a connector are erp.<its name>.*.";

	@TempDir
	Path folder;

	private Path file(String name, String... lines) throws IOException {
		Path file = folder.resolve(name);
		Files.write(file, Arrays.asList(lines));
		return file;
	}

	/** A start of the application of the file (by its name), with that outside file. */
	private static StandardEnvironment start(Path file) {
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

	private static String refusal(Path file) {
		return assertThrows(IllegalStateException.class, () -> start(file)).getMessage();
	}

	/** The file upgraded as docs/deploy-note.md says, written beside it. */
	private Path upgraded(Path file) throws IOException {
		Path upgraded = folder.resolve("upgraded-" + file.getFileName());
		try (OutputStream out = Files.newOutputStream(upgraded)) {
			Installations.upgraded(Installations.file(file)).store(out, null);
		}
		return upgraded;
	}

	/** Every line of the old file, but the type and enabled lines, has its value under its new key. */
	private static void sameValues(String[] oldLines, StandardEnvironment env) {
		for (String line : oldLines) {
			String key = line.substring(0, line.indexOf('='));
			if (key.equals("spring.profiles.active") || key.equals("node.type") || key.endsWith(".enabled")
					&& key.startsWith("erp.") && !key.startsWith("erp.sync.")
					|| OldLines.deadKeys().contains(key)) {
				continue;
			}
			String now = key.replace("erp.dynamicsnav.", "erp.emtop.").replace("erp.navpospages.", "erp.happyness.");
			assertEquals(line.substring(line.indexOf('=') + 1), env.getProperty(now), now);
		}
	}

	@Test
	@DisplayName("An EMTOP store: refused naming its type lines, its enabled line and its five NAV keys; upgraded, it starts")
	void emtopStore() throws IOException {
		Path file = file("store.properties", EMTOP_STORE);
		assertEquals("Old lines no longer read: spring.profiles.active=store in the outside file " + file + "; node.type=STORE"
				+ " in the outside file " + file + ". An installation no longer names its type: the application is its type"
				+ " (this is the store application). Remove the lines. Old line no longer read: erp.dynamicsnav.enabled=true"
				+ " in the outside file " + file + ": write erp.connector=emtop instead." + CHOSEN_BY + " Old keys no longer"
				+ " read, in the outside file " + file + ": erp.dynamicsnav.base-url is now erp.emtop.base-url,"
				+ " erp.dynamicsnav.company is now erp.emtop.company, erp.dynamicsnav.domain is now erp.emtop.domain,"
				+ " erp.dynamicsnav.password is now erp.emtop.password, erp.dynamicsnav.username is now erp.emtop.username."
				+ RENAME, refusal(file));
		Path upgraded = upgraded(file);
		assertEquals(new TreeSet<>(Arrays.asList("ownership.catalogue", "ownership.customers", "ownership.promotions",
				"ownership.loyalty", "ownership.supply", "sales.upstream", "erp.connector", "erp.emtop.base-url",
				"erp.emtop.company", "erp.emtop.domain", "erp.emtop.username", "erp.emtop.password", "erp.sync.enabled",
				"erp.sync.scheduler.delay")), new TreeSet<>(Installations.file(upgraded).stringPropertyNames()));
		StandardEnvironment env = start(upgraded);
		assertEquals("emtop", ErpConnectorEnvironmentPostProcessor.inEffect(env));
		assertEquals("STORE", env.getProperty("node.type"));
		assertEquals("NAV http://nav.test:7048/NAV/ODataV4", ErpConnectorEnvironmentPostProcessor.summaryAddress(env));
		sameValues(EMTOP_STORE, env);
	}

	@Test
	@DisplayName("A Happyness head office: refused naming its type line, its enabled line, its eight keys and its two dead keys; upgraded, it starts")
	void happynessHeadOffice() throws IOException {
		Path file = file("happyness_ho.properties", HAPPYNESS_HEAD_OFFICE);
		assertEquals("Old line no longer read: spring.profiles.active=headoffice in the outside file " + file + ". An"
				+ " installation no longer names its type: the application is its type (this is the head office"
				+ " application). Remove the line. Old line no longer read: erp.navpospages.enabled=true in the outside file "
				+ file + ": write erp.connector=happyness instead." + CHOSEN_BY + " Old keys no longer read, in the outside"
				+ " file " + file + ": erp.navpospages.base-url is now erp.happyness.base-url, erp.navpospages.company is"
				+ " now erp.happyness.company, erp.navpospages.default-vat is now erp.happyness.default-vat,"
				+ " erp.navpospages.domain is now erp.happyness.domain, erp.navpospages.invoices.seller-name is now"
				+ " erp.happyness.invoices.seller-name, erp.navpospages.password is now"
				+ " erp.happyness.password, erp.navpospages.price-includes-vat is now erp.happyness.price-includes-vat,"
				+ " erp.navpospages.username is now erp.happyness.username." + RENAME + " Lines no longer read: in the"
				+ " outside file " + file + ", erp.navpospages.invoices.years, erp.navpospages.location-code. Remove the"
				+ " lines: nothing reads them any more.", refusal(file));
		StandardEnvironment env = start(upgraded(file));
		assertEquals("happyness", ErpConnectorEnvironmentPostProcessor.inEffect(env));
		assertEquals("HEAD_OFFICE", env.getProperty("node.type"));
		NavPosPagesStartupCheck.check(env); // the connector's own checks pass on the new keys
		assertEquals(null, env.getProperty("erp.happyness.location-code"), "a dead key: dropped, not renamed");
		assertEquals(null, env.getProperty("erp.happyness.invoices.years"));
		sameValues(HAPPYNESS_HEAD_OFFICE, env);
	}

	@Test
	@DisplayName("A Happyness store: refused naming its type lines and its enabled line false; upgraded, it starts")
	void happynessStore() throws IOException {
		Path file = file("happyness_store1.properties", HAPPYNESS_STORE);
		assertEquals("Old lines no longer read: spring.profiles.active=store in the outside file " + file + "; node.type=STORE"
				+ " in the outside file " + file + ". An installation no longer names its type: the application is its type"
				+ " (this is the store application). Remove the lines. Old line no longer read: erp.dynamicsnav.enabled=false"
				+ " in the outside file " + file + ": remove the line." + CHOSEN_BY, refusal(file));
		Properties upgraded = Installations.upgraded(Installations.file(file));
		assertEquals(null, upgraded.getProperty("erp.connector"), "no connector");
		StandardEnvironment env = start(upgraded(file));
		assertEquals("none", ErpConnectorEnvironmentPostProcessor.inEffect(env));
		assertEquals("STORE", env.getProperty("node.type"));
		sameValues(HAPPYNESS_STORE, env);
	}
}
