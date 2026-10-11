package com.digithink.zsretail.erp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.Repo;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Code cleanup, step 2: the application never names a connector. Outside the connector packages (connector/&lt;name&gt;
 * of every project of connectors/), no file of any project's src/main/java imports a connector package or contains a
 * connector's key prefix (as its descriptor declares it: erp.dynamicsnav, erp.navpospages; a property key, a package, a
 * comment) or zsretail.connector. (a connector package). Since code cleanup step 7 no class of core is an exception: the
 * translation of erp.connector reads the descriptors. The application asks ErpAbilities what the connector in effect
 * can do. See docs/modules/erp-sync.md, "Abilities".
 */
class ConnectorNamesGuardTest {

	static final String BASE = "com/digithink/zsretail/";
	/** Code cleanup step 7: the connectors' own packages only (every connector project found); no class of core. */
	static final List<String> ALLOWED = Repo.connectorNames().stream().map(name -> BASE + "connector/" + name + "/")
			.collect(Collectors.toList());
	/** The connectors' key prefixes, as each declares them (its descriptor), and their packages. */
	static final List<String> TEXTS = texts();

	private static List<String> texts() {
		List<String> texts = ErpConnectors.descriptors().stream().map(ErpConnectorDescriptor::keyPrefix)
				.collect(Collectors.toList());
		texts.add("zsretail.connector.");
		return texts;
	}

	/** The offending lines of one file, as "path:line: text". */
	static List<String> offences(String relativePath, List<String> lines) {
		List<String> found = new ArrayList<>();
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i);
			for (String text : TEXTS) {
				if (line.contains(text)) {
					found.add(relativePath + ":" + (i + 1) + ": " + line.trim());
					break;
				}
			}
		}
		return found;
	}

	static boolean allowed(String relativePath) {
		return ALLOWED.stream().anyMatch(relativePath::startsWith);
	}

	@Test
	@DisplayName("No file outside the connector packages and the step 1 class names a connector")
	void noConnectorNames() throws IOException {
		List<String> found = new ArrayList<>();
		int scanned = 0;
		for (Map.Entry<String, Path> file : Repo.mainJavaFiles().entrySet()) {
			String relativePath = file.getKey();
			if (allowed(relativePath)) {
				continue;
			}
			scanned++;
			found.addAll(offences(relativePath, Files.readAllLines(file.getValue(), StandardCharsets.UTF_8)));
		}
		assertTrue(scanned > 300, "scanned " + scanned + " files");
		assertEquals("", String.join("\n", found),
				"Name no connector here: ask ErpAbilities (erp-api) for an ability, see docs/modules/erp-sync.md");
	}

	@Test
	@DisplayName("The guard itself: an import, a key, a comment are caught; the allowed places are not scanned")
	void guardCatches() {
		assertEquals(4, offences("x/A.java", Arrays.asList("import com.digithink.zsretail.erp.navpospages.config.X;",
				"@Value(\"${erp.dynamicsnav.base-url}\")", "/** read erp.navpospages.invoices.seller-name */",
				"ErpAbilities.has(env, ErpAbility.SUPPLY_INVOICES)", "the connector navpospages",
				"import com.digithink.zsretail.connector.happyness.sync.NavPosPagesInvoices;")).size());
		assertTrue(allowed(BASE + "connector/happyness/sync/NavPosPagesInvoices.java"));
		assertTrue(allowed(BASE + "connector/emtop/mapper/DynamicsNavMapper.java"));
		assertTrue(!allowed(BASE + "core/config/ErpConnectorEnvironmentPostProcessor.java"), "since step 7: no exception");
		assertTrue(TEXTS.containsAll(Arrays.asList("erp.dynamicsnav", "erp.navpospages")), "from the descriptors: " + TEXTS);
		assertTrue(!allowed(BASE + "core/config/NodeOwnership.java"));
		assertTrue(!allowed(BASE + "erp/api/ErpAbilities.java"));
	}
}
