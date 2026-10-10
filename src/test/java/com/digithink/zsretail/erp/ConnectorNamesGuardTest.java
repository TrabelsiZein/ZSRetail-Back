package com.digithink.zsretail.erp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Code cleanup, step 2: the application never names a connector. Outside the connector packages (erp/dynamicsnav,
 * erp/navpospages) and the translation class of step 1 (config/ErpConnectorEnvironmentPostProcessor), no file of
 * src/main/java imports a connector package or contains the text erp.dynamicsnav or erp.navpospages (a property key, a
 * package, a comment). The application asks ErpAbilities what the connector in effect can do. See
 * docs/modules/erp-sync.md, "Abilities".
 */
class ConnectorNamesGuardTest {

	static final Path MAIN = Paths.get("src", "main", "java");
	static final String BASE = "com/digithink/zsretail/";
	static final List<String> ALLOWED = Arrays.asList(BASE + "erp/dynamicsnav/", BASE + "erp/navpospages/",
			BASE + "config/ErpConnectorEnvironmentPostProcessor.java");
	static final List<String> TEXTS = Arrays.asList("erp.dynamicsnav", "erp.navpospages");

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
		try (Stream<Path> files = Files.walk(MAIN)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
				String relativePath = MAIN.relativize(file).toString().replace('\\', '/');
				if (allowed(relativePath)) {
					continue;
				}
				scanned++;
				found.addAll(offences(relativePath, Files.readAllLines(file, StandardCharsets.UTF_8)));
			}
		} catch (UncheckedIOException e) {
			throw e.getCause();
		}
		assertTrue(scanned > 300, "scanned " + scanned + " files");
		assertEquals("", String.join("\n", found),
				"Name no connector here: ask ErpAbilities (erp/spi) for an ability, see docs/modules/erp-sync.md");
	}

	@Test
	@DisplayName("The guard itself: an import, a key, a comment are caught; the allowed places are not scanned")
	void guardCatches() {
		assertEquals(3, offences("x/A.java", Arrays.asList("import com.digithink.zsretail.erp.navpospages.config.X;",
				"@Value(\"${erp.dynamicsnav.base-url}\")", "/** read erp.navpospages.invoices.seller-name */",
				"ErpAbilities.has(env, ErpAbility.SUPPLY_INVOICES)", "the connector navpospages")).size());
		assertTrue(allowed(BASE + "erp/navpospages/sync/NavPosPagesSync.java"));
		assertTrue(allowed(BASE + "erp/dynamicsnav/mapper/DynamicsNavMapper.java"));
		assertTrue(allowed(BASE + "config/ErpConnectorEnvironmentPostProcessor.java"));
		assertTrue(!allowed(BASE + "config/NodeOwnership.java"));
		assertTrue(!allowed(BASE + "erp/spi/ErpAbilities.java"));
	}
}
