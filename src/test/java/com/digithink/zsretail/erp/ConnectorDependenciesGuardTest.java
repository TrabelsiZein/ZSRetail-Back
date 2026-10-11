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
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Code cleanup step 3: the classes of a connector package (erp/navpospages, erp/dynamicsnav) use only erp/spi, their own
 * package, the JDK and libraries. Every other reference to a class of the application (an import or a fully qualified
 * name) must be on the allow-list below, with its reason: what is left for the packages step, when erp/spi becomes its
 * own project. An entry nothing uses any more fails too, so the list stays exact. erp/engine never names a connector:
 * ConnectorNamesGuardTest scans it (checked here).
 */
class ConnectorDependenciesGuardTest {

	static final String APP = "com.digithink.zsretail.";
	static final List<String> CONNECTORS = java.util.Arrays.asList("navpospages", "dynamicsnav");
	static final Pattern REFERENCE = Pattern.compile("com\\.digithink\\.zsretail\\.[A-Za-z0-9_.]*[A-Z][A-Za-z0-9_]*");

	/**
	 * What still breaks the rule, by connector ("*": both): a referenced class, or a package ending with a dot, and why.
	 */
	static final Map<String, String> ALLOWED = new LinkedHashMap<>();

	static {
		allow("*", "erp.dto.", "the neutral objects (Erp*DTO, ErpSyncFilter, PullOperationResult): they join erp/spi when it"
				+ " becomes its own project");
		allow("navpospages", "erp.engine.CatalogueSettings", "NavPosPagesCatalogueSettings gives the engine its settings");
		allow("navpospages", "erp.engine.CatalogueRun", "NavPosPagesInvoices returns a run like the engine's (summary for"
				+ " the communications log)");
		allow("navpospages", "erp.engine.RunSummary", "NavPosPagesInvoices writes the engine's summary format");
		allow("navpospages", "erp.engine.HeadOfficeCatalogue", "the invoices read the General Setup ERP_INVOICES_READ_AFTER"
				+ " (NavPosPagesInvoices, NavPosPagesInvoiceSettingCheck)");
		allow("navpospages", "config.NodeOwnership", "NavPosPagesStartupCheck: a head office whose catalogue only comes"
				+ " from the ERP");
		allow("navpospages", "service.GeneralSetupValueCheck", "NavPosPagesInvoiceSettingCheck checks the General Setup"
				+ " value on save");
		allow("navpospages", "utils.Quantities", "NavPosPagesMapper: the decimal quantities of the invoice lines");
		allow("dynamicsnav", "model.Location", "DynamicsNavRestClient: the DEFAULT_LOCATION of the store");
		allow("dynamicsnav", "service.GeneralSetupService", "DynamicsNavRestClient: the General Setup values it sends");
		allow("dynamicsnav", "service.LocationService", "DynamicsNavRestClient: the DEFAULT_LOCATION of the store");
	}

	private static void allow(String connector, String reference, String reason) {
		ALLOWED.put(connector + " " + reference, reason);
	}

	/** The connector of a path relative to src/main/java, or null for any other file. */
	static String connectorOf(String relativePath) {
		for (String connector : CONNECTORS) {
			if (relativePath.startsWith(ConnectorNamesGuardTest.BASE + "erp/" + connector + "/")) {
				return connector;
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
		if (inApp.startsWith("erp.spi.") || inApp.startsWith("erp." + connector + ".")) {
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
	@DisplayName("A connector package uses only erp/spi, its own package, the JDK and libraries, apart from the allow-list")
	void connectorDependencies() throws IOException {
		List<String> breaches = new ArrayList<>();
		Set<String> used = new LinkedHashSet<>();
		int scanned = 0;
		try (Stream<Path> files = Files.walk(ConnectorNamesGuardTest.MAIN)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
				String relativePath = ConnectorNamesGuardTest.MAIN.relativize(file).toString().replace('\\', '/');
				String connector = connectorOf(relativePath);
				if (connector == null) {
					continue;
				}
				scanned++;
				List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
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
		}
		assertTrue(scanned > 30, "scanned " + scanned + " files");
		assertEquals("", String.join("\n", breaches), "A connector uses erp/spi only: move what it needs there, or add"
				+ " the line to the allow-list with its reason");
		List<String> stale = ALLOWED.keySet().stream().filter(key -> !used.contains(key)).collect(Collectors.toList());
		assertEquals(new ArrayList<String>(), stale, "Allow-list entries no connector needs any more: remove them");
	}

	@Test
	@DisplayName("erp/engine is scanned by ConnectorNamesGuardTest and names no connector")
	void engineNamesNoConnector() throws IOException {
		Path engine = ConnectorNamesGuardTest.MAIN.resolve(ConnectorNamesGuardTest.BASE + "erp/engine");
		int scanned = 0;
		try (Stream<Path> files = Files.list(engine)) {
			for (Path file : files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
				String relativePath = ConnectorNamesGuardTest.MAIN.relativize(file).toString().replace('\\', '/');
				assertFalse(ConnectorNamesGuardTest.allowed(relativePath), relativePath);
				assertEquals(new ArrayList<String>(),
						ConnectorNamesGuardTest.offences(relativePath, Files.readAllLines(file, StandardCharsets.UTF_8)));
				scanned++;
			}
		}
		assertTrue(scanned >= 10, "scanned " + scanned + " engine files");
	}

	@Test
	@DisplayName("The guard itself: spi, own package, JDK and libraries pass; another class of the application is a breach")
	void guardCatches() {
		assertEquals("", entryFor("navpospages", "com.digithink.zsretail.erp.spi.ErpRead"));
		assertEquals("", entryFor("navpospages", "com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow"));
		assertEquals("", entryFor("dynamicsnav", "org.springframework.stereotype.Component"));
		assertEquals("* erp.dto.", entryFor("dynamicsnav", "com.digithink.zsretail.erp.dto.ErpItemDTO"));
		assertEquals(null, entryFor("dynamicsnav", "com.digithink.zsretail.erp.navpospages.sync.NavPosPagesInvoices"));
		assertEquals(null, entryFor("navpospages", "com.digithink.zsretail.erp.engine.JdbcCatalogueState"));
		assertEquals(null, entryFor("navpospages", "com.digithink.zsretail.repository.ItemRepository"));
		assertEquals(null, entryFor("dynamicsnav", "com.digithink.zsretail.utils.Quantities"), "navpospages only");
		assertEquals("navpospages", connectorOf(ConnectorNamesGuardTest.BASE + "erp/navpospages/mapper/X.java"));
		assertEquals(null, connectorOf(ConnectorNamesGuardTest.BASE + "erp/engine/CatalogueEngine.java"));
	}
}
