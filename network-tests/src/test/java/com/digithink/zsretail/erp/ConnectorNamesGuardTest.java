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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.Repo;

/**
 * Code cleanup, step 2: the application never names a connector. Outside the connector packages (connector/emtop and
 * connector/happyness since step 6.3) and the translation class of step 1 (core/config/ErpConnectorEnvironmentPostProcessor),
 * no file of any project's src/main/java imports a connector package or contains the text erp.dynamicsnav,
 * erp.navpospages (a property key, a package, a comment) or zsretail.connector. (a connector package). The application
 * asks ErpAbilities what the connector in effect can do. See docs/modules/erp-sync.md, "Abilities".
 */
class ConnectorNamesGuardTest {

	static final String BASE = "com/digithink/zsretail/";
	static final List<String> ALLOWED = Arrays.asList(BASE + "connector/emtop/", BASE + "connector/happyness/",
			BASE + "core/config/ErpConnectorEnvironmentPostProcessor.java");
	static final List<String> TEXTS = Arrays.asList("erp.dynamicsnav", "erp.navpospages", "zsretail.connector.");

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
		assertTrue(allowed(BASE + "core/config/ErpConnectorEnvironmentPostProcessor.java"));
		assertTrue(!allowed(BASE + "core/config/NodeOwnership.java"));
		assertTrue(!allowed(BASE + "erp/api/ErpAbilities.java"));
	}
}
