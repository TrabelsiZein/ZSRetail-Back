package com.digithink.zsretail.connector.parafendri;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.config.ParafendriConfig;
import com.digithink.zsretail.connector.parafendri.config.ParafendriProperties;
import com.digithink.zsretail.connector.parafendri.config.ParafendriStartupCheck;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCompanyRow;

/**
 * ParaFendri, step 0: the connection test against a real Business Central, GET only (the companies of the API), the
 * settings read from an outside file named by -Dparafendri.file (no default: never the committed
 * configs/customers/parafendri-store.properties, all CHANGE_ME, and never a customer's production without Zein's
 * word). Skipped unless -Dparafendri.live=true (never in the normal suite). Prints the companies found and the one of
 * company-id. Writes nothing anywhere.
 * <p>
 * Run from Apps/ZSRetail-Back (JDK 21 and the settings of CLAUDE.md): mvn ... -pl connectors/parafendri -am
 * -Dtest=ParafendriLiveConnectionTest -Dsurefire.failIfNoSpecifiedTests=false -Dparafendri.live=true
 * -Dparafendri.file=C:\...\test_parafendri_store.properties test
 */
@EnabledIfSystemProperty(named = "parafendri.live", matches = "true")
class ParafendriLiveConnectionTest {

	@Test
	@DisplayName("Live connection test: the companies of the API read (GET only), company-id among them")
	void liveConnection() throws IOException {
		String file = System.getProperty("parafendri.file", "").trim();
		assertTrue(!file.isEmpty(), "-Dparafendri.file=<the outside file of the test installation> is required");
		Path path = Paths.get(file);
		Properties lines = new Properties();
		try (InputStream in = Files.newInputStream(path)) {
			lines.load(in);
		}
		MockEnvironment env = new MockEnvironment();
		lines.stringPropertyNames().forEach(key -> env.setProperty(key, lines.getProperty(key)));
		ParafendriStartupCheck.check(env);
		System.out.println(ParafendriStartupCheck.summary(env));
		ParafendriProperties properties = Binder.get(env).bind(ParafendriProperties.PREFIX, ParafendriProperties.class)
				.get();
		ParafendriRestClient client = new ParafendriRestClient(ParafendriConfig.restTemplate(properties), properties);

		long start = System.currentTimeMillis();
		for (ParafendriCompanyRow row : client.readCompanies()) {
			System.out.println("company " + row.getId() + " " + row.getName() + " (" + row.getDisplayName() + ")");
		}
		ParafendriCompanyRow company = client.checkCompany();
		System.out.println("company-id found: " + company.getName() + ", in " + (System.currentTimeMillis() - start)
				+ " ms");
	}
}
