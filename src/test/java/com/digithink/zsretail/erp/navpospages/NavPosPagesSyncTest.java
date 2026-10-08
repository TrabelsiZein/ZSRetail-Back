package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesSource;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.connector.NavPosPagesConnector;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCollection;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesHeadOffice;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesState;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesSync;
import com.digithink.zsretail.erp.navpospages.sync.NavPosRun;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * ERP catalogue, step 6: changes only, on the samples of src/test/resources/navpospages. The head office tables are a
 * fake that applies what the import (ErpItemBootstrapService) applies; the ERP a fake source; the state table a map.
 * No live call.
 */
class NavPosPagesSyncTest {

	private FakeSource erp;
	private FakeHeadOffice headOffice;
	private FakeState state;
	private NavPosPagesProperties properties;
	private NavPosPagesSync sync;

	@BeforeEach
	void setUp() {
		erp = new FakeSource();
		erp.categories = NavPosPagesTestSupport.rows("ItemCategory", new TypeReference<NavPosCollection<NavPosCategoryRow>>() {
		});
		erp.items = NavPosPagesTestSupport.rows("PointStockPOS", new TypeReference<NavPosCollection<NavPosStockRow>>() {
		});
		erp.barcodes = NavPosPagesTestSupport.rows("ItemBarCodePOS", new TypeReference<NavPosCollection<NavPosBarcodeRow>>() {
		});
		headOffice = new FakeHeadOffice();
		state = new FakeState();
		properties = NavPosPagesTestSupport.properties();
		sync = new NavPosPagesSync(erp, headOffice, state, properties);
	}

	// ─── Running the four jobs, the import applying what it is handed ────

	private NavPosRun<ErpItemFamilyDTO> families() {
		NavPosRun<ErpItemFamilyDTO> run = sync.families();
		headOffice.importFamilies(run.getHanded());
		return run;
	}

	private NavPosRun<ErpItemSubFamilyDTO> subFamilies() {
		NavPosRun<ErpItemSubFamilyDTO> run = sync.subFamilies();
		headOffice.importSubFamilies(run.getHanded());
		return run;
	}

	private NavPosRun<ErpItemDTO> items() {
		NavPosRun<ErpItemDTO> run = sync.items();
		headOffice.importItems(run.getHanded());
		return run;
	}

	private NavPosRun<ErpItemBarcodeDTO> barcodes() {
		NavPosRun<ErpItemBarcodeDTO> run = sync.barcodes();
		headOffice.importBarcodes(run.getHanded());
		return run;
	}

	/** Families, sub-families and every item at the head office (no cap reached). */
	private void loadCatalogue() {
		families();
		subFamilies();
		items();
		assertEquals(0, items().getHanded().size());
	}

	/** The whole catalogue and every barcode. */
	private void loadAll() {
		loadCatalogue();
		for (int i = 0; i < 10 && !Boolean.TRUE.equals(barcodes().getSummary().get("caughtUp")); i++) {
			// until the cursor reaches the end
		}
	}

	private static List<String> codes(List<ErpItemDTO> items) {
		return items.stream().map(ErpItemDTO::getCode).collect(Collectors.toList());
	}

	private NavPosStockRow erpItem(String code) {
		return erp.items.stream().filter(row -> row.getItemNo().equals(code)).findFirst().get();
	}

	// ─── First load ─────────────────────────────────────────────

	@Test
	@DisplayName("First load with the cap over several runs: families, then sub-families, items, barcodes, each waiting for the one before")
	void firstLoadWithCap() {
		properties.setMaxChangesPerRun(20);
		properties.setBarcodePageSize(15);

		NavPosRun<ErpItemSubFamilyDTO> early = sync.subFamilies();
		assertEquals("waiting for families: 13 families of the ERP not at the head office yet",
				early.getSummary().get("waiting"));
		assertTrue(sync.items().getHanded().isEmpty());
		assertEquals("waiting for items: no items run yet", sync.barcodes().getSummary().get("waiting"));

		NavPosRun<ErpItemFamilyDTO> f = families();
		assertEquals(13, f.getHanded().size());
		assertEquals(13, f.count("new"));
		assertEquals(0, families().getHanded().size(), "nothing changed");

		NavPosRun<ErpItemSubFamilyDTO> s1 = subFamilies();
		assertEquals(20, s1.getHanded().size());
		assertEquals(14, s1.count("heldBackByCap"));
		assertEquals("waiting for sub-families: 14 sub-families of the ERP not at the head office yet",
				sync.items().getSummary().get("waiting"), "items wait while sub-families are not all saved");
		assertEquals(14, subFamilies().getHanded().size());
		assertEquals(0, subFamilies().getHanded().size());

		List<String> handed = new ArrayList<>();
		int[] sizes = new int[4];
		for (int run = 0; run < 4; run++) {
			NavPosRun<ErpItemDTO> items = items();
			sizes[run] = items.getHanded().size();
			handed.addAll(codes(items.getHanded()));
			if (run < 3) {
				NavPosRun<ErpItemBarcodeDTO> waiting = barcodes();
				assertTrue(waiting.getHanded().isEmpty());
				assertTrue(waiting.getSummary().get("waiting").toString().startsWith("waiting for items: "),
						waiting.toString());
			}
		}
		assertEquals("20,20,10,0", sizes[0] + "," + sizes[1] + "," + sizes[2] + "," + sizes[3]);
		assertEquals(handed.stream().sorted().collect(Collectors.toList()), handed, "a stable order, by code");
		assertEquals(50, headOffice.items.size());
		assertEquals("FLORELLE VAO LES PETALES N° 01", headOffice.items.get("000001").name);

		// Barcodes: pages of 15, cap 20, each page handed then confirmed
		int runs = 0;
		while (!Boolean.TRUE.equals(barcodes().getSummary().get("caughtUp")) && runs++ < 20) {
		}
		assertEquals(49, headOffice.barcodes.size(), "the barcode of item 0000527, outside the location, left out");
		assertFalse(headOffice.barcodes.values().stream().anyMatch(b -> b.itemCode.equals("0000527")));
		assertEquals("48793", state.get("barcodes.cursor"));
		assertTrue(barcodes().getHanded().isEmpty(), "nothing new");
	}

	@Test
	@DisplayName("A run with nothing changed hands over nothing, for every kind")
	void nothingChanged() {
		loadAll();
		assertEquals(0, families().getHanded().size());
		assertEquals(0, subFamilies().getHanded().size());
		NavPosRun<ErpItemDTO> items = items();
		assertEquals(0, items.getHanded().size());
		assertEquals(0, items.count("changed"));
		assertEquals(0, items.count("new"));
		assertEquals(0, barcodes().getHanded().size());
		assertEquals(50, items.count("read"));
	}

	// ─── Items ──────────────────────────────────────────────────

	@Test
	@DisplayName("A price change hands that item only; the stored price before VAT is never seen as changed")
	void priceChange() {
		loadCatalogue();
		erpItem("000001").setUnitPrice(new BigDecimal("2.95"));
		NavPosRun<ErpItemDTO> run = items();
		assertEquals(Arrays.asList("000001"), codes(run.getHanded()));
		assertEquals(1, run.count("changed"));
		assertEquals(new BigDecimal("2.4789915966"), run.getHanded().get(0).getUnitPrice());
		assertEquals(0, items().getHanded().size(), "unchanged once saved");
	}

	@Test
	@DisplayName("Price 0: the item is handed inactive; it is active again when the ERP gives it a price")
	void zeroPriceInactiveThenActive() {
		loadCatalogue();
		erpItem("000001").setUnitPrice(BigDecimal.ZERO);
		NavPosRun<ErpItemDTO> zero = items();
		assertEquals(Arrays.asList("000001"), codes(zero.getHanded()));
		assertEquals(Boolean.FALSE, zero.getHanded().get(0).getActive());
		assertFalse(headOffice.items.get("000001").isActive());
		assertEquals(0, items().getHanded().size());

		erpItem("000001").setUnitPrice(new BigDecimal("2.45"));
		NavPosRun<ErpItemDTO> back = items();
		assertEquals(Boolean.TRUE, back.getHanded().get(0).getActive());
		assertTrue(headOffice.items.get("000001").isActive());
	}

	@Test
	@DisplayName("An ERP item gone from the location is handed inactive (never deleted); hand-made items and packs are never touched")
	void itemGone() {
		loadCatalogue();
		headOffice.items.put("MAIN-1", item("MAIN-1", null, true)); // made at the head office (a pack or any item)
		headOffice.items.put("TAX_STAMP", item("TAX_STAMP", null, true));
		erp.items.removeIf(row -> row.getItemNo().equals("000001"));
		NavPosRun<ErpItemDTO> run = items();
		assertEquals(Arrays.asList("000001"), codes(run.getHanded()));
		ErpItemDTO gone = run.getHanded().get(0);
		assertEquals(Boolean.FALSE, gone.getActive());
		assertEquals("FLORELLE VAO LES PETALES N° 01", gone.getName(), "the head office values kept");
		assertEquals(new BigDecimal("2.0588235294"), gone.getUnitPrice());
		assertEquals(1, run.count("deactivated"));
		assertFalse(headOffice.items.get("000001").isActive());
		assertTrue(headOffice.items.containsKey("000001"), "never deleted");
		assertTrue(headOffice.items.get("MAIN-1").isActive());
		assertEquals(0, items().getHanded().size(), "already inactive: nothing more");
	}

	@Test
	@DisplayName("96 % of the ERP items gone: all handed inactive, no guard; hand-made items, packs and the tax stamp untouched")
	void mostItemsGone() {
		loadCatalogue();
		headOffice.items.put("MAIN-1", item("MAIN-1", null, true)); // made at the head office (a pack or any item)
		headOffice.items.put("TAX_STAMP", item("TAX_STAMP", null, true));
		String taxStamp = erp.items.get(49).getItemNo();
		headOffice.taxStamp = taxStamp; // the ERP item of TAX_STAMP_ERP_ITEM_CODE, at the head office with its ERP id
		List<String> kept = Arrays.asList(erp.items.get(0).getItemNo(), erp.items.get(1).getItemNo());
		erp.items.subList(2, 50).clear(); // 48 of 50 = 96 % missing
		NavPosRun<ErpItemDTO> run = items();
		assertEquals(47, run.count("deactivated"), "every missing ERP item but the tax stamp");
		assertEquals(47, run.getHanded().size());
		assertTrue(run.getHanded().stream().allMatch(dto -> Boolean.FALSE.equals(dto.getActive())));
		assertNull(run.getSummary().get("guard"));
		assertEquals(52, headOffice.items.size(), "never deleted");
		for (NavPosPagesHeadOffice.Item here : headOffice.items.values()) {
			boolean untouched = kept.contains(here.code) || here.code.equals(taxStamp) || !here.fromErp();
			assertEquals(untouched, here.isActive(), here.code);
		}
		assertEquals(0, items().getHanded().size(), "already inactive: nothing more");
	}

	@Test
	@DisplayName("An empty ERP answer deactivates nothing and says so")
	void emptyAnswer() {
		loadCatalogue();
		erp.items.clear();
		NavPosRun<ErpItemDTO> run = items();
		assertTrue(run.getHanded().isEmpty());
		assertEquals("the ERP answered no item for the location: 50 items not deactivated", run.getSummary().get("guard"));
		assertTrue(headOffice.items.values().stream().allMatch(NavPosPagesHeadOffice.Item::isActive));
	}

	@Test
	@DisplayName("The ERP item of TAX_STAMP_ERP_ITEM_CODE is left out (the import never saves it, the barcodes would wait for ever)")
	void taxStampItem() {
		headOffice.taxStamp = "000001";
		families();
		subFamilies();
		NavPosRun<ErpItemDTO> run = items();
		assertFalse(codes(run.getHanded()).contains("000001"));
		assertEquals(Integer.valueOf(1), ((Map<?, ?>) run.getSummary().get("leftOut")).get(NavPosPagesSync.TAX_STAMP_ITEM));
		assertEquals("49", state.get("items.pending"), "counted before handing over, without it");
		items();
		assertEquals("0", state.get("items.pending"));
	}

	// ─── Barcodes ───────────────────────────────────────────────

	@Test
	@DisplayName("Barcodes wait for the items: no item run yet, or new items not saved yet; the cursor does not move")
	void barcodesWaitForItems() {
		properties.setMaxChangesPerRun(30);
		families();
		subFamilies(); // 30 of 34
		subFamilies();
		NavPosRun<ErpItemBarcodeDTO> noRun = sync.barcodes();
		assertEquals("waiting for items: no items run yet", noRun.getSummary().get("waiting"));
		items(); // 30 of 50
		NavPosRun<ErpItemBarcodeDTO> pending = sync.barcodes();
		assertEquals("waiting for items: 50 new items not at the head office yet", pending.getSummary().get("waiting"));
		assertTrue(pending.getHanded().isEmpty());
		assertNull(state.get("barcodes.cursor"));
		items(); // the last 20; counted 20 before handing
		assertTrue(sync.barcodes().getSummary().containsKey("waiting"));
		items(); // counts 0: the last batch was saved
		assertFalse(barcodes().getHanded().isEmpty());
	}

	@Test
	@DisplayName("The cursor over pages with gaps in Entry_No; a barcode of an item outside the location is left out and passed")
	void cursorOverGaps() {
		loadCatalogue();
		erp.barcodes = new ArrayList<>(Arrays.asList(new NavPosBarcodeRow("000001", "B1", 5L),
				new NavPosBarcodeRow("OUTSIDE", "B2", 9L), new NavPosBarcodeRow("000002", "B3", 120L),
				new NavPosBarcodeRow("0000016", "B4", 121L), new NavPosBarcodeRow("0000017", "B5", 5000L)));
		properties.setBarcodePageSize(2);

		NavPosRun<ErpItemBarcodeDTO> first = barcodes();
		assertEquals(Arrays.asList("B1"), first.getHanded().stream().map(ErpItemBarcodeDTO::getBarcode)
				.collect(Collectors.toList()));
		assertEquals(Long.valueOf(0), first.getSummary().get("cursorTo"), "not past B1 before it is saved");
		assertEquals(Integer.valueOf(1),
				((Map<?, ?>) first.getSummary().get("leftOut")).get(NavPosPagesSync.ITEM_NOT_AT_HEAD_OFFICE));

		NavPosRun<ErpItemBarcodeDTO> second = barcodes(); // page 1 confirmed (B1 saved, B2 left out), page 2 handed
		assertEquals(Arrays.asList("B3", "B4"), second.getHanded().stream().map(ErpItemBarcodeDTO::getBarcode)
				.collect(Collectors.toList()));
		assertEquals(Long.valueOf(9), second.getSummary().get("cursorTo"));
		NavPosRun<ErpItemBarcodeDTO> third = barcodes();
		assertEquals(Arrays.asList("B5"), third.getHanded().stream().map(ErpItemBarcodeDTO::getBarcode)
				.collect(Collectors.toList()));
		assertEquals(Long.valueOf(121), third.getSummary().get("cursorTo"));
		NavPosRun<ErpItemBarcodeDTO> last = barcodes();
		assertTrue(last.getHanded().isEmpty());
		assertEquals(Boolean.TRUE, last.getSummary().get("caughtUp"));
		assertEquals("5000", state.get("barcodes.cursor"));
		assertFalse(headOffice.barcodes.containsKey("B2"));
		assertEquals("000002", headOffice.barcodes.get("B3").itemCode);
	}

	@Test
	@DisplayName("The cursor does not move when the previous batch was not saved: the same rows are handed again")
	void cursorNotMovedWhenNotSaved() {
		loadCatalogue();
		NavPosRun<ErpItemBarcodeDTO> handedNotSaved = sync.barcodes(); // the import fails: nothing saved
		assertEquals(49, handedNotSaved.getHanded().size());
		assertEquals("0", state.get("barcodes.cursor"));
		NavPosRun<ErpItemBarcodeDTO> again = sync.barcodes();
		assertEquals(49, again.getHanded().size(), "handed again");
		assertEquals("0", state.get("barcodes.cursor"));
		headOffice.importBarcodes(again.getHanded().subList(0, 10)); // a part saved
		NavPosRun<ErpItemBarcodeDTO> rest = barcodes();
		assertEquals(39, rest.getHanded().size());
		// The cursor stops just before the first row still to save (the 11th of the rows handed, by Entry_No)
		long firstUnsaved = erp.barcodes.stream().filter(row -> headOffice.items.containsKey(row.getItemNo()))
				.map(NavPosBarcodeRow::getEntryNo).sorted().skip(10).findFirst().get();
		long expected = erp.barcodes.stream().map(NavPosBarcodeRow::getEntryNo).filter(entry -> entry < firstUnsaved)
				.max(Long::compare).get();
		assertEquals(Long.valueOf(expected), rest.getSummary().get("cursorTo"));
		assertEquals(String.valueOf(expected), state.get("barcodes.cursor"));
	}

	@Test
	@DisplayName("A new item after the first load gets its barcodes by item number, then the list is cleared")
	void newItemGetsItsBarcodes() {
		erp.items.removeIf(row -> row.getItemNo().equals("000002"));
		loadAll();
		assertFalse(headOffice.items.containsKey("000002"));
		assertFalse(headOffice.barcodes.containsKey("000002"), "its barcode was left out, the cursor passed it");

		erp.items.add(new NavPosStockRow("000002", "", "NEW ONE", new BigDecimal("2.45"), "FAM-ONG-MAQ", "SF-VEO-ONG-MAQ"));
		items();
		assertEquals("1", state.get("needs-barcodes:000002"));
		assertEquals("waiting for items: 1 new items not at the head office yet", sync.barcodes().getSummary().get("waiting"));
		items(); // counts 0: the new item was saved
		NavPosRun<ErpItemBarcodeDTO> run = barcodes();
		assertEquals(Arrays.asList("000002"), run.getHanded().stream().map(ErpItemBarcodeDTO::getBarcode)
				.collect(Collectors.toList()));
		assertEquals(Arrays.asList("000002"), erp.askedItems);
		assertEquals("1", state.get("needs-barcodes:000002"), "kept until its barcodes are seen at the head office");
		barcodes();
		assertNull(state.get("needs-barcodes:000002"));
		assertEquals("000002", headOffice.barcodes.get("000002").itemCode);
	}

	@Test
	@DisplayName("During the first load no item is listed for its barcodes: the cursor covers them")
	void noListDuringFirstLoad() {
		loadCatalogue();
		assertTrue(state.keysStartingWith("needs-barcodes:").isEmpty());
	}

	// ─── Dry run, summary ───────────────────────────────────────

	@Test
	@DisplayName("Dry run: reads, compares and summarises; hands nothing; the state table is not touched")
	void dryRun() {
		properties.setDryRun(true);
		NavPosRun<ErpItemFamilyDTO> f = sync.families();
		assertTrue(f.getHanded().isEmpty());
		assertEquals(13, f.count("new"));
		assertEquals(0, f.count("handed"));
		assertEquals(Boolean.TRUE, f.getSummary().get("dryRun"));
		assertTrue(state.values.isEmpty());

		properties.setDryRun(false);
		loadCatalogue();
		erpItem("000001").setUnitPrice(new BigDecimal("9"));
		Map<String, String> before = new HashMap<>(state.values);
		properties.setDryRun(true);
		NavPosRun<ErpItemDTO> items = sync.items();
		assertTrue(items.getHanded().isEmpty());
		assertEquals(1, items.count("changed"));
		NavPosRun<ErpItemBarcodeDTO> barcodes = sync.barcodes();
		assertTrue(barcodes.getHanded().isEmpty());
		assertEquals(49, barcodes.count("toHand"));
		assertEquals(before, state.values);
	}

	@Test
	@DisplayName("The connector hands the run and keeps only its summary for the communications log")
	void connectorSummary() {
		NavPosPagesConnector connector = new NavPosPagesConnector(sync);
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

	private void invoicesOfTheErp() {
		for (String number : new String[] { "FVV24000000009", "FVV25000000001", "FVV25000000002", "FVV25000000003",
				"FVV26000000001", "FVV26000000002" }) {
			erp.invoices.add(invoice(number));
		}
		properties.getInvoices().setYears(Arrays.asList(2026, 2025));
	}

	private static Map<String, String> highest(String... prefixAndNumber) {
		Map<String, String> highest = new HashMap<>();
		for (int i = 0; i < prefixAndNumber.length; i += 2) {
			highest.put(prefixAndNumber[i], prefixAndNumber[i + 1]);
		}
		return highest;
	}

	@Test
	@DisplayName("Invoices: without years nothing is read")
	void invoicesNotConfigured() {
		erp.invoices.add(invoice("FVV26000000001"));
		NavPosRun<ErpSupplyInvoiceDTO> run = sync.invoices(new HashMap<>());
		assertTrue(run.getHanded().isEmpty());
		assertTrue(erp.invoiceReads.isEmpty());
		assertEquals("erp.navpospages.invoices.years is not set", run.getSummary().get("notConfigured"));
	}

	@Test
	@DisplayName("Invoices: each year after its highest number, merged by number; another year's number never read")
	void invoicesByYear() {
		invoicesOfTheErp();
		NavPosRun<ErpSupplyInvoiceDTO> run = sync.invoices(highest("FVV25", "FVV25000000001", "FVV26", "FVV26000000001"));
		assertEquals(Arrays.asList("FVV26 after FVV26000000001", "FVV25 after FVV25000000001"), erp.invoiceReads);
		assertEquals(Arrays.asList("FVV25000000002", "FVV25000000003", "FVV26000000002"), numbers(run.getHanded()));
		assertEquals("C-1", run.getHanded().get(0).getCustomerNo());
		assertEquals("FVV25", run.getHanded().get(0).getYearPrefix(), "the prefix of its read (step b)");
		assertEquals("FVV26", run.getHanded().get(2).getYearPrefix());
		assertEquals(3, run.count("read"));
		assertEquals(3, run.count("handed"));
		Map<?, ?> years = (Map<?, ?>) run.getSummary().get("years");
		assertEquals("{after=FVV26000000001, read=1}", String.valueOf(years.get("FVV26")));
		assertEquals("{after=FVV25000000001, read=2}", String.valueOf(years.get("FVV25")));
		assertEquals(NavPosPagesTestSupport.COMPANY_URL + "FactureFranchise", run.getUrl());

		erp.invoiceReads.clear();
		assertEquals(Arrays.asList("FVV25000000001", "FVV25000000002", "FVV25000000003", "FVV26000000001",
				"FVV26000000002"), numbers(sync.invoices(null).getHanded()), "first run: every invoice of the two years");
		assertEquals(Arrays.asList("FVV26 after null", "FVV25 after null"), erp.invoiceReads);
	}

	@Test
	@DisplayName("Invoices: the start number only for its year and only without a number of that year; max-per-run per year")
	void invoicesStartNumberAndMax() {
		invoicesOfTheErp();
		properties.getInvoices().setStartNumber("FVV26000000001");
		assertEquals(Arrays.asList("FVV25000000001", "FVV25000000002", "FVV25000000003", "FVV26000000002"),
				numbers(sync.invoices(new HashMap<>()).getHanded()));
		assertEquals(Arrays.asList("FVV26 after FVV26000000001", "FVV25 after null"), erp.invoiceReads);

		erp.invoiceReads.clear();
		assertTrue(sync.invoices(highest("FVV26", "FVV26000000002", "FVV25", "FVV25000000003")).getHanded().isEmpty());
		assertEquals(Arrays.asList("FVV26 after FVV26000000002", "FVV25 after FVV25000000003"), erp.invoiceReads,
				"the head office's number wins over the start number");

		properties.getInvoices().setStartNumber(null);
		properties.getInvoices().setMaxPerRun(1);
		assertEquals(Arrays.asList("FVV25000000001", "FVV26000000001"), numbers(sync.invoices(null).getHanded()));
	}

	@Test
	@DisplayName("Invoices: dry run reads and summarises, hands nothing; the connector keeps the summary, never the list")
	void invoicesDryRunAndConnector() {
		invoicesOfTheErp();
		properties.setDryRun(true);
		NavPosRun<ErpSupplyInvoiceDTO> dry = sync.invoices(null);
		assertTrue(dry.getHanded().isEmpty());
		assertEquals(5, dry.count("read"));
		assertEquals(0, dry.count("handed"));

		properties.setDryRun(false);
		NavPosPagesConnector connector = new NavPosPagesConnector(sync);
		assertEquals(5, connector.fetchSupplyInvoices(null).size());
		Map<?, ?> summary = (Map<?, ?>) connector.getLastPullOperationResult().getRawResponse();
		assertEquals("invoices", summary.get("run"));
		assertEquals(5, summary.get("handed"));
		assertFalse(summary.values().stream().anyMatch(value -> value instanceof Collection), "never the list");
	}

	// ─── Fakes ──────────────────────────────────────────────────

	private static NavPosPagesHeadOffice.Item item(String code, String erpId, boolean active) {
		return new NavPosPagesHeadOffice.Item(code, code, "", 1.0, 19, active, erpId, null, null, null, null);
	}

	/** The ERP: the three pages in memory; barcodes by Entry_No in pages of barcode-page-size. */
	final class FakeSource implements NavPosPagesSource {
		List<NavPosCategoryRow> categories = new ArrayList<>();
		List<NavPosStockRow> items = new ArrayList<>();
		List<NavPosBarcodeRow> barcodes = new ArrayList<>();
		List<String> askedItems = new ArrayList<>();

		@Override
		public List<NavPosCategoryRow> readCategories() {
			return new ArrayList<>(categories);
		}

		@Override
		public List<NavPosStockRow> readItems() {
			return new ArrayList<>(items);
		}

		@Override
		public List<NavPosBarcodeRow> readBarcodesAfter(long entryNo) {
			return barcodes.stream().filter(row -> row.getEntryNo() > entryNo)
					.sorted(Comparator.comparing(NavPosBarcodeRow::getEntryNo)).limit(properties.getBarcodePageSize())
					.collect(Collectors.toList());
		}

		@Override
		public List<NavPosBarcodeRow> readBarcodesOfItems(List<String> itemNos) {
			askedItems.addAll(itemNos);
			return barcodes.stream().filter(row -> itemNos.contains(row.getItemNo())).collect(Collectors.toList());
		}

		/** Invoices from the ERP: the invoices page, by number; each read written "FVV26 after FVV26000000001". */
		List<NavPosInvoiceRow> invoices = new ArrayList<>();
		List<String> invoiceReads = new ArrayList<>();

		@Override
		public List<NavPosInvoiceRow> readInvoicesAfter(String yearPrefix, String afterNumber) {
			invoiceReads.add(yearPrefix + " after " + afterNumber);
			return invoices.stream().filter(row -> row.text("No").startsWith(yearPrefix))
					.filter(row -> afterNumber == null || row.text("No").compareTo(afterNumber) > 0)
					.sorted(Comparator.comparing(row -> row.text("No")))
					.limit(properties.getInvoices().getMaxPerRun()).collect(Collectors.toList());
		}

		@Override
		public String pageUrl(String page) {
			return NavPosPagesTestSupport.COMPANY_URL + page;
		}
	}

	/** The head office tables, and the import as ErpItemBootstrapService applies it. */
	static final class FakeHeadOffice implements NavPosPagesHeadOffice {
		final Map<String, Family> families = new TreeMap<>();
		final Map<String, SubFamily> subFamilies = new TreeMap<>();
		final Map<String, Item> items = new TreeMap<>();
		final Map<String, Barcode> barcodes = new TreeMap<>();
		String taxStamp;

		@Override
		public Map<String, Family> families() {
			return new LinkedHashMap<>(families);
		}

		@Override
		public Map<String, SubFamily> subFamilies() {
			return new LinkedHashMap<>(subFamilies);
		}

		@Override
		public Map<String, Item> items() {
			return new LinkedHashMap<>(items);
		}

		@Override
		public Map<String, Barcode> barcodes(Collection<String> values) {
			Map<String, Barcode> found = new LinkedHashMap<>();
			values.stream().filter(barcodes::containsKey).forEach(value -> found.put(value, barcodes.get(value)));
			return found;
		}

		@Override
		public String taxStampErpCode() {
			return taxStamp;
		}

		void importFamilies(List<ErpItemFamilyDTO> handed) {
			for (ErpItemFamilyDTO dto : handed) {
				families.put(dto.getCode(), new Family(dto.getCode(), name(dto.getName(), dto.getCode()),
						text(dto.getDescription()), dto.getActive(), dto.getExternalId()));
			}
		}

		void importSubFamilies(List<ErpItemSubFamilyDTO> handed) {
			for (ErpItemSubFamilyDTO dto : handed) {
				if (dto.getFamilyExternalId() != null && families.containsKey(dto.getFamilyExternalId())) { // otherwise skipped
					subFamilies.put(dto.getCode(), new SubFamily(dto.getCode(), name(dto.getName(), dto.getCode()),
							text(dto.getDescription()), dto.getActive(), dto.getExternalId(), dto.getFamilyExternalId()));
				}
			}
		}

		void importItems(List<ErpItemDTO> handed) {
			for (ErpItemDTO dto : handed) {
				if (taxStamp != null && dto.getCode().equals(taxStamp)) {
					continue;
				}
				Item before = items.get(dto.getCode());
				String family = dto.getFamilyExternalId() != null && families.containsKey(dto.getFamilyExternalId())
						? dto.getFamilyExternalId()
						: before == null ? null : before.familyCode;
				String subFamily = dto.getSubFamilyExternalId() != null && subFamilies.containsKey(dto.getSubFamilyExternalId())
						? dto.getSubFamilyExternalId()
						: before == null ? null : before.subFamilyCode;
				items.put(dto.getCode(), new Item(dto.getCode(), name(dto.getName(), dto.getCode()),
						text(dto.getDescription()), dto.getUnitPrice() == null ? null : dto.getUnitPrice().doubleValue(),
						dto.getDefaultVAT(), dto.getActive(), dto.getExternalId(), text(dto.getItemDiscGroup()),
						dto.getMaximumAuthorizedDiscount(), family, subFamily));
			}
		}

		void importBarcodes(List<ErpItemBarcodeDTO> handed) {
			for (ErpItemBarcodeDTO dto : handed) {
				if (items.containsKey(dto.getItemExternalId())) {
					barcodes.put(dto.getBarcode(), new Barcode(dto.getBarcode(), dto.getItemExternalId(), true));
				}
			}
		}

		private static String name(String name, String code) {
			return name == null || name.trim().isEmpty() ? code : name;
		}

		private static String text(String value) {
			return value == null ? "" : value;
		}
	}

	/** The state table. */
	static final class FakeState implements NavPosPagesState {
		final Map<String, String> values = new TreeMap<>();

		@Override
		public String get(String key) {
			return values.get(key);
		}

		@Override
		public void put(String key, String value) {
			values.put(key, value);
		}

		@Override
		public void remove(String key) {
			values.remove(key);
		}

		@Override
		public List<String> keysStartingWith(String prefix) {
			return values.keySet().stream().filter(key -> key.startsWith(prefix)).collect(Collectors.toList());
		}
	}
}
