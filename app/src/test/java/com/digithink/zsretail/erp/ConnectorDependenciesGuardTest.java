package com.digithink.zsretail.erp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.ProjectSortingGuardTest;
import com.digithink.zsretail.support.Repo;

/**
 * Code cleanup step 3: the classes of a connector package (connector/happyness, connector/emtop) use only erp-api, their
 * own package, the JDK and libraries (since step 6.3 their Maven projects make it true as well). Since code cleanup step 5.8e erp-api is what ProjectSortingGuardTest sorts there:
 * the package erp/api (with erp/api/dto), the project erp-api since step 6.2. Every other reference to a class of the
 * application (an import or a fully qualified name) must be on the allow-list below, with its reason. An entry nothing
 * uses any more fails too, so the list stays exact. core/erp/engine never names a connector: ConnectorNamesGuardTest scans it
 * (checked here).
 */
class ConnectorDependenciesGuardTest {

	static final String APP = "com.digithink.zsretail.";
	/** Each connector and its package (after com.digithink.zsretail.). */
	static final Map<String, String> CONNECTORS = new LinkedHashMap<>();

	static {
		CONNECTORS.put("happyness", "connector.happyness");
		CONNECTORS.put("emtop", "connector.emtop");
	}
	static final Pattern REFERENCE = Pattern.compile("com\\.digithink\\.zsretail\\.[A-Za-z0-9_.]*[A-Z][A-Za-z0-9_]*");

	/**
	 * What still breaks the rule, by connector ("*": both): a referenced class, or a package ending with a dot, and why.
	 */
	static final Map<String, String> ALLOWED = new LinkedHashMap<>();

	static {
		// Empty since code cleanup step 5.8e: a new breach is untied, or added here with allow(connector, reference, reason).
	}

	private static void allow(String connector, String reference, String reason) {
		ALLOWED.put(connector + " " + reference, reason);
	}

	/** The connector of a path relative to a project's src/main/java, or null for any other file. */
	static String connectorOf(String relativePath) {
		for (Map.Entry<String, String> connector : CONNECTORS.entrySet()) {
			if (relativePath.startsWith(ConnectorNamesGuardTest.BASE + connector.getValue().replace('.', '/') + "/")) {
				return connector.getKey();
			}
		}
		return null;
	}

	/** The allow-list entry that covers this reference of the connector, or null (a breach). */
	static String entryFor(String connector, String reference) {
		if (!reference.startsWith(APP)) {
			return "";
		}
		String inApp = reference.substring(APP.length());
		if (inApp.startsWith(CONNECTORS.get(connector) + ".")
				|| ProjectSortingGuardTest.ERP_API.equals(ProjectSortingGuardTest.projectOf(inApp))) {
			return "";
		}
		for (String key : ALLOWED.keySet()) {
			String[] parts = key.split(" ", 2);
			boolean forThisOne = parts[0].equals("*") || parts[0].equals(connector);
			boolean matches = parts[1].endsWith(".") ? inApp.startsWith(parts[1])
					: inApp.equals(parts[1]) || inApp.startsWith(parts[1] + ".");
			if (forThisOne && matches) {
				return key;
			}
		}
		return null;
	}

	@Test
	@DisplayName("A connector package uses only erp-api, its own package, the JDK and libraries, apart from the allow-list")
	void connectorDependencies() throws IOException {
		List<String> breaches = new ArrayList<>();
		Set<String> used = new LinkedHashSet<>();
		int scanned = 0;
		for (Map.Entry<String, Path> file : Repo.mainJavaFiles().entrySet()) {
			String relativePath = file.getKey();
			String connector = connectorOf(relativePath);
			if (connector == null) {
				continue;
			}
			scanned++;
			List<String> lines = Files.readAllLines(file.getValue(), StandardCharsets.UTF_8);
			for (int i = 0; i < lines.size(); i++) {
				Matcher matcher = REFERENCE.matcher(lines.get(i));
				while (matcher.find()) {
					String entry = entryFor(connector, matcher.group());
					if (entry == null) {
						breaches.add(relativePath + ":" + (i + 1) + ": " + matcher.group());
					} else if (!entry.isEmpty()) {
						used.add(entry);
					}
				}
			}
		}
		assertTrue(scanned > 30, "scanned " + scanned + " files");
		assertEquals("", String.join("\n", breaches), "A connector uses erp-api only: move what it needs there, or add"
				+ " the line to the allow-list with its reason");
		List<String> stale = ALLOWED.keySet().stream().filter(key -> !used.contains(key)).collect(Collectors.toList());
		assertEquals(new ArrayList<String>(), stale, "Allow-list entries no connector needs any more: remove them");
	}

	@Test
	@DisplayName("core/erp/engine is scanned by ConnectorNamesGuardTest and names no connector")
	void engineNamesNoConnector() throws IOException {
		String engine = ConnectorNamesGuardTest.BASE + "core/erp/engine/";
		int scanned = 0;
		for (Map.Entry<String, Path> file : Repo.mainJavaFiles().entrySet()) {
			String relativePath = file.getKey();
			if (!relativePath.startsWith(engine) || relativePath.indexOf('/', engine.length()) >= 0) {
				continue;
			}
			assertFalse(ConnectorNamesGuardTest.allowed(relativePath), relativePath);
			assertEquals(new ArrayList<String>(),
					ConnectorNamesGuardTest.offences(relativePath, Files.readAllLines(file.getValue(), StandardCharsets.UTF_8)));
			scanned++;
		}
		assertTrue(scanned >= 9, "scanned " + scanned + " engine files");
	}

	@Test
	@DisplayName("The guard itself: spi, own package, JDK and libraries pass; another class of the application is a breach")
	void guardCatches() {
		assertEquals("", entryFor("happyness", "com.digithink.zsretail.erp.api.ErpRead"));
		assertEquals("", entryFor("happyness", "com.digithink.zsretail.connector.happyness.dto.NavPosStockRow"));
		assertEquals("", entryFor("emtop", "org.springframework.stereotype.Component"));
		assertEquals("", entryFor("emtop", "com.digithink.zsretail.erp.api.dto.ErpItemDTO"), "erp-api, as sorted");
		assertEquals(null, entryFor("happyness", "com.digithink.zsretail.core.erp.dto.ErpSyncJobViewDTO"), "core, as sorted");
		assertEquals(null, entryFor("emtop", "com.digithink.zsretail.connector.happyness.sync.NavPosPagesInvoices"));
		assertEquals(null, entryFor("happyness", "com.digithink.zsretail.core.erp.engine.JdbcCatalogueState"));
		assertEquals(null, entryFor("happyness", "com.digithink.zsretail.core.repository.ItemRepository"));
		assertEquals(null, entryFor("happyness", "com.digithink.zsretail.core.utils.Quantities"),
				"it reads erp.api.ErpQuantities");
		assertEquals("happyness", connectorOf(ConnectorNamesGuardTest.BASE + "connector/happyness/mapper/X.java"));
		assertEquals(null, connectorOf(ConnectorNamesGuardTest.BASE + "core/erp/engine/CatalogueEngine.java"));
	}
}
