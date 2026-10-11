package com.digithink.zsretail.erp.engine;

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

import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.ErpSyncWarningException;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.api.dto.PullOperationResult;
import com.digithink.zsretail.erp.engine.CatalogueImport;
import com.digithink.zsretail.erp.engine.CatalogueState;
import com.digithink.zsretail.erp.engine.HeadOfficeCatalogue;
import com.digithink.zsretail.erp.navpospages.NavPosPagesFixture;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesSource;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.connector.NavPosPagesConnector;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCollection;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.TextNode;


/**
 * ERP catalogue, step 6: changes only, on the samples of src/test/resources/navpospages: the catalogue engine, its
 * stock points and its single job, fed the page rows of the navpospages connector through its adapter
 * ({@link NavPosPagesFixture}). Code cleanup step 3: the catalogue tests of NavPosPagesSyncTest, moved with the engine,
 * same expectations.
 */
class CatalogueEngineTest extends NavPosPagesFixture {

	// ─── First load ─────────────────────────────────────────────

	@Test
	@DisplayName("First load with packets of 20: families, then sub-families (one packet per run), all items in one run, barcodes, each waiting for the one before")
	void firstLoadWithPackets() {
		properties.setPacketSize(20);
		properties.setBarcodePageSize(15);

		CatalogueRun<ErpItemSubFamilyDTO> early = engine.subFamilies();
		assertEquals("waiting for families: 13 families of the ERP not at the head office yet",
				early.getSummary().get("waiting"));
		assertTrue(engine.items().getHanded().isEmpty());
		assertEquals("waiting for items: no items run yet", engine.barcodes().getSummary().get("waiting"));

		CatalogueRun<ErpItemFamilyDTO> f = families();
		assertEquals(13, f.getHanded().size());
		assertEquals(13, f.count("new"));
		assertEquals(0, families().getHanded().size(), "nothing changed");

		CatalogueRun<ErpItemSubFamilyDTO> s1 = subFamilies();
		assertEquals(20, s1.getHanded().size());
		assertEquals(14, s1.count("heldBackByCap"));
		assertEquals("waiting for sub-families: 14 sub-families of the ERP not at the head office yet",
				engine.items().getSummary().get("waiting"), "items wait while sub-families are not all saved");
		assertEquals(14, subFamilies().getHanded().size());
		assertEquals(0, subFamilies().getHanded().size());

		CatalogueRun<ErpItemDTO> items = items();
		List<String> applied = codes(items.getHanded());
		assertEquals(Arrays.asList(20, 20, 10), packets, "every item in one run, packet after packet");
		assertEquals(applied.stream().sorted().collect(Collectors.toList()), applied, "a stable order, by code");
		assertEquals(50, items.count("new"));
		assertEquals(50, items.count("applied"));
		assertEquals(3, items.count("packets"));
		assertEquals(50, headOffice.items.size());
		assertEquals("FLORELLE VAO LES PETALES N° 01", headOffice.items.get("000001").name);
		assertEquals("0", state.get("items.pending"), "counted again once applied: the barcodes need not wait");
		assertEquals(0, items().getHanded().size());

		// Barcodes: pages of 15, all in one run, one packet per page (at most 15 rows to save, packets of 20)
		CatalogueRun<ErpItemBarcodeDTO> run = barcodes();
		assertEquals(Boolean.TRUE, run.getSummary().get("caughtUp"));
		assertEquals(49, run.count("applied"));
		assertEquals(4, run.count("packets"));
		assertEquals(5, run.count("pagesRead"), "4 pages and the empty answer");
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
		CatalogueRun<ErpItemDTO> items = items();
		assertEquals(0, items.getHanded().size());
		assertEquals(0, items.count("changed"));
		assertEquals(0, items.count("new"));
		assertEquals(50, items.count("read"));
		CatalogueRun<ErpItemBarcodeDTO> barcodes = barcodes();
		assertEquals(0, barcodes.getHanded().size());
		assertEquals(0, barcodes.count("applied"));
		assertEquals(0, barcodes.count("packets"));
		assertEquals(Boolean.TRUE, barcodes.getSummary().get("caughtUp"));
	}

	// ─── Items ──────────────────────────────────────────────────

	@Test
	@DisplayName("A price change hands that item only; the stored price before VAT is never seen as changed")
	void priceChange() {
		loadCatalogue();
		erpItem("000001").setUnitPrice(new BigDecimal("2.95"));
		CatalogueRun<ErpItemDTO> run = items();
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
		CatalogueRun<ErpItemDTO> zero = items();
		assertEquals(Arrays.asList("000001"), codes(zero.getHanded()));
		assertEquals(Boolean.FALSE, zero.getHanded().get(0).getActive());
		assertFalse(headOffice.items.get("000001").isActive());
		assertEquals(0, items().getHanded().size());

		erpItem("000001").setUnitPrice(new BigDecimal("2.45"));
		CatalogueRun<ErpItemDTO> back = items();
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
		CatalogueRun<ErpItemDTO> run = items();
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
		CatalogueRun<ErpItemDTO> run = items();
		assertEquals(47, run.count("deactivated"), "every missing ERP item but the tax stamp");
		assertEquals(47, run.getHanded().size());
		assertTrue(run.getHanded().stream().allMatch(dto -> Boolean.FALSE.equals(dto.getActive())));
		assertNull(run.getSummary().get("guard"));
		assertEquals(52, headOffice.items.size(), "never deleted");
		for (HeadOfficeCatalogue.Item here : headOffice.items.values()) {
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
		CatalogueRun<ErpItemDTO> run = items();
		assertTrue(run.getHanded().isEmpty());
		assertEquals("the ERP answered no item for the location: 50 items not deactivated", run.getSummary().get("guard"));
		assertTrue(headOffice.items.values().stream().allMatch(HeadOfficeCatalogue.Item::isActive));
		assertEquals(0, run.count("deactivated"));
		assertEquals(0, run.count("packets"));
	}

	// ─── Items: all the changes in one run, in packets ──────────

	@Test
	@DisplayName("One items run applies every change (changed and deactivated) in packets by code; the summary says what was applied, nothing held back")
	void allChangesInOneRun() {
		loadCatalogue();
		packets.clear();
		properties.setPacketSize(20);
		erp.items.subList(5, 50).clear(); // 45 of 50 gone from the location
		erp.items.get(0).setUnitPrice(new BigDecimal("9.99"));
		CatalogueRun<ErpItemDTO> run = items();
		assertEquals(Arrays.asList(20, 20, 6), packets);
		assertEquals(46, run.getHanded().size());
		List<String> applied = codes(run.getHanded());
		assertEquals(applied.stream().sorted().collect(Collectors.toList()), applied, "by code");
		Map<String, Object> summary = run.getSummary();
		assertEquals(0, run.count("new"));
		assertEquals(1, run.count("changed"));
		assertEquals(45, run.count("deactivated"));
		assertEquals(46, run.count("applied"));
		assertEquals(3, run.count("packets"));
		for (String gone : new String[] { "handed", "toHand", "heldBackByCap" }) {
			assertFalse(summary.containsKey(gone), gone + " in " + summary);
		}
		assertEquals(45, headOffice.items.values().stream().filter(here -> !here.isActive()).count());
		assertEquals(50, headOffice.items.size(), "never deleted");

		CatalogueRun<ErpItemDTO> next = items();
		assertEquals(0, next.count("applied"));
		assertEquals(0, next.count("packets"));
		assertEquals(Arrays.asList(20, 20, 6), packets, "nothing left");
	}

	@Test
	@DisplayName("A failed packet ends the run in error; the packets before it stay and the next run goes on with what is left")
	void packetFails() {
		families();
		subFamilies();
		properties.setPacketSize(20);
		failAtPacket = 2;
		IllegalStateException failure = assertThrows(IllegalStateException.class, this::items);
		assertEquals("Items: packet 2 of 3 failed, 20 of 50 rows applied before it (the next run goes on): connection lost",
				failure.getMessage());
		assertEquals(20, headOffice.items.size(), "the first packet stays");
		assertEquals("50", state.get("items.pending"), "counted before applying: the barcodes wait");
		assertTrue(engine.barcodes().getSummary().containsKey("waiting"));

		CatalogueRun<ErpItemDTO> next = items();
		assertEquals(30, next.count("new"));
		assertEquals(Arrays.asList(20, 20, 10), packets);
		assertEquals(50, headOffice.items.size());
		assertEquals("0", state.get("items.pending"));
		assertFalse(engine.barcodes().getSummary().containsKey("waiting"));
	}

	@Test
	@DisplayName("An items run does not start while another one is running; the next one starts once it has ended")
	void notTwiceAtOnce() {
		families();
		subFamilies();
		List<String> refused = new ArrayList<>();
		duringPacket = () -> refused.add(assertThrows(ErpSyncWarningException.class, engine::items).getMessage());
		items();
		assertEquals(Arrays.asList("an items run is already running: this one does not start"), refused);
		assertEquals(50, headOffice.items.size(), "the running one ends normally");
		duringPacket = () -> {
		};
		erp.items.get(0).setUnitPrice(new BigDecimal("9.99"));
		assertEquals(1, items().count("applied"));
	}

	// ─── Items: a blank Description ─────────────────────────────

	@Test
	@DisplayName("A blank Description never replaces the name of an item at the head office and is not a change; a new item takes its code")
	void blankDescription() {
		loadCatalogue();
		String name = headOffice.items.get("000001").name;
		String description = headOffice.items.get("000001").description;
		erpItem("000001").setDescription("  ");
		CatalogueRun<ErpItemDTO> blank = items();
		assertEquals(0, blank.count("changed"), "kept, not a change");
		assertEquals(0, blank.count("applied"));

		erpItem("000001").setUnitPrice(new BigDecimal("9.99")); // another change: handed with the head office name
		CatalogueRun<ErpItemDTO> price = items();
		assertEquals(1, price.count("changed"));
		assertEquals(name, price.getHanded().get(0).getName());
		assertEquals(name, headOffice.items.get("000001").name);
		assertEquals(description, headOffice.items.get("000001").description);

		erp.items.add(new NavPosStockRow("NEW-1", "", "", new BigDecimal("2.45"), "FAM-ONG-MAQ", "SF-VEO-ONG-MAQ"));
		assertEquals(1, items().count("new"));
		assertEquals("NEW-1", headOffice.items.get("NEW-1").name, "a new item with a blank name takes its code");

		erpItem("000001").setDescription("NEW NAME FROM THE ERP");
		assertEquals(1, items().count("changed"));
		assertEquals("NEW NAME FROM THE ERP", headOffice.items.get("000001").name, "a name given again is applied");
	}

	@Test
	@DisplayName("The ERP item of TAX_STAMP_ERP_ITEM_CODE is left out (the import never saves it, the barcodes would wait for ever)")
	void taxStampItem() {
		headOffice.taxStamp = "000001";
		families();
		subFamilies();
		CatalogueRun<ErpItemDTO> run = items();
		assertFalse(codes(run.getHanded()).contains("000001"));
		assertEquals(Integer.valueOf(1), ((Map<?, ?>) run.getSummary().get("leftOut")).get(CatalogueEngine.TAX_STAMP_ITEM));
		assertEquals("0", state.get("items.pending"), "counted once applied, without it: the barcodes never wait for it");
	}

	// ─── Barcodes ───────────────────────────────────────────────

	@Test
	@DisplayName("Barcodes wait for the items: no item run yet, or new items not saved yet (a packet failed); the cursor does not move")
	void barcodesWaitForItems() {
		properties.setPacketSize(30);
		families();
		subFamilies(); // 30 of 34
		subFamilies();
		CatalogueRun<ErpItemBarcodeDTO> noRun = engine.barcodes();
		assertEquals("waiting for items: no items run yet", noRun.getSummary().get("waiting"));
		failAtPacket = 2;
		assertThrows(IllegalStateException.class, this::items); // 30 of 50 saved
		CatalogueRun<ErpItemBarcodeDTO> pending = engine.barcodes();
		assertEquals("waiting for items: 50 new items not at the head office yet", pending.getSummary().get("waiting"));
		assertTrue(pending.getHanded().isEmpty());
		assertNull(state.get("barcodes.cursor"));
		items(); // the last 20, then counted again: 0
		assertFalse(barcodes().getHanded().isEmpty());
	}

	@Test
	@DisplayName("Empty barcode table, 3 pages, barcodes to save on pages 1 and 3: one run applies them all, caught up, cursor at the highest Entry_No")
	void emptyHeadOfficeThreePages() {
		loadCatalogue();
		erp.barcodes = new ArrayList<>(Arrays.asList(new NavPosBarcodeRow("000001", "B1", 5L),
				new NavPosBarcodeRow("OUTSIDE", "X1", 9L), new NavPosBarcodeRow("OUTSIDE", "X2", 120L),
				new NavPosBarcodeRow("OUTSIDE", "X3", 121L), new NavPosBarcodeRow("000002", "B2", 4999L),
				new NavPosBarcodeRow("OUTSIDE", "X4", 5000L)));
		properties.setBarcodePageSize(2);

		CatalogueRun<ErpItemBarcodeDTO> run = barcodes();
		assertEquals(Arrays.asList("B1", "B2"), barcodeValues(run));
		assertEquals(Boolean.TRUE, run.getSummary().get("caughtUp"));
		assertEquals(2, run.count("applied"));
		assertEquals(2, run.count("packets"), "page 2 has nothing to save");
		assertEquals(4, run.count("pagesRead"), "3 pages and the empty answer");
		assertEquals(Long.valueOf(0), run.getSummary().get("cursorFrom"));
		assertEquals(Long.valueOf(5000), run.getSummary().get("cursorTo"));
		assertEquals("5000", state.get("barcodes.cursor"));
		assertEquals(Integer.valueOf(4),
				((Map<?, ?>) run.getSummary().get("leftOut")).get(CatalogueEngine.ITEM_NOT_AT_HEAD_OFFICE));
		assertEquals("000002", headOffice.barcodes.get("B2").itemCode);
		for (String gone : new String[] { "handed", "toHand", "heldBackByCap" }) {
			assertFalse(run.getSummary().containsKey(gone), gone + " in " + run.getSummary());
		}

		CatalogueRun<ErpItemBarcodeDTO> again = barcodes();
		assertEquals(0, again.count("applied"));
		assertEquals(0, again.count("packets"));
		assertEquals(Boolean.TRUE, again.getSummary().get("caughtUp"));
		assertEquals(1, again.count("pagesRead"));
	}

	@Test
	@DisplayName("More rows to save on a page than one packet: packet after packet, the cursor saved after each one")
	void pageBiggerThanAPacket() {
		loadCatalogue();
		properties.setPacketSize(20);
		List<String> cursors = new ArrayList<>();
		duringBarcodePacket = () -> cursors.add(state.get("barcodes.cursor"));
		CatalogueRun<ErpItemBarcodeDTO> run = barcodes();
		assertEquals(Arrays.asList(20, 20, 9), barcodePackets);
		assertEquals(49, run.count("applied"));
		assertEquals(3, run.count("packets"));
		assertEquals(Boolean.TRUE, run.getSummary().get("caughtUp"));
		assertEquals("48793", state.get("barcodes.cursor"));
		assertEquals(null, cursors.get(0), "nothing saved before the first packet");
		assertEquals(String.valueOf(lastEntryBefore(savedEntry(20))), cursors.get(1), "after packet 1");
		assertEquals(String.valueOf(lastEntryBefore(savedEntry(40))), cursors.get(2), "after packet 2");
	}

	// ─── Code cleanup step 3, proof: the cursor by position over left-out rows, as by Entry_No before ────

	/** What a barcode run leaves: the cursor saved, the guard and the counts of its summary. */
	private String proof(CatalogueRun<ErpItemBarcodeDTO> run) {
		Map<String, Object> summary = run.getSummary();
		StringBuilder text = new StringBuilder("cursor=" + state.get("barcodes.cursor"));
		for (String key : new String[] { "guard", "read", "leftOut", "alreadyAtHeadOffice", "applied", "packets",
				"pagesRead", "cursorFrom", "cursorTo", "caughtUp" }) {
			text.append(", ").append(key).append('=').append(summary.get(key));
		}
		return text.append(", handed=").append(barcodeValues(run)).toString();
	}

	@Test
	@DisplayName("Proof: a blank row below a barcode the import did not save: the cursor passes the blank row, never the barcode")
	void proofBlankRowBelowUnsaved() {
		loadCatalogue();
		erp.barcodes = new ArrayList<>(Arrays.asList(new NavPosBarcodeRow("000001", "PA", 1L),
				new NavPosBarcodeRow("000001", " ", 2L), new NavPosBarcodeRow("000001", "PB", 3L)));
		notSaving.add("PB");
		assertEquals("cursor=2, guard=1 barcodes handed but not at the head office after their packet: run stopped,"
				+ " cursor kept before them, read=3, leftOut={blank Cross_Reference_No=1}, alreadyAtHeadOffice=null,"
				+ " applied=1, packets=1, pagesRead=1, cursorFrom=0, cursorTo=2, caughtUp=false, handed=[PA]",
				proof(barcodes()));
	}

	@Test
	@DisplayName("Proof: a page of only blank rows: every row left out, the cursor moves over the page, caught up")
	void proofPageOfBlankRows() {
		loadCatalogue();
		erp.barcodes = new ArrayList<>(Arrays.asList(new NavPosBarcodeRow("000001", "", 7L),
				new NavPosBarcodeRow(" ", "PX", 8L), new NavPosBarcodeRow(null, null, 9L)));
		assertEquals("cursor=9, guard=null, read=3, leftOut={blank Cross_Reference_No=2, blank Item_No=1},"
				+ " alreadyAtHeadOffice=null, applied=0, packets=0, pagesRead=2, cursorFrom=0, cursorTo=9, caughtUp=true,"
				+ " handed=[]", proof(barcodes()));
	}

	@Test
	@DisplayName("Proof: a row with a null Entry_No: first by position, counted as 0; saved, or not saved and the cursor at 0")
	void proofNullEntryNo() {
		loadCatalogue();
		erp.barcodePages = after -> after == 0 ? Arrays.asList(new NavPosBarcodeRow("000001", "PC", 4L),
				new NavPosBarcodeRow("000001", "PN", null)) : new ArrayList<>();
		assertEquals("cursor=4, guard=null, read=2, leftOut={}, alreadyAtHeadOffice=null, applied=2, packets=1,"
				+ " pagesRead=2, cursorFrom=0, cursorTo=4, caughtUp=true, handed=[PN, PC]", proof(barcodes()));

		// The same page on an empty barcode table, the row without Entry_No not saved: the cursor stays at 0
		headOffice.barcodes.clear();
		state.remove("barcodes.cursor");
		notSaving.add("PN");
		assertEquals("cursor=0, guard=1 barcodes handed but not at the head office after their packet: run stopped,"
				+ " cursor kept before them, read=2, leftOut={}, alreadyAtHeadOffice=null, applied=1, packets=1,"
				+ " pagesRead=1, cursorFrom=0, cursorTo=0, caughtUp=false, handed=[PC]", proof(barcodes()));
	}

	/** The Entry_No of the n-th row (0-based, by Entry_No) whose item is at the head office. */
	private long savedEntry(int n) {
		return erp.barcodes.stream().filter(row -> headOffice.items.containsKey(row.getItemNo()))
				.map(NavPosBarcodeRow::getEntryNo).sorted().skip(n).findFirst().get();
	}

	/** The highest Entry_No of the ERP below limit. */
	private long lastEntryBefore(long limit) {
		return erp.barcodes.stream().map(NavPosBarcodeRow::getEntryNo).filter(entry -> entry < limit).max(Long::compare)
				.get();
	}

	@Test
	@DisplayName("A failed packet ends the run in error; the packets before it stay, the cursor stops before the failed rows; the next run finishes")
	void barcodePacketFails() {
		loadCatalogue();
		properties.setPacketSize(20);
		failAtBarcodePacket = 2;
		IllegalStateException failure = assertThrows(IllegalStateException.class, this::barcodes);
		assertEquals("Barcodes: packet 2 failed, 20 rows applied before it (the next run goes on): connection lost",
				failure.getMessage());
		assertEquals(20, headOffice.barcodes.size(), "the first packet stays");
		long firstFailed = savedEntry(20);
		assertEquals(String.valueOf(lastEntryBefore(firstFailed)), state.get("barcodes.cursor"));
		assertTrue(Long.parseLong(state.get("barcodes.cursor")) < firstFailed, "never past a row not saved");

		CatalogueRun<ErpItemBarcodeDTO> next = barcodes();
		assertEquals(29, next.count("applied"));
		assertEquals(Boolean.TRUE, next.getSummary().get("caughtUp"));
		assertEquals(49, headOffice.barcodes.size());
		assertEquals("48793", state.get("barcodes.cursor"));
	}

	@Test
	@DisplayName("A row the import leaves out without failing stops the run: the cursor stays before it, the next run hands it again")
	void rowNotSavedStopsTheRun() {
		loadCatalogue();
		properties.setPacketSize(20);
		long entry = savedEntry(25);
		String barcode = erp.barcodes.stream().filter(row -> row.getEntryNo() == entry).findFirst().get()
				.getCrossReferenceNo().trim();
		notSaving.add(barcode);
		CatalogueRun<ErpItemBarcodeDTO> stopped = barcodes();
		assertEquals(Boolean.FALSE, stopped.getSummary().get("caughtUp"));
		assertEquals(39, stopped.count("applied"), "the second packet without that row");
		assertEquals("1 barcodes handed but not at the head office after their packet: run stopped, cursor kept before them",
				stopped.getSummary().get("guard"));
		assertEquals(String.valueOf(lastEntryBefore(entry)), state.get("barcodes.cursor"));

		notSaving.clear();
		CatalogueRun<ErpItemBarcodeDTO> next = barcodes();
		assertEquals(Boolean.TRUE, next.getSummary().get("caughtUp"));
		assertEquals(49, headOffice.barcodes.size());
		assertEquals("48793", state.get("barcodes.cursor"));
	}

	@Test
	@DisplayName("A barcode run does not start while another one is running")
	void barcodesNotTwiceAtOnce() {
		loadCatalogue();
		List<String> refused = new ArrayList<>();
		duringBarcodePacket = () -> refused.add(assertThrows(ErpSyncWarningException.class, engine::barcodes).getMessage());
		assertEquals(Boolean.TRUE, barcodes().getSummary().get("caughtUp"));
		assertEquals(Arrays.asList("a barcode run is already running: this one does not start"), refused);
		assertEquals(49, headOffice.barcodes.size());
	}

	@Test
	@DisplayName("A new item after the first load gets its barcodes by item number in the next run, then the list is cleared in that run")
	void newItemGetsItsBarcodes() {
		erp.items.removeIf(row -> row.getItemNo().equals("000002"));
		loadAll();
		assertFalse(headOffice.items.containsKey("000002"));
		assertFalse(headOffice.barcodes.containsKey("000002"), "its barcode was left out, the cursor passed it");

		erp.items.add(new NavPosStockRow("000002", "", "NEW ONE", new BigDecimal("2.45"), "FAM-ONG-MAQ", "SF-VEO-ONG-MAQ"));
		items();
		assertEquals("1", state.get("needs-barcodes:000002"));
		assertEquals("0", state.get("items.pending"), "the new item was saved in the run");
		CatalogueRun<ErpItemBarcodeDTO> run = barcodes();
		assertEquals(Arrays.asList("000002"), barcodeValues(run));
		assertEquals(Arrays.asList("000002"), erp.askedItems);
		assertEquals(1, run.count("appliedForNewItems"));
		assertNull(state.get("needs-barcodes:000002"), "its barcodes are saved: off the list in the same run");
		assertEquals(Boolean.TRUE, run.getSummary().get("caughtUp"), "then the cursor, in the same run");
		assertEquals("000002", headOffice.barcodes.get("000002").itemCode);
	}

	@Test
	@DisplayName("A \"needs its barcodes\" list with more rows than one packet: every item in one run, packet after packet; an item not saved stays listed")
	void needsListBiggerThanAPacket() {
		Set<String> withBarcode = erp.barcodes.stream().map(NavPosBarcodeRow::getItemNo).collect(Collectors.toSet());
		List<NavPosStockRow> later = erp.items.stream().filter(row -> withBarcode.contains(row.getItemNo())).limit(12)
				.collect(Collectors.toList());
		erp.items.removeAll(later);
		loadAll();
		erp.items.addAll(later);
		properties.setPacketSize(5);
		items();
		assertEquals(12, state.keysStartingWith("needs-barcodes:").size());
		String kept = later.get(7).getItemNo();
		String keptBarcode = erp.barcodes.stream().filter(row -> row.getItemNo().equals(kept)).findFirst().get()
				.getCrossReferenceNo().trim();
		notSaving.add(keptBarcode);
		barcodePackets.clear();

		CatalogueRun<ErpItemBarcodeDTO> run = barcodes();
		assertEquals(Arrays.asList(5, 5, 2), barcodePackets);
		assertEquals(11, run.count("appliedForNewItems"));
		assertEquals(12, run.count("itemsNeedingBarcodes"));
		assertEquals(Arrays.asList("needs-barcodes:" + kept), state.keysStartingWith("needs-barcodes:"),
				"only the item whose barcode is not saved");
		assertEquals(Boolean.TRUE, run.getSummary().get("caughtUp"));

		notSaving.clear();
		CatalogueRun<ErpItemBarcodeDTO> next = barcodes();
		assertEquals(1, next.count("appliedForNewItems"));
		assertTrue(state.keysStartingWith("needs-barcodes:").isEmpty());
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
		CatalogueRun<ErpItemFamilyDTO> f = engine.families();
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
		CatalogueRun<ErpItemDTO> items = engine.items();
		assertTrue(items.getHanded().isEmpty());
		assertEquals(1, items.count("changed"));
		properties.setBarcodePageSize(15);
		properties.setPacketSize(20);
		CatalogueRun<ErpItemBarcodeDTO> barcodes = engine.barcodes();
		assertTrue(barcodes.getHanded().isEmpty());
		assertEquals(49, barcodes.count("toApply"), "read to the last page");
		assertEquals(0, barcodes.count("applied"));
		assertEquals(0, barcodes.count("packets"));
		assertEquals(5, barcodes.count("pagesRead"));
		assertEquals(Boolean.TRUE, barcodes.getSummary().get("caughtUp"), "it ends although no cursor is saved");
		assertTrue(barcodePackets.isEmpty(), "nothing imported");
		assertTrue(headOffice.barcodes.isEmpty());
		assertEquals(before, state.values);
	}

	// ─── Stock points (release 2.2, step 2) ─────────────────────

	private static final long FRANCHISE = 1;
	private static final long MAG01 = 2;

	/** A copy of the ERP row for another point. */
	private static NavPosStockRow copy(NavPosStockRow row, String price) {
		NavPosStockRow copy = new NavPosStockRow();
		copy.setItemNo(row.getItemNo());
		copy.setVariantCode(row.getVariantCode());
		copy.setDescription(row.getDescription());
		copy.setUnitPrice(price == null ? row.getUnitPrice() : new BigDecimal(price));
		copy.setFamily(row.getFamily());
		copy.setSubfamily(row.getSubfamily());
		return copy;
	}

	/** The stockPoints part of the summary for one point. */
	@SuppressWarnings("unchecked")
	private static Map<String, Object> point(CatalogueRun<?> run, String code) {
		return (Map<String, Object>) ((Map<String, Object>) run.getSummary().get("stockPoints")).get(code);
	}

	/**
	 * MAG01, second in the list: items 0, 1 and 2 of FRANCHISE (item 0 at 11.90, item 1 at 5.95, item 2 as in
	 * FRANCHISE) and ONLY-MAG01, an item of no other point.
	 */
	private void secondPoint() {
		List<NavPosStockRow> mag01 = new ArrayList<>();
		mag01.add(copy(erp.items.get(0), "11.90"));
		mag01.add(copy(erp.items.get(1), "5.95"));
		mag01.add(copy(erp.items.get(2), null));
		NavPosStockRow only = copy(erp.items.get(0), "23.80");
		only.setItemNo("ONLY-MAG01");
		only.setDescription("Only in MAG01");
		mag01.add(only);
		erp.otherPoints.put("MAG01", mag01);
		stockPoints.points.add(new StockPoints.Point(MAG01, "MAG01", "Store 1"));
	}

	@Test
	@DisplayName("One point: its rows hold exactly the item's values; a second run writes nothing; the summary has its stockPoints part")
	void onePointRows() {
		loadCatalogue();
		assertEquals(Arrays.asList("FRANCHISE"), erp.pointReads.stream().distinct().collect(Collectors.toList()));
		Map<String, StockPoints.Row> rows = stockPoints.rows(FRANCHISE);
		assertEquals(headOffice.items.size(), rows.size(), "one row per item");
		for (HeadOfficeCatalogue.Item item : headOffice.items.values()) {
			StockPoints.Row row = rows.get(item.code);
			assertEquals(item.name, row.name, item.code);
			assertEquals(item.unitPrice, row.unitPrice, item.code);
			assertEquals(item.isActive(), row.active, item.code);
			assertEquals(item.familyCode, row.familyCode, item.code);
			assertEquals(item.subFamilyCode, row.subFamilyCode, item.code);
		}
		int writes = stockPoints.writes;
		CatalogueRun<ErpItemDTO> again = items();
		assertEquals(0, again.getHanded().size());
		assertEquals(writes, stockPoints.writes, "nothing written");
		Map<String, Object> franchise = point(again, "FRANCHISE");
		assertEquals(again.count("read"), franchise.get("read"));
		assertEquals(0, franchise.get("new"));
		assertEquals(0, franchise.get("changed"));
		assertEquals(0, franchise.get("deactivated"));
		assertEquals(0, franchise.get("written"));
		assertTrue(franchise.containsKey("durationMs"));
	}

	@Test
	@DisplayName("No active point: the items run reads nothing, writes nothing and says to create a point de stock")
	void noPoint() {
		families();
		subFamilies();
		stockPoints.points.clear();
		CatalogueRun<ErpItemDTO> run = items();
		assertTrue(run.getHanded().isEmpty());
		assertEquals(CatalogueEngine.NO_STOCK_POINT, run.getSummary().get("waiting"));
		assertTrue(erp.pointReads.isEmpty());
		assertTrue(headOffice.items.isEmpty());
		assertNull(state.get("items.run"));
	}

	@Test
	@DisplayName("Two points: each point gets its own rows and prices; the item comes from the first point where it is active; an item of one point only is created")
	void twoPoints() {
		loadCatalogue();
		String item0 = erp.items.get(0).getItemNo();
		String item1 = erp.items.get(1).getItemNo();
		Double franchisePrice0 = headOffice.items.get(item0).unitPrice;
		erp.items.get(1).setUnitPrice(BigDecimal.ZERO); // inactive in FRANCHISE, active in MAG01
		secondPoint();

		CatalogueRun<ErpItemDTO> run = items();
		assertEquals(Arrays.asList("FRANCHISE", "MAG01"), erp.pointReads.subList(erp.pointReads.size() - 2,
				erp.pointReads.size()));
		assertEquals(franchisePrice0, headOffice.items.get(item0).unitPrice, "item 0: FRANCHISE is first and active");
		assertEquals(10.0, stockPoints.row(MAG01, item0).unitPrice, 1e-9, "MAG01 keeps its own price");
		assertEquals(franchisePrice0, stockPoints.row(FRANCHISE, item0).unitPrice);
		assertTrue(headOffice.items.get(item1).isActive(), "item 1: active in MAG01");
		assertEquals(5.0, headOffice.items.get(item1).unitPrice, 1e-9, "item 1: the price of MAG01, the first active");
		assertFalse(stockPoints.row(FRANCHISE, item1).active);
		assertEquals(0.0, stockPoints.row(FRANCHISE, item1).unitPrice, 0.0);
		assertTrue(headOffice.items.containsKey("ONLY-MAG01"), "created through the import");
		assertEquals(20.0, headOffice.items.get("ONLY-MAG01").unitPrice, 1e-9);
		assertNull(stockPoints.row(FRANCHISE, "ONLY-MAG01"));
		assertEquals("Only in MAG01", stockPoints.row(MAG01, "ONLY-MAG01").name);

		assertEquals(1, point(run, "FRANCHISE").get("changed"));
		assertEquals(0, point(run, "FRANCHISE").get("new"));
		assertEquals(4, point(run, "MAG01").get("new"));
		assertEquals(4, point(run, "MAG01").get("written"));
		assertEquals(1, run.count("new"), "ONLY-MAG01");
		assertEquals(0, items().getHanded().size(), "nothing more");
	}

	@Test
	@DisplayName("Gone from one point: that row inactive (never deleted), the item still active through the other point")
	void goneFromOnePoint() {
		loadCatalogue();
		secondPoint();
		items();
		String item2 = erp.items.get(2).getItemNo();
		erp.otherPoints.get("MAG01").removeIf(row -> row.getItemNo().equals(item2));
		CatalogueRun<ErpItemDTO> run = items();
		assertEquals(1, point(run, "MAG01").get("deactivated"));
		assertFalse(stockPoints.row(MAG01, item2).active);
		assertTrue(stockPoints.row(FRANCHISE, item2).active);
		assertTrue(headOffice.items.get(item2).isActive());
		assertEquals(0, run.count("deactivated"));

		erp.items.removeIf(row -> row.getItemNo().equals(item2)); // now in no point
		CatalogueRun<ErpItemDTO> last = items();
		assertEquals(1, last.count("deactivated"));
		assertFalse(headOffice.items.get(item2).isActive());
		assertFalse(stockPoints.row(FRANCHISE, item2).active);
	}

	@Test
	@DisplayName("A point that answers no row keeps its rows and counts with them; nothing is deactivated, and the guard names it")
	void silentPoint() {
		loadCatalogue();
		String item1 = erp.items.get(1).getItemNo();
		erp.items.get(1).setUnitPrice(BigDecimal.ZERO);
		secondPoint();
		items();
		erp.otherPoints.put("MAG01", new ArrayList<>()); // MAG01 answers no row
		String gone = erp.items.get(5).getItemNo();
		erp.items.removeIf(row -> row.getItemNo().equals(gone)); // gone from FRANCHISE, in no other point

		CatalogueRun<ErpItemDTO> run = items();
		assertEquals(Boolean.TRUE, point(run, "MAG01").get("noAnswer"));
		assertEquals(0, point(run, "MAG01").get("written"));
		assertEquals(4, stockPoints.rows(MAG01).values().stream().filter(row -> row.active).count(), "kept");
		assertTrue(headOffice.items.get(item1).isActive(), "item 1 still active through MAG01's last rows");
		assertTrue(headOffice.items.get("ONLY-MAG01").isActive());
		assertTrue(headOffice.items.get(gone).isActive(), "no item deactivated while a point is silent");
		assertEquals("stock points that answered no item (MAG01): 1 items not deactivated", run.getSummary().get("guard"));
		assertFalse(stockPoints.row(FRANCHISE, gone).active, "its FRANCHISE row is deactivated");
	}

	@Test
	@DisplayName("A failed read of one point fails the run before anything is applied")
	void failedRead() {
		loadCatalogue();
		secondPoint();
		erp.failingPoints.add("MAG01");
		erp.items.get(0).setUnitPrice(new BigDecimal("99.00"));
		Double before = headOffice.items.get(erp.items.get(0).getItemNo()).unitPrice;
		packets.clear();
		int writes = stockPoints.writes;
		assertThrows(IllegalStateException.class, this::items);
		assertTrue(packets.isEmpty(), "no item packet");
		assertEquals(writes, stockPoints.writes, "no row written");
		assertEquals(before, headOffice.items.get(erp.items.get(0).getItemNo()).unitPrice);
	}

	@Test
	@DisplayName("An inactive point is not read: its items in no active point are deactivated, its rows untouched")
	void inactivePoint() {
		loadCatalogue();
		secondPoint();
		items();
		stockPoints.points.removeIf(point -> point.id == MAG01); // deactivated
		erp.pointReads.clear();
		CatalogueRun<ErpItemDTO> run = items();
		assertEquals(Arrays.asList("FRANCHISE"), erp.pointReads);
		assertFalse(headOffice.items.get("ONLY-MAG01").isActive());
		assertEquals(1, run.count("deactivated"));
		assertTrue(stockPoints.row(MAG01, "ONLY-MAG01").active, "an inactive point's rows are not touched");
	}

	@Test
	@DisplayName("List order decides the item's copy: MAG01 first gives item 0 the price of MAG01")
	void listOrder() {
		loadCatalogue();
		secondPoint();
		items();
		java.util.Collections.reverse(stockPoints.points);
		String item0 = erp.items.get(0).getItemNo();
		CatalogueRun<ErpItemDTO> run = items();
		assertEquals(10.0, headOffice.items.get(item0).unitPrice, 1e-9);
		assertTrue(run.count("changed") >= 1);
		assertEquals(Arrays.asList("MAG01", "FRANCHISE"),
				new ArrayList<>(((Map<?, ?>) run.getSummary().get("stockPoints")).keySet()));
	}

	@Test
	@DisplayName("Dry run with two points: read and counted per point, nothing written")
	void dryRunTwoPoints() {
		loadCatalogue();
		secondPoint();
		properties.setDryRun(true);
		int writes = stockPoints.writes;
		CatalogueRun<ErpItemDTO> run = items();
		assertTrue(run.getHanded().isEmpty());
		assertEquals(4, point(run, "MAG01").get("new"));
		assertEquals(0, point(run, "MAG01").get("written"));
		assertEquals(writes, stockPoints.writes);
		assertNull(stockPoints.row(MAG01, "ONLY-MAG01"));
		assertFalse(headOffice.items.containsKey("ONLY-MAG01"));
	}

	@Test
	@DisplayName("Step 4: each point's last read is recorded (OK with its counts, NO_ANSWER, FAILED); never in a dry run")
	void lastReads() {
		loadCatalogue();
		secondPoint();
		items();
		assertTrue(stockPoints.lastReads.get(FRANCHISE).startsWith("OK read 50, new 0, changed 0, deactivated 0,"),
				stockPoints.lastReads.get(FRANCHISE));
		assertTrue(stockPoints.lastReads.get(MAG01).startsWith("OK read 4, new 4, changed 0, deactivated 0, written 4"),
				stockPoints.lastReads.get(MAG01));

		erp.otherPoints.put("MAG01", new ArrayList<>());
		items();
		assertTrue(stockPoints.lastReads.get(MAG01).startsWith("NO_ANSWER no row: its rows kept; read 0"),
				stockPoints.lastReads.get(MAG01));

		erp.failingPoints.add("MAG01");
		assertThrows(IllegalStateException.class, this::items);
		assertEquals("FAILED read failed: reading the page PointStockPOS failed: timeout",
				stockPoints.lastReads.get(MAG01));

		erp.failingPoints.clear();
		stockPoints.lastReads.clear();
		properties.setDryRun(true);
		items();
		assertTrue(stockPoints.lastReads.isEmpty(), "a dry run records nothing");
	}

	// ─── The job SYNC_CATALOGUE (release 2.2) ────────────────────

	/** The rows the job writes to the communications log: status, message, summary. */
	private final List<Object[]> logged = new ArrayList<>();

	/** The job on this engine; families and sub-families applied by the fake import. */
	private CatalogueJob job() {
		CatalogueImport categories = new CatalogueImport() {
			@Override
			public void items(List<ErpItemDTO> packet) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void barcodes(List<ErpItemBarcodeDTO> packet) {
				throw new UnsupportedOperationException();
			}

			@Override
			public void families(List<ErpItemFamilyDTO> packet) {
				headOffice.importFamilies(packet);
			}

			@Override
			public void subFamilies(List<ErpItemSubFamilyDTO> packet) {
				headOffice.importSubFamilies(packet);
			}
		};
		return new CatalogueJob(engine, categories, stockPoints,
				(status, summary, message, start, end) -> logged.add(new Object[] { status, message, summary }));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> part(String name) {
		Map<String, Object> summary = (Map<String, Object>) logged.get(logged.size() - 1)[2];
		return (Map<String, Object>) ((Map<String, Object>) summary.get("parts")).get(name);
	}

	@Test
	@DisplayName("Sync catalogue: families (in rounds), sub-families, items and rows, barcodes in one run; each part with its counts and duration")
	void catalogueJobOneRun() {
		properties.setPacketSize(20);
		job().syncCatalogue();
		assertEquals(1, logged.size());
		assertEquals(com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus.SUCCESS, logged.get(0)[0]);
		assertEquals(13, headOffice.families.size());
		assertEquals(34, headOffice.subFamilies.size(), "two rounds: 20, then 14");
		assertEquals(2, part("subFamilies").get("rounds"));
		assertEquals(34, part("subFamilies").get("handed"));
		assertEquals(50, headOffice.items.size());
		assertEquals(50, stockPoints.rows(FRANCHISE).size());
		assertTrue(headOffice.barcodes.size() > 0);
		assertEquals(Boolean.TRUE, part("barcodes").get("caughtUp"));
		for (String name : new String[] { "families", "subFamilies", "items", "barcodes" }) {
			assertTrue(part(name).containsKey("durationMs"), name);
		}

		job().syncCatalogue(); // nothing changed: a success, every part checked
		assertEquals(com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus.SUCCESS, logged.get(1)[0]);
		assertEquals(0, part("items").get("applied"));
	}

	@Test
	@DisplayName("Sync catalogue without an active point de stock: nothing read, a warning with the message")
	void catalogueJobNoPoint() {
		stockPoints.points.clear();
		ErpSyncWarningException warning = assertThrows(ErpSyncWarningException.class, () -> job().syncCatalogue());
		assertEquals(CatalogueEngine.NO_STOCK_POINT, warning.getMessage());
		assertEquals("No point de stock: create one in Catalogue > Points de stock, then run the job again.",
				warning.getMessage());
		assertEquals(com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus.WARNING, logged.get(0)[0]);
		assertTrue(headOffice.families.isEmpty());
		assertTrue(erp.pointReads.isEmpty());
	}

	@Test
	@DisplayName("Sync catalogue: a part that fails stops the run; the parts before it stay saved and the log says which part failed")
	void catalogueJobFailedPart() {
		erp.failingPoints.add("FRANCHISE");
		assertThrows(IllegalStateException.class, () -> job().syncCatalogue());
		assertEquals(13, headOffice.families.size(), "saved before the failure");
		assertEquals(34, headOffice.subFamilies.size());
		assertTrue(headOffice.items.isEmpty());
		assertEquals(com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus.ERROR, logged.get(0)[0]);
		assertTrue(((String) logged.get(0)[1]).startsWith("Items failed (the parts before it are saved"),
				(String) logged.get(0)[1]);
		assertTrue(part("items").containsKey("error"));
		assertEquals(null, part("barcodes"));

		erp.failingPoints.clear();
		job().syncCatalogue(); // the next run goes on
		assertEquals(50, headOffice.items.size());
	}

	@Test
	@DisplayName("Sync catalogue: an item whose family is not among the ERP's categories is imported without it, counted and named")
	void catalogueJobItemsWithoutCategory() {
		erp.items.get(0).setFamily("NOT-A-FAMILY");
		String code = erp.items.get(0).getItemNo();
		job().syncCatalogue();
		assertTrue(headOffice.items.containsKey(code), "imported");
		assertEquals(null, headOffice.items.get(code).familyCode, "without the family");
		// In the sample every item names a family that the sample of the categories does not have
		assertEquals(50, part("items").get("notInCategories"));
		String first = (String) part("items").get("notInCategoriesFirst");
		assertTrue(first.startsWith(code + " (family NOT-A-FAMILY), "), first);
		assertEquals(10, first.split(", ").length, "the first ten named");
	}

	@Test
	@DisplayName("Sync catalogue: a part that waits or a dry run is a warning with its reason, never a success")
	void catalogueJobWarnings() {
		properties.setDryRun(true);
		ErpSyncWarningException dry = assertThrows(ErpSyncWarningException.class, () -> job().syncCatalogue());
		assertTrue(dry.getMessage().contains(CatalogueJob.DRY_RUN), dry.getMessage());

		properties.setDryRun(false);
		job().syncCatalogue();
		erp.items.clear(); // FRANCHISE answers nothing: the items run keeps its rows (a guard)
		ErpSyncWarningException warning = assertThrows(ErpSyncWarningException.class, () -> job().syncCatalogue());
		assertTrue(warning.getMessage().startsWith("Items: the ERP answered no item"), warning.getMessage());
		assertEquals(com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus.WARNING,
				logged.get(logged.size() - 1)[0]);
	}

}
