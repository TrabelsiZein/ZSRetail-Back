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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.Repo;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;
import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Code cleanup, step 2: the application never names a connector. Outside the connector packages (connector/&lt;name&gt;
 * of every project of connectors/), no file of any project's src/main/java imports a connector package or contains a
 * connector's key prefix, old or new (as its descriptor declares them; a property key, a package, a comment) or
 * zsretail.connector. (a connector package). Since code cleanup step 7 no class of core is an exception: the refusals of
 * the old lines read the descriptors. The application asks ErpAbilities what the connector in effect
 * can do. See docs/modules/erp-sync.md, "Abilities".
 */
class ConnectorNamesGuardTest {

	static final String BASE = "com/digithink/zsretail/";
	/** Code cleanup step 7: the connectors' own packages only (every connector project found); no class of core. */
	static final List<String> ALLOWED = Repo.connectorNames().stream().map(name -> BASE + "connector/" + name + "/")
			.collect(Collectors.toList());
	/** The connectors' key prefixes, old and new, as each declares them (its descriptor), and their packages. */
	static final List<String> TEXTS = texts();

	private static List<String> texts() {
		List<String> texts = new ArrayList<>();
		for (ErpConnectorDescriptor descriptor : ErpConnectors.descriptors()) {
			texts.add(descriptor.keyPrefix());
			if (descriptor.oldKeyPrefix() != null && !texts.contains(descriptor.oldKeyPrefix())) {
				texts.add(descriptor.oldKeyPrefix());
			}
		}
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

	/**
	 * Code cleanup step 8, follow-up 2: what a user can read (a string literal: a message, a setting's description) never
	 * names a connector: its name, its old name (the last part of its old prefix), nor NAV. Comments may tell the history.
	 * No exception (the old name of the catalogue state table is declared by its connector, code cleanup step 8). Item
	 * variants (ZEN), step 8: a name is a whole word, with no letter or digit before or after it, so that "frozen" or
	 * "citizen" is not the connector zen; not \b, which takes _ for a letter and would no longer catch navpospages_state
	 * or ERP_ZEN_PASSWORD.
	 */
	static final Pattern NAMED = Pattern.compile(
			"(?i)(?<![a-z0-9])(?:" + String.join("|", connectorWords()) + ")(?![a-z0-9])|(?-i)\\bNAV\\b");

	private static List<String> connectorWords() {
		List<String> words = new ArrayList<>();
		for (ErpConnectorDescriptor descriptor : ErpConnectors.descriptors()) {
			words.add(Pattern.quote(descriptor.name()));
			if (descriptor.oldKeyPrefix() != null) {
				words.add(Pattern.quote(descriptor.oldKeyPrefix().substring(descriptor.oldKeyPrefix().lastIndexOf('.') + 1)));
			}
		}
		return words;
	}

	/** The string literals of the lines that name a connector, as "path:line: literal". */
	static List<String> namedInStrings(String relativePath, List<String> lines) {
		List<String> found = new ArrayList<>();
		Pattern literal = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
		for (int i = 0; i < lines.size(); i++) {
			String line = lines.get(i).trim();
			if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
				continue;
			}
			Matcher strings = literal.matcher(line);
			while (strings.find()) {
				if (NAMED.matcher(strings.group()).find()) {
					found.add(relativePath + ":" + (i + 1) + ": " + strings.group());
				}
			}
		}
		return found;
	}

	@Test
	@DisplayName("No message or text of the application (outside the connectors) names a connector, an old name or NAV")
	void noConnectorInTexts() throws IOException {
		List<String> found = new ArrayList<>();
		for (Map.Entry<String, Path> file : Repo.mainJavaFiles().entrySet()) {
			if (!allowed(file.getKey())) {
				found.addAll(namedInStrings(file.getKey(), Files.readAllLines(file.getValue(), StandardCharsets.UTF_8)));
			}
		}
		assertEquals("", String.join("\n", found), "Word it by the ability or as \"the ERP\"");
		assertEquals(4, namedInStrings("x/A.java", Arrays.asList("throw new X(\"the navpospages connector\");",
				"log.info(\"sent to NAV\");", "msg(\"Happyness head office\");", "// the NAV of EMTOP",
				"String table = \"navpospages_state\";", "\"the ERP\", \"navigation\"")).size());
		// Item variants (ZEN), step 8: zen as a whole word only
		assertEquals(Arrays.asList("x/A.java:1: \"the zen connector\"", "x/A.java:2: \"ERP_ZEN_PASSWORD\"",
				"x/A.java:3: \"erp.zen.base-url\""), namedInStrings("x/A.java", Arrays.asList("msg(\"the zen connector\");",
						"env(\"ERP_ZEN_PASSWORD\");", "key(\"erp.zen.base-url\");", "\"frozen\", \"citizen\", \"Zenith\", \"dozens\"",
						"\"emtopX\", \"2happyness\"")));
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
		assertTrue(TEXTS.containsAll(Arrays.asList("erp.emtop", "erp.happyness", "erp.dynamicsnav", "erp.navpospages",
				"erp.zen")),
				"from the descriptors, new and old: " + TEXTS);
		assertTrue(!allowed(BASE + "core/config/NodeOwnership.java"));
		assertTrue(!allowed(BASE + "erp/api/ErpAbilities.java"));
	}
}
