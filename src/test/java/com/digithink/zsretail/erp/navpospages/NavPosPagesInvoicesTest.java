package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.dto.PullOperationResult;
import com.digithink.zsretail.erp.engine.CatalogueImport;
import com.digithink.zsretail.erp.engine.CatalogueRun;
import com.digithink.zsretail.erp.engine.CatalogueState;
import com.digithink.zsretail.erp.engine.HeadOfficeCatalogue;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesSource;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.connector.NavPosPagesConnector;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCollection;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;
import com.digithink.zsretail.erp.spi.StockPoints;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.TextNode;


/**
 * Invoices from the ERP and the connector's answers, on the samples of src/test/resources/navpospages
 * ({@link NavPosPagesFixture}): the invoices page read by number (NavPosPagesInvoices), and NavPosPagesConnector handing
 * the runs and keeping only their summary. Code cleanup step 3: the invoice and connector tests of NavPosPagesSyncTest,
 * same expectations.
 */
class NavPosPagesInvoicesTest extends NavPosPagesFixture {

	@Test
	@DisplayName("The connector hands the run and keeps only its summary for the communications log")
	void connectorSummary() {
		NavPosPagesConnector connector = new NavPosPagesConnector(engine, invoiceReads);
		List<ErpItemFamilyDTO> handed = connector.fetchItemFamilies(null);
		assertEquals(13, handed.size());
		PullOperationResult<?> log = connector.getLastPullOperationResult();
		assertEquals(NavPosPagesTestSupport.COMPANY_URL + "ItemCategory", log.getUrl());
		Map<?, ?> summary = (Map<?, ?>) log.getRawResponse();
		assertEquals("families", summary.get("run"));
		assertEquals(50, summary.get("read"));
		assertEquals(13, summary.get("handed"));
		assertFalse(summary.values().stream().anyMatch(value -> value instanceof Collection), "never the list");
		connector.clearLastPullOperationResult();
		assertNull(connector.getLastPullOperationResult());
	}

	@Test
	@DisplayName("The connector's items fetch applies the run and hands nothing more to the job; the summary says what was applied")
	void connectorItemsApplied() {
		families();
		subFamilies();
		NavPosPagesConnector connector = new NavPosPagesConnector(engine, invoiceReads);
		assertTrue(connector.fetchItems(null).isEmpty(), "already applied, packet after packet");
		assertEquals(50, headOffice.items.size());
		Map<?, ?> summary = (Map<?, ?>) connector.getLastPullOperationResult().getRawResponse();
		assertEquals("items", summary.get("run"));
		assertEquals(50, summary.get("new"));
		assertEquals(50, summary.get("applied"));
		assertEquals(1, summary.get("packets"));
		assertFalse(summary.values().stream().anyMatch(value -> value instanceof Collection), "never the list");

		assertTrue(connector.fetchItemBarcodes(null).isEmpty(), "the barcode run too: applied until caught up");
		assertEquals(49, headOffice.barcodes.size());
		Map<?, ?> barcodes = (Map<?, ?>) connector.getLastPullOperationResult().getRawResponse();
		assertEquals("barcodes", barcodes.get("run"));
		assertEquals(49, barcodes.get("applied"));
		assertEquals(Boolean.TRUE, barcodes.get("caughtUp"));
	}

	// ─── Invoices from the ERP, step (a) ────────────────────────

	private static NavPosInvoiceRow invoice(String number) {
		NavPosInvoiceRow row = new NavPosInvoiceRow();
		row.set("No", TextNode.valueOf(number));
		row.set("Sell_to_Customer_No", TextNode.valueOf("C-1"));
		return row.resolveLines("FactureFranchiseSalesInvLines");
	}

	private static List<String> numbers(List<ErpSupplyInvoiceDTO> invoices) {
		return invoices.stream().map(ErpSupplyInvoiceDTO::getNumber).collect(Collectors.toList());
	}

	/** The ERP's invoices of 2024, 2025 and 2026; today is 2026-10-09. */
	private void invoicesOfTheErp() {
		for (String number : new String[] { "FVV24000000009", "FVV25000000001", "FVV25000000002", "FVV25000000003",
				"FVV26000000001", "FVV26000000002" }) {
			erp.invoices.add(invoice(number));
		}
		invoiceReads.setToday(() -> java.time.LocalDate.of(2026, 10, 9));
	}

	private static Map<String, String> highest(String... prefixAndNumber) {
		Map<String, String> highest = new HashMap<>();
		for (int i = 0; i < prefixAndNumber.length; i += 2) {
			highest.put(prefixAndNumber[i], prefixAndNumber[i + 1]);
		}
		return highest;
	}

	@Test
	@DisplayName("2.2.1: invoices read again by number (held by 2.2.0 for a decimal): only those, with the year prefix of their number")
	void invoicesByNumbers() {
		invoicesOfTheErp();
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoiceReads.invoicesByNumbers(Arrays.asList("FVV25000000002", "FVV26000000001",
				"FVV26999999999"));
		assertEquals(Arrays.asList("numbers [FVV25000000002, FVV26000000001, FVV26999999999]"), erp.invoiceReads);
		assertEquals(Arrays.asList("FVV25000000002", "FVV26000000001"), numbers(run.getHanded()),
				"a number the page no longer has is not in the answer");
		assertEquals(Arrays.asList("FVV25", "FVV26"),
				run.getHanded().stream().map(ErpSupplyInvoiceDTO::getYearPrefix).collect(Collectors.toList()));
		assertEquals(3, run.getSummary().get("readAgain"));

		erp.invoiceReads.clear();
		assertTrue(invoiceReads.invoicesByNumbers(new ArrayList<>()).getHanded().isEmpty());
		assertTrue(erp.invoiceReads.isEmpty(), "no number: no GET");
	}

	@Test
	@DisplayName("Invoices, setting empty and nothing saved: from the year of the oldest invoice on the page up to this year; an empty page reads nothing")
	void invoicesSettingEmpty() {
		invoicesOfTheErp();
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoiceReads.invoices(new HashMap<>());
		assertEquals(Arrays.asList("first FVV", "FVV24 after null", "FVV25 after null", "FVV26 after null"), erp.invoiceReads);
		assertEquals(Arrays.asList("FVV24000000009", "FVV25000000001", "FVV25000000002", "FVV25000000003",
				"FVV26000000001", "FVV26000000002"), numbers(run.getHanded()));
		assertEquals("the oldest invoice on the page: FVV24000000009", run.getSummary().get("startingPoint"));

		erp.invoices.clear();
		erp.invoiceReads.clear();
		CatalogueRun<ErpSupplyInvoiceDTO> empty = invoiceReads.invoices(null);
		assertTrue(empty.getHanded().isEmpty());
		assertEquals(Arrays.asList("first FVV"), erp.invoiceReads, "one GET, no year read");
		assertEquals("no invoice on the page", empty.getSummary().get("startingPoint"));
	}

	@Test
	@DisplayName("Invoices, a starting number and nothing saved: its year after it, the next years from their start; an older year never")
	void invoicesStartingNumber() {
		invoicesOfTheErp();
		headOffice.invoicesReadAfter = "FVV25000000001";
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoiceReads.invoices(new HashMap<>());
		assertEquals(Arrays.asList("FVV25 after FVV25000000001", "FVV26 after null"), erp.invoiceReads);
		assertEquals(Arrays.asList("FVV25000000002", "FVV25000000003", "FVV26000000001", "FVV26000000002"),
				numbers(run.getHanded()));
		assertEquals("FVV25", run.getHanded().get(0).getYearPrefix(), "the prefix of its read (step b)");
		assertEquals("after FVV25000000001 (General Setup)", run.getSummary().get("startingPoint"));
	}

	@Test
	@DisplayName("Invoices, some saved: each year after its highest number from the oldest saved year; the setting, even changed, reads no old invoice")
	void invoicesAfterSaved() {
		invoicesOfTheErp();
		headOffice.invoicesReadAfter = "FVV24000000001"; // changed to an older number after the go-live
		CatalogueRun<ErpSupplyInvoiceDTO> run = invoiceReads.invoices(highest("FVV25", "FVV25000000001", "FVV26", "FVV26000000001"));
		assertEquals(Arrays.asList("FVV25 after FVV25000000001", "FVV26 after FVV26000000001"), erp.invoiceReads);
		assertEquals(Arrays.asList("FVV25000000002", "FVV25000000003", "FVV26000000002"), numbers(run.getHanded()));
		assertEquals("C-1", run.getHanded().get(0).getCustomerNo());
		assertEquals("FVV26", run.getHanded().get(2).getYearPrefix());
		assertEquals(3, run.count("read"));
		assertEquals(3, run.count("handed"));
		Map<?, ?> years = (Map<?, ?>) run.getSummary().get("years");
		assertEquals("{after=FVV26000000001, read=1}", String.valueOf(years.get("FVV26")));
		assertEquals("{after=FVV25000000001, read=2}", String.valueOf(years.get("FVV25")));
		assertEquals(NavPosPagesTestSupport.COMPANY_URL + "FactureFranchise", run.getUrl());

		erp.invoiceReads.clear();
		assertTrue(invoiceReads.invoices(highest("FVV25", "FVV25000000003")).getHanded().size() == 2, "2026 from its start");
		assertEquals(Arrays.asList("FVV25 after FVV25000000003", "FVV26 after null"), erp.invoiceReads);
	}

	@Test
	@DisplayName("Invoices: at most max-per-run per year")
	void invoicesMaxPerRun() {
		invoicesOfTheErp();
		properties.getInvoices().setMaxPerRun(1);
		assertEquals(Arrays.asList("FVV24000000009", "FVV25000000001", "FVV26000000001"),
				numbers(invoiceReads.invoices(null).getHanded()));
	}

	@Test
	@DisplayName("Invoices: a number's year (FVV26... is 2026) and what looks like an invoice number")
	void invoiceNumbers() {
		NavPosPagesProperties.Invoices settings = properties.getInvoices();
		assertEquals(Integer.valueOf(2026), settings.yearOf("FVV26000000123"));
		assertEquals(null, settings.yearOf("FA26000000123"));
		assertEquals(null, settings.yearOf("FVVX6"));
		assertTrue(settings.looksLikeNumber(" FVV26000000123 "));
		for (String wrong : new String[] { "FVV26", "26000000123", "FVV2600A", "FA26000000123", "" }) {
			assertFalse(settings.looksLikeNumber(wrong), wrong);
		}
	}

	@Test
	@DisplayName("Invoices: dry run reads and summarises, hands nothing; the connector keeps the summary, never the list")
	void invoicesDryRunAndConnector() {
		invoicesOfTheErp();
		properties.setDryRun(true);
		CatalogueRun<ErpSupplyInvoiceDTO> dry = invoiceReads.invoices(null);
		assertTrue(dry.getHanded().isEmpty());
		assertEquals(6, dry.count("read"));
		assertEquals(0, dry.count("handed"));

		properties.setDryRun(false);
		NavPosPagesConnector connector = new NavPosPagesConnector(engine, invoiceReads);
		assertEquals(6, connector.fetchSupplyInvoices(null).size());
		Map<?, ?> summary = (Map<?, ?>) connector.getLastPullOperationResult().getRawResponse();
		assertEquals("invoices", summary.get("run"));
		assertEquals(6, summary.get("handed"));
		assertFalse(summary.values().stream().anyMatch(value -> value instanceof Collection), "never the list");
	}

}
