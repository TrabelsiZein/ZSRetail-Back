package com.digithink.zsretail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.support.Repo;

/**
 * Code cleanup step 5: the target project of every class of the projects' src/main/java (step 6 moves each class once,
 * straight into it) and the rules between the projects:
 * <ul>
 * <li>erp-api uses nothing of ours;</li>
 * <li>core uses core and erp-api;</li>
 * <li>store and headoffice use themselves, core and erp-api, never each other;</li>
 * <li>a connector (emtop, happyness) uses itself and erp-api.</li>
 * </ul>
 * A reference is an import, a class of the same package named in the code, or a fully qualified name in the code
 * (comments and strings left out). What still breaks a rule today is the allow-list below, each line with its reason;
 * a line nothing needs any more fails too, so the list stays exact. The sorting: docs/roadmap/project-sorting.md.
 */
public class ProjectSortingGuardTest {

	static final String APP = "com.digithink.zsretail.";

	public static final String ERP_API = "erp-api";
	static final String CORE = "core";
	static final String STORE = "store";
	static final String HEADOFFICE = "headoffice";
	static final String EMTOP = "emtop";
	static final String HAPPYNESS = "happyness";

	/** The classes of the common packages that go to store (everything else there goes to core). */
	static final Set<String> STORE_CLASSES = new HashSet<>(Arrays.asList(
			"controller.BadgeAPI", "controller.CashierSessionAPI", "controller.DashboardAPI", "controller.InvoiceAPI",
			"controller.PaymentAPI", "controller.PaymentMethodAPI", "controller.PriceCalculateController",
			"controller.ReturnHeaderAPI", "controller.ReturnVoucherAPI", "controller.SalesDiscountAPI",
			"controller.SalesHeaderAPI", "controller.SalesLineAPI", "controller.SalesPriceAPI", "controller.SalesReportAPI", "controller.WarrantyAPI",
			"dto.CartCalculateRequestDTO", "dto.CartCalculateResponseDTO", "dto.ChangePaymentMethodRequestDTO",
			"dto.CloseSessionRequestDTO", "dto.CrossProductAdjustmentDTO", "dto.EligibleTicketDTO", "dto.InvoiceListDTO",
			"dto.PrepareInvoiceRequestDTO", "dto.PriceCalculateRequestDTO", "dto.PriceCalculateResponseDTO",
			"dto.ProcessReturnRequestDTO", "dto.ProcessSaleRequestDTO", "dto.SessionCloseTicketDTO",
			"dto.SessionDashboardDTO", "dto.SplitBillRequestDTO", "erp.service.ReturnExportService",
			"erp.service.SessionExportService", "erp.service.TicketExportService", "erp.model.PaymentHeader",
			"erp.model.PaymentLine", "erp.repository.PaymentHeaderRepository", "erp.repository.PaymentLineRepository",
			"exception.CashDiscrepancyException", "model.PaymentChangeLog", "model.SessionCashCount", "model.Warranty",
			"repository.PaymentChangeLogRepository", "repository.ReturnVoucherRepository",
			"repository.SessionCashCountRepository", "repository.WarrantyRepository", "service.BadgeScanLogService",
			"service.CashierSessionService", "service.DashboardService", "service.InvoiceService",
			"service.PaymentMethodService", "service.PaymentService", "service.PromotionCalculationService",
			"service.ReturnHeaderService", "service.ReturnVoucherService", "service.SalesDiscountService",
			"service.SalesHeaderService", "service.SalesLineService", "service.SalesPriceService", "service.SalesReportService",
			"service.SessionCashCountService", "service.StoreQuantityColumns", "service.WarrantyService"));

	/**
	 * Code cleanup step 6: the package root of each project; a class takes it when it moves into its project. Until then
	 * (in app) a class is sorted by the rules of step 5 below.
	 */
	static final Map<String, String> ROOTS = new LinkedHashMap<>();

	static {
		ROOTS.put("erp.api.", ERP_API);
		ROOTS.put("connector.emtop.", EMTOP);
		ROOTS.put("connector.happyness.", HAPPYNESS);
		ROOTS.put("core.", CORE);
		ROOTS.put("store.", STORE);
		ROOTS.put("headoffice.", HEADOFFICE);
	}

	/** The Maven project (folder) of each project. */
	static final Map<String, String> FOLDERS = new HashMap<>();

	static {
		FOLDERS.put(ERP_API, "erp-api");
		FOLDERS.put(EMTOP, "connector-emtop");
		FOLDERS.put(HAPPYNESS, "connector-happyness");
		FOLDERS.put(CORE, "core");
		FOLDERS.put(STORE, "store");
		FOLDERS.put(HEADOFFICE, "headoffice");
	}

	/** The project of a class (its name after com.digithink.zsretail.). */
	public static String projectOf(String name) {
		for (Map.Entry<String, String> root : ROOTS.entrySet()) {
			if (name.startsWith(root.getKey())) {
				return root.getValue();
			}
		}
		// Not moved yet (still in app)
		if (name.startsWith("holink.") || name.startsWith("analytics.") || name.startsWith("inventory.")) {
			return STORE;
		}
		return STORE_CLASSES.contains(name) ? STORE : CORE;
	}

	static final Map<String, Set<String>> MAY_USE = new HashMap<>();

	static {
		MAY_USE.put(ERP_API, set(ERP_API));
		MAY_USE.put(CORE, set(CORE, ERP_API));
		MAY_USE.put(STORE, set(STORE, CORE, ERP_API));
		MAY_USE.put(HEADOFFICE, set(HEADOFFICE, CORE, ERP_API));
		MAY_USE.put(EMTOP, set(EMTOP, ERP_API));
		MAY_USE.put(HAPPYNESS, set(HAPPYNESS, ERP_API));
	}

	private static Set<String> set(String... values) {
		return new HashSet<>(Arrays.asList(values));
	}

	/** What still breaks a rule: "from -> to" (names after com.digithink.zsretail.), and why. */
	static final Map<String, String> ALLOWED = new LinkedHashMap<>();

	static {
		// Empty since code cleanup step 5.8d: a new breach is untied, or added here with allow(reason, references...).
	}

	private static void allow(String reason, String... references) {
		for (String reference : references) {
			ALLOWED.put(reference, reason);
		}
	}

	// ─── Reading the references ─────────────────────────────────

	static final Pattern IMPORT = Pattern
			.compile("^\\s*import\\s+(?:static\\s+)?(com\\.digithink\\.zsretail\\.[\\w.]+?)(\\.\\*)?\\s*;", Pattern.MULTILINE);
	static final Pattern QUALIFIED = Pattern.compile("\\bcom\\.digithink\\.zsretail\\.[\\w.]+");

	/** The code without comments, string and character literals, package and import lines. */
	static String code(String text) {
		StringBuilder out = new StringBuilder(text.length());
		int i = 0;
		while (i < text.length()) {
			char c = text.charAt(i);
			char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
			if (c == '/' && next == '*') {
				int end = text.indexOf("*/", i + 2);
				i = end < 0 ? text.length() : end + 2;
				out.append(' ');
			} else if (c == '/' && next == '/') {
				int end = text.indexOf('\n', i);
				i = end < 0 ? text.length() : end;
			} else if (c == '"' || c == '\'') {
				i++;
				while (i < text.length() && text.charAt(i) != c && text.charAt(i) != '\n') {
					i += text.charAt(i) == '\\' ? 2 : 1;
				}
				i++;
				out.append(c).append(c);
			} else {
				out.append(c);
				i++;
			}
		}
		return out.toString().replaceAll("(?m)^\\s*(package|import)\\s[^;]*;", "");
	}

	/**
	 * Every class of every project's src/main/java (names after com.digithink.zsretail.) and the classes each one refers
	 * to.
	 */
	static Map<String, Set<String>> references() throws IOException {
		Map<String, Path> files = new TreeMap<>();
		for (Map.Entry<String, Path> file : Repo.mainJavaFiles().entrySet()) {
			String name = file.getKey().replaceAll("\\.java$", "").replace('/', '.');
			files.put(name.substring(APP.length()), file.getValue());
		}
		Map<String, List<String>> byPackage = new HashMap<>();
		files.keySet().forEach(name -> byPackage
				.computeIfAbsent(name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "", k -> new ArrayList<>())
				.add(name));
		Map<String, Set<String>> references = new TreeMap<>();
		for (Map.Entry<String, Path> entry : files.entrySet()) {
			String name = entry.getKey();
			String text = new String(Files.readAllBytes(entry.getValue()), StandardCharsets.UTF_8);
			String code = code(text);
			String ownPackage = name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : "";
			Set<String> to = new TreeSet<>();
			Matcher imports = IMPORT.matcher(text);
			while (imports.find()) {
				String imported = imports.group(1).substring(APP.length());
				if (imports.group(2) != null && byPackage.containsKey(imported)) {
					for (String other : byPackage.get(imported)) {
						if (named(code, other)) {
							to.add(other);
						}
					}
				} else {
					String known = known(imported, files.keySet());
					if (known != null) {
						to.add(known);
					}
				}
			}
			for (String other : byPackage.getOrDefault(ownPackage, new ArrayList<>())) {
				if (!other.equals(name) && named(code, other)) {
					to.add(other);
				}
			}
			Matcher qualified = QUALIFIED.matcher(code);
			while (qualified.find()) {
				String known = known(qualified.group().substring(APP.length()), files.keySet());
				if (known != null && !known.equals(name)) {
					to.add(known);
				}
			}
			references.put(name, to);
		}
		return references;
	}

	private static boolean named(String code, String name) {
		String simple = name.substring(name.lastIndexOf('.') + 1);
		return Pattern.compile("\\b" + Pattern.quote(simple) + "\\b").matcher(code).find();
	}

	/** The class a reference names (a nested class or a static member: its top-level class), or null. */
	private static String known(String reference, Set<String> classes) {
		String name = reference;
		while (!classes.contains(name) && name.contains(".")) {
			name = name.substring(0, name.lastIndexOf('.'));
		}
		return classes.contains(name) ? name : null;
	}

	// ─── The tests ──────────────────────────────────────────────

	@Test
	@DisplayName("No project uses one it may not use, apart from the allow-list; no allow-list line is stale")
	void sorting() throws IOException {
		Map<String, Set<String>> references = references();
		assertTrue(references.size() > 600, "classes read: " + references.size());
		List<String> breaches = new ArrayList<>();
		Set<String> used = new HashSet<>();
		for (Map.Entry<String, Set<String>> entry : references.entrySet()) {
			String from = projectOf(entry.getKey());
			for (String target : entry.getValue()) {
				String to = projectOf(target);
				if (MAY_USE.get(from).contains(to)) {
					continue;
				}
				String reference = entry.getKey() + " -> " + target;
				if (ALLOWED.containsKey(reference)) {
					used.add(reference);
				} else {
					breaches.add(from + " -> " + to + ": " + reference);
				}
			}
		}
		assertEquals("", String.join("\n", breaches), "A project uses one it may not use: untie the knot, or add the"
				+ " line to the allow-list with its reason");
		List<String> stale = ALLOWED.keySet().stream().filter(reference -> !used.contains(reference))
				.collect(Collectors.toList());
		assertEquals(new ArrayList<String>(), stale, "Allow-list lines nothing needs any more: remove them");
	}

	@Test
	@DisplayName("Every class lives in the project of its package root; a class without one is still in app")
	void packageRootIsProject() {
		List<String> wrong = new ArrayList<>();
		for (Map.Entry<String, Path> file : Repo.mainJavaFiles().entrySet()) {
			String name = file.getKey().replaceAll("\\.java$", "").replace('/', '.').substring(APP.length());
			String folder = Repo.ROOT.relativize(file.getValue()).getName(0).toString();
			String expected = ROOTS.keySet().stream().anyMatch(name::startsWith) ? FOLDERS.get(projectOf(name)) : "app";
			if (!folder.equals(expected)) {
				wrong.add(name + " in " + folder + ", expected " + expected);
			}
		}
		assertEquals(new ArrayList<String>(), wrong);
	}

	@Test
	@DisplayName("Every class named in the sorting exists")
	void sortingNamesExist() throws IOException {
		Set<String> classes = references().keySet();
		List<String> missing = new ArrayList<>();
		STORE_CLASSES.stream().filter(name -> !classes.contains(name)).forEach(missing::add);
		assertEquals(new ArrayList<String>(), missing);
	}

	@Test
	@DisplayName("The reader: comments and strings are not references; imports, same package and full names are")
	void reader() {
		String code = code("package a;\nimport b.C;\n/** D */ class E { // F\n String s = \"G // H\"; char c = '\"'; I i; }");
		assertTrue(code.contains("I i"), code);
		assertTrue(!code.contains("D") && !code.contains("F") && !code.contains("G") && !code.contains("import"), code);
		assertEquals(CORE, projectOf("core.service.ItemService"));
		assertEquals(STORE, projectOf("service.SalesHeaderService"));
		assertEquals(STORE, projectOf("holink.service.SalesPushService"));
		assertEquals(CORE, projectOf("core.link.dto.TicketCopyDTO"));
		assertEquals(HEADOFFICE, projectOf("headoffice.dto.StoreDTO"));
		assertEquals(ERP_API, projectOf("erp.api.dto.ErpItemDTO"));
		assertEquals(CORE, projectOf("core.erp.dto.ErpSyncJobViewDTO"));
		assertEquals(EMTOP, projectOf("connector.emtop.connector.DynamicsNavConnector"));
		assertEquals(HAPPYNESS, projectOf("connector.happyness.mapper.NavPosPagesMapper"));
		assertEquals(CORE, projectOf("POSMainApp"));
	}
}
