package com.digithink.zsretail.connector.parafendri;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

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
import com.digithink.zsretail.connector.parafendri.dto.ParafendriBarcodeRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCollection;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriItemCategoryRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriStockPointRow;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriLocationRow;
import com.digithink.zsretail.connector.parafendri.mapper.ParafendriMapper;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;

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
	@DisplayName("Live connection test (GET only): company-id among the companies; step 1: the locations, the store's among them")
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

		// Step 1: the locations of the company, and the store's (-Dparafendri.location, PV-GREMDA by default)
		start = System.currentTimeMillis();
		List<ParafendriLocationRow> locations = client.readLocations();
		for (ParafendriLocationRow row : locations) {
			System.out.println("location " + row.getCode() + " " + row.getName() + " (" + row.getLastModifiedDateTime()
					+ ")");
		}
		System.out.println("locations: " + locations.size() + ", in " + (System.currentTimeMillis() - start) + " ms");
		String code = System.getProperty("parafendri.location", "PV-GREMDA");
		boolean found = client.checkLocation(code);
		System.out.println("location " + code + (found ? " found" : " NOT found"));
		assertTrue(found, "location " + code + " among the locations of the company");

		// Step 2: the tree itemCategories, its count per level, what each level keeps and leaves out
		start = System.currentTimeMillis();
		List<ParafendriItemCategoryRow> tree = client.readItemCategories();
		System.out.println("itemCategories: " + tree.size() + " rows, in " + (System.currentTimeMillis() - start) + " ms");
		Map<Object, Long> perIndentation = tree.stream().collect(Collectors.groupingBy(
				row -> row.getIndentation() == null ? "none" : row.getIndentation(), TreeMap::new, Collectors.counting()));
		System.out.println("rows per indentation: " + perIndentation);
		ErpCategories levels = new ParafendriMapper().categories(tree);
		print("catégories (0)", levels.getCategories());
		print("familles (1)", levels.getFamilies());
		print("sous-familles (2)", levels.getSubFamilies());
		print("sections (3)", levels.getSections());

		// Step 3: page 1 of stockPoints of the point de vente (no checkpoint), its count and time, then what the items keep
		start = System.currentTimeMillis();
		ParafendriCollection<ParafendriStockPointRow> page = client.readStockPointsFirstPage(code, null);
		List<ParafendriStockPointRow> rows = page.getValue() == null ? new ArrayList<>() : page.getValue();
		System.out.println("stockPoints page 1 (" + code + "): " + rows.size() + " rows, in "
				+ (System.currentTimeMillis() - start) + " ms, more pages: "
				+ (page.getNextLink() == null || page.getNextLink().trim().isEmpty() ? "no" : "yes"));
		ErpRead<ErpItemDTO> items = new ParafendriMapper().items(code, rows, levels);
		print("items of page 1", items);
		if (!items.getRows().isEmpty()) {
			ErpItemDTO first = items.getRows().get(0);
			System.out.println("first item: " + first.getCode() + " " + first.getName() + ", unit " + first.getUnitOfMeasure()
					+ ", unitPrice " + first.getUnitPrice() + ", VAT " + first.getDefaultVAT() + ", links "
					+ first.getCategoryCode() + " / " + first.getFamilyExternalId() + " / " + first.getSubFamilyExternalId()
					+ " / " + first.getSectionCode() + ", active " + first.getActive() + ", modified "
					+ first.getLastModifiedAt());
		}

		// Step 4: page 1 of barCodes (company-wide, no checkpoint), its count and time; the orphans: barcodes whose itemNo
		// is not in this point de vente's stockPoints (the import would leave them out, one warning row each)
		start = System.currentTimeMillis();
		ParafendriCollection<ParafendriBarcodeRow> barcodePage = client.readBarcodesFirstPage(null);
		List<ParafendriBarcodeRow> barcodeRows = barcodePage.getValue() == null ? new ArrayList<>()
				: barcodePage.getValue();
		System.out.println("barCodes page 1: " + barcodeRows.size() + " rows, in " + (System.currentTimeMillis() - start)
				+ " ms, more pages: "
				+ (barcodePage.getNextLink() == null || barcodePage.getNextLink().trim().isEmpty() ? "no" : "yes"));
		ErpRead<ErpItemBarcodeDTO> barcodes = new ParafendriMapper().barcodes(barcodeRows);
		print("barcodes of page 1", barcodes);
		Set<String> here = rows.stream().map(row -> row.getItemNo() == null ? "" : row.getItemNo().trim())
				.collect(Collectors.toSet());
		long orphans = barcodes.getRows().stream().filter(barcode -> !here.contains(barcode.getItemExternalId())).count();
		long itemsWithBarcode = barcodes.getRows().stream().map(ErpItemBarcodeDTO::getItemExternalId)
				.filter(here::contains).distinct().count();
		System.out.println("orphan barcodes (itemNo not in the stockPoints of " + code + "): " + orphans
				+ "; items of " + code + " with a barcode: " + itemsWithBarcode + " of " + here.size()
				+ (barcodePage.getNextLink() == null ? "" : " (page 1 only)"));
		if (!barcodes.getRows().isEmpty()) {
			ErpItemBarcodeDTO firstBarcode = barcodes.getRows().get(0);
			System.out.println("first barcode: " + firstBarcode.getBarcode() + " of " + firstBarcode.getItemExternalId()
					+ ", id " + firstBarcode.getExternalId() + ", modified " + firstBarcode.getLastModifiedAt());
		}
	}

	private static void print(String level, ErpRead<?> read) {
		System.out.println(level + ": read " + read.getRead() + ", kept " + read.getKept() + ", left out "
				+ read.getLeftOut() + ", noted " + read.getNotes());
	}
}
