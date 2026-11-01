package com.digithink.zsretail.connector.parafendri;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.connector.parafendri.client.ParafendriReadException;
import com.digithink.zsretail.connector.parafendri.client.ParafendriRestClient;
import com.digithink.zsretail.connector.parafendri.connector.ParafendriConnector;
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
import com.digithink.zsretail.connector.parafendri.dto.ParafendriSalesOrderRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriShipAndInvoiceRow;
import com.digithink.zsretail.connector.parafendri.mapper.ParafendriMapper;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.ErpStoreLocation;
import com.digithink.zsretail.erp.api.dto.ErpInvoiceResult;
import com.digithink.zsretail.erp.api.dto.ErpOperationResult;
import com.digithink.zsretail.erp.api.dto.ErpTicketDTO;
import com.digithink.zsretail.erp.api.dto.ErpTicketLineDTO;

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

	/** The client of the outside file -Dparafendri.file, its settings checked as at startup. */
	static ParafendriRestClient client() throws IOException {
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
		return new ParafendriRestClient(ParafendriConfig.restTemplate(properties), properties);
	}

	/**
	 * Step 6, a live POST of one test ticket: runs ONLY with -Dparafendri.live.post=true as well (never in the normal
	 * suite, never alone: to run together on the test Business Central). One line, the first active item of the point
	 * de vente (-Dparafendri.location, PV-GREMDA by default) at its price, quantity 1, ticket number ZSTEST + the time;
	 * pushTicket, then pushTicket again: the second must find the first order (never twice). Creates one sales order.
	 */
	@Test
	@EnabledIfSystemProperty(named = "parafendri.live.post", matches = "true")
	@DisplayName("Live POST of one test ticket (only with -Dparafendri.live.post=true), then the same ticket again: no double")
	void liveTicketPost() throws IOException {
		ParafendriRestClient client = client();
		String code = System.getProperty("parafendri.location", "PV-GREMDA");
		ParafendriCollection<ParafendriStockPointRow> page = client.readStockPointsFirstPage(code, null);
		ParafendriStockPointRow item = page.getValue().stream()
				.filter(row -> row.getUnitPrice() != null && row.getUnitPrice().signum() > 0
						&& !Boolean.TRUE.equals(row.getItemBlocked()) && !Boolean.TRUE.equals(row.getItemSalesBlocked())
						&& !Boolean.TRUE.equals(row.getStockPointBlocked()))
				.findFirst().orElseThrow(() -> new AssertionError("no active item with a price at " + code));
		BigDecimal ttc = item.getUnitPrice()
				.multiply(BigDecimal.ONE.add(item.getVatPercent().movePointLeft(2)))
				.setScale(3, RoundingMode.HALF_UP);
		ErpTicketDTO ticket = new ErpTicketDTO();
		ticket.setTicketNumber("ZSTEST" + LocalDateTime.now().format(
				DateTimeFormatter.ofPattern("yyMMddHHmmss")));
		ticket.setSaleDate(LocalDateTime.now());
		ticket.setCashierSessionId("ZSTEST");
		ticket.setTotalAmount(ttc);
		ticket.setTotalAmountExcludingVat(item.getUnitPrice().setScale(3, RoundingMode.HALF_UP));
		ErpTicketLineDTO line = new ErpTicketLineDTO();
		line.setLineNumber(1);
		line.setItemExternalId(item.getItemNo().trim());
		line.setQuantity(BigDecimal.ONE);
		line.setUnitPrice(item.getUnitPrice());
		line.setTotalAmountIncludingVat(ttc);
		ticket.getLines().add(line);
		ParafendriConnector connector =
				new ParafendriConnector(client,
						new ErpStoreLocation() {
							@Override
							public String defaultLocationCode() {
								return code;
							}

							@Override
							public List<String> defaultResponsibilityCenters() {
								return new ArrayList<>();
							}
						});
		ErpOperationResult first = connector.pushTicket(ticket);
		System.out.println("POST " + ticket.getTicketNumber() + ": success " + first.isSuccess() + ", number "
				+ first.getExternalReference() + ", " + first.getMessage());
		assertTrue(first.isSuccess(), first.getMessage());
		ErpOperationResult again = connector.pushTicket(ticket);
		System.out.println("again: success " + again.isSuccess() + ", number " + again.getExternalReference() + ", "
				+ again.getMessage());
		assertTrue(again.isSuccess(), again.getMessage());
		assertTrue(first.getExternalReference().equals(again.getExternalReference()), "the same order, not a second");
	}

	@Test
	@DisplayName("Live connection test (GET only): company-id among the companies; step 1: the locations, the store's among them")
	void liveConnection() throws IOException {
		ParafendriRestClient client = client();

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

		// Step 6 (GET only): the lookup of a ticket by its number, as pushTicket makes it before a POST, and the point
		// de vente's customerNo (one stockPoints row; and how many values its rows carry)
		String lookup = "ZSLOOKUP-" + code;
		start = System.currentTimeMillis();
		try {
			List<ParafendriSalesOrderRow> orders = client.readSalesOrdersOfTicket(lookup);
			System.out.println("salesOrders ticketNo eq '" + lookup + "': " + orders.size() + " rows, in "
					+ (System.currentTimeMillis() - start) + " ms (" + client.salesOrdersAddress() + "): the no-double"
					+ " guard works");
		} catch (ParafendriReadException e) {
			// Business Central refusing the filter (ticketNo not a stored, filterable field): the export posts anyway
			assertTrue(ParafendriConnector.notFilterable(e), e.getMessage());
			System.out.println("salesOrders ticketNo eq '" + lookup + "': HTTP " + e.getStatus() + " not filterable, in "
					+ (System.currentTimeMillis() - start) + " ms: pushTicket posts anyway and the run warns once: "
					+ ParafendriConnector.NO_DOUBLE_GUARD);
		}
		start = System.currentTimeMillis();
		String customerNo = client.readCustomerNo(code);
		Set<String> customers = rows.stream().map(row -> row.getCustomerNo() == null ? "(none)" : row.getCustomerNo().trim())
				.collect(Collectors.toCollection(TreeSet::new));
		System.out.println("customerNo of " + code + ": " + customerNo + ", in " + (System.currentTimeMillis() - start)
				+ " ms; values on its " + rows.size() + " stockPoints rows: " + customers);

		// Step 7 (GET only): the lookup of a sales order's invoice by orderNo (the key), as requestInvoice makes it
		// before a POST: a number that is no order, so no row
		start = System.currentTimeMillis();
		List<ParafendriShipAndInvoiceRow> invoices = client.readShipAndInvoice(lookup);
		System.out.println("shipAndInvoice orderNo eq '" + lookup + "': " + invoices.size() + " rows, in "
				+ (System.currentTimeMillis() - start) + " ms (" + client.shipAndInvoiceUri(lookup) + ")");
		assertTrue(invoices.isEmpty(), "no invoice of an order that does not exist");
	}

	/**
	 * Step 7, a live POST of one invoice: runs ONLY with -Dparafendri.live.post=true and -Dparafendri.order=&lt;a sales
	 * order of a test ticket&gt; (e.g. the one liveTicketPost made; never in the normal suite, to run together on the test
	 * Business Central). requestInvoice, then requestInvoice again: the second must find the first invoice (never twice).
	 * Ships and invoices that order in Business Central; the PDF is not written anywhere.
	 */
	@Test
	@EnabledIfSystemProperty(named = "parafendri.live.post", matches = "true")
	@DisplayName("Live POST of one invoice (only with -Dparafendri.live.post=true and -Dparafendri.order), then again: no double")
	void liveInvoicePost() throws IOException {
		String orderNo = System.getProperty("parafendri.order", "").trim();
		assertTrue(!orderNo.isEmpty(), "-Dparafendri.order=<the sales order of a test ticket> is required");
		ParafendriConnector connector = new ParafendriConnector(client(), new ErpStoreLocation() {
			@Override
			public String defaultLocationCode() {
				return System.getProperty("parafendri.location", "PV-GREMDA");
			}

			@Override
			public List<String> defaultResponsibilityCenters() {
				return new ArrayList<>();
			}
		});
		ErpInvoiceResult first = connector.requestInvoice(orderNo, "ZSTEST", null);
		System.out.println("POST shipAndInvoice " + orderNo + ": success " + first.isSuccess() + ", invoice "
				+ first.getInvoiceNo() + ", PDF " + (first.getPdf() == null ? "none" : first.getPdf().length + " bytes")
				+ ", " + first.getMessage() + ", warnings " + first.getWarnings());
		assertTrue(first.isSuccess(), first.getMessage());
		ErpInvoiceResult again = connector.requestInvoice(orderNo, "ZSTEST", null);
		System.out.println("again: success " + again.isSuccess() + ", invoice " + again.getInvoiceNo() + ", "
				+ again.getMessage());
		assertTrue(again.isSuccess(), again.getMessage());
		assertTrue(first.getInvoiceNo().equals(again.getInvoiceNo()), "the same invoice, not a second");
	}

	private static void print(String level, ErpRead<?> read) {
		System.out.println(level + ": read " + read.getRead() + ", kept " + read.getKept() + ", left out "
				+ read.getLeftOut() + ", noted " + read.getNotes());
	}
}
