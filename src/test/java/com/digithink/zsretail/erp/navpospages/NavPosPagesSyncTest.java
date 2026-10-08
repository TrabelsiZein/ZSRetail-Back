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
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesSource;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.connector.NavPosPagesConnector;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCollection;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesHeadOffice;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesImport;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesState;
import com.digithink.zsretail.erp.navpospages.sync.NavPosPagesSync;
import com.digithink.zsretail.erp.navpospages.sync.NavPosRun;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;
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
	/** The size of each packet of items applied, in order. */
	private final List<Integer> packets = new ArrayList<>();
	/** The n-th packet of items from now fails, its transaction rolled back (0: none fails). */
	private int failAtPacket;
	/** Runs inside each packet of items, before it is applied. */
	private Runnable duringPacket = () -> {
	};
	/** The same three for the packets of barcodes. */
	private final List<Integer> barcodePackets = new ArrayList<>();
	private int failAtBarcodePacket;
	private Runnable duringBarcodePacket = () -> {
	};
	/** Barcodes the import leaves out without failing (as the real one does with a warning). */
	private final Set<String> notSaving = new HashSet<>();

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
		sync = new NavPosPagesSync(erp, headOffice, state, properties, new NavPosPagesImport() {
			@Override
			public void items(List<ErpItemDTO> packet) {
				duringPacket.run();
				if (failAtPacket > 0 && --failAtPacket == 0) {
					throw new IllegalStateException("connection lost");
				}
				headOffice.importItems(packet);
				packets.add(packet.size());
			}

			@Override
			public void barcodes(List<ErpItemBarcodeDTO> packet) {
				duringBarcodePacket.run();
				if (failAtBarcodePacket > 0 && --failAtBarcodePacket == 0) {
					throw new IllegalStateException("connection lost");
				}
				headOffice.importBarcodes(packet.stream().filter(dto -> !notSaving.contains(dto.getBarcode()))
						.collect(Collectors.toList()));
				barcodePackets.add(packet.size());
			}
		});
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

	/** The items run applies its packets itself. */
	private NavPosRun<ErpItemDTO> items() {
		return sync.items();
	}

	/** The barcode run applies its packets itself, until it has caught up. */
	private NavPosRun<ErpItemBarcodeDTO> barcodes() {
		return sync.barcodes();
	}

	private static List<String> barcodeValues(NavPosRun<ErpItemBarcodeDTO> run) {
		return run.getHanded().stream().map(ErpItemBarcodeDTO::getBarcode).collect(Collectors.toList());
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
		assertEquals(Boolean.TRUE, barcodes().getSummary().get("caughtUp"));
	}

	private static List<String> codes(List<ErpItemDTO> items) {
		return items.stream().map(ErpItemDTO::getCode).collect(Collectors.toList());
	}

	private NavPosStockRow erpItem(String code) {
		return erp.items.stream().filter(row -> row.getItemNo().equals(code)).findFirst().get();
	}

	// ─── First load ─────────────────────────────────────────────

	@Test
	@DisplayName("First load with packets of 20: families, then sub-families (one packet per run), all items in one run, barcodes, each waiting for the one before")
	void firstLoadWithPackets() {
		properties.setPacketSize(20);
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

		NavPosRun<ErpItemDTO> items = items();
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
		NavPosRun<ErpItemBarcodeDTO> run = barcodes();
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
		NavPosRun<ErpItemDTO> items = items();
		assertEquals(0, items.getHanded().size());
		assertEquals(0, items.count("changed"));
		assertEquals(0, items.count("new"));
		assertEquals(50, items.count("read"));
		NavPosRun<ErpItemBarcodeDTO> barcodes = barcodes();
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
		NavPosRun<ErpItemDTO> run = items();
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

		NavPosRun<ErpItemDTO> next = items();
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
		assertTrue(sync.barcodes().getSummary().containsKey("waiting"));

		NavPosRun<ErpItemDTO> next = items();
		assertEquals(30, next.count("new"));
		assertEquals(Arrays.asList(20, 20, 10), packets);
		assertEquals(50, headOffice.items.size());
		assertEquals("0", state.get("items.pending"));
		assertFalse(sync.barcodes().getSummary().containsKey("waiting"));
	}

	@Test
	@DisplayName("An items run does not start while another one is running; the next one starts once it has ended")
	void notTwiceAtOnce() {
		families();
		subFamilies();
		List<String> refused = new ArrayList<>();
		duringPacket = () -> refused.add(assertThrows(ErpSyncWarningException.class, sync::items).getMessage());
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
		NavPosRun<ErpItemDTO> blank = items();
		assertEquals(0, blank.count("changed"), "kept, not a change");
		assertEquals(0, blank.count("applied"));

		erpItem("000001").setUnitPrice(new BigDecimal("9.99")); // another change: handed with the head office name
		NavPosRun<ErpItemDTO> price = items();
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
		NavPosRun<ErpItemDTO> run = items();
		assertFalse(codes(run.getHanded()).contains("000001"));
		assertEquals(Integer.valueOf(1), ((Map<?, ?>) run.getSummary().get("leftOut")).get(NavPosPagesSync.TAX_STAMP_ITEM));
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
		NavPosRun<ErpItemBarcodeDTO> noRun = sync.barcodes();
		assertEquals("waiting for items: no items run yet", noRun.getSummary().get("waiting"));
		failAtPacket = 2;
		assertThrows(IllegalStateException.class, this::items); // 30 of 50 saved
		NavPosRun<ErpItemBarcodeDTO> pending = sync.barcodes();
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

		NavPosRun<ErpItemBarcodeDTO> run = barcodes();
		assertEquals(Arrays.asList("B1", "B2"), barcodeValues(run));
		assertEquals(Boolean.TRUE, run.getSummary().get("caughtUp"));
		assertEquals(2, run.count("applied"));
		assertEquals(2, run.count("packets"), "page 2 has nothing to save");
		assertEquals(4, run.count("pagesRead"), "3 pages and the empty answer");
		assertEquals(Long.valueOf(0), run.getSummary().get("cursorFrom"));
		assertEquals(Long.valueOf(5000), run.getSummary().get("cursorTo"));
		assertEquals("5000", state.get("barcodes.cursor"));
		assertEquals(Integer.valueOf(4),
				((Map<?, ?>) run.getSummary().get("leftOut")).get(NavPosPagesSync.ITEM_NOT_AT_HEAD_OFFICE));
		assertEquals("000002", headOffice.barcodes.get("B2").itemCode);
		for (String gone : new String[] { "handed", "toHand", "heldBackByCap" }) {
			assertFalse(run.getSummary().containsKey(gone), gone + " in " + run.getSummary());
		}

		NavPosRun<ErpItemBarcodeDTO> again = barcodes();
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
		NavPosRun<ErpItemBarcodeDTO> run = barcodes();
		assertEquals(Arrays.asList(20, 20, 9), barcodePackets);
		assertEquals(49, run.count("applied"));
		assertEquals(3, run.count("packets"));
		assertEquals(Boolean.TRUE, run.getSummary().get("caughtUp"));
		assertEquals("48793", state.get("barcodes.cursor"));
		assertEquals(null, cursors.get(0), "nothing saved before the first packet");
		assertEquals(String.valueOf(lastEntryBefore(savedEntry(20))), cursors.get(1), "after packet 1");
		assertEquals(String.valueOf(lastEntryBefore(savedEntry(40))), cursors.get(2), "after packet 2");
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

		NavPosRun<ErpItemBarcodeDTO> next = barcodes();
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
		NavPosRun<ErpItemBarcodeDTO> stopped = barcodes();
		assertEquals(Boolean.FALSE, stopped.getSummary().get("caughtUp"));
		assertEquals(39, stopped.count("applied"), "the second packet without that row");
		assertEquals("1 barcodes handed but not at the head office after their packet: run stopped, cursor kept before them",
				stopped.getSummary().get("guard"));
		assertEquals(String.valueOf(lastEntryBefore(entry)), state.get("barcodes.cursor"));

		notSaving.clear();
		NavPosRun<ErpItemBarcodeDTO> next = barcodes();
		assertEquals(Boolean.TRUE, next.getSummary().get("caughtUp"));
		assertEquals(49, headOffice.barcodes.size());
		assertEquals("48793", state.get("barcodes.cursor"));
	}

	@Test
	@DisplayName("A barcode run does not start while another one is running")
	void barcodesNotTwiceAtOnce() {
		loadCatalogue();
		List<String> refused = new ArrayList<>();
		duringBarcodePacket = () -> refused.add(assertThrows(ErpSyncWarningException.class, sync::barcodes).getMessage());
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
		NavPosRun<ErpItemBarcodeDTO> run = barcodes();
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

		NavPosRun<ErpItemBarcodeDTO> run = barcodes();
		assertEquals(Arrays.asList(5, 5, 2), barcodePackets);
		assertEquals(11, run.count("appliedForNewItems"));
		assertEquals(12, run.count("itemsNeedingBarcodes"));
		assertEquals(Arrays.asList("needs-barcodes:" + kept), state.keysStartingWith("needs-barcodes:"),
				"only the item whose barcode is not saved");
		assertEquals(Boolean.TRUE, run.getSummary().get("caughtUp"));

		notSaving.clear();
		NavPosRun<ErpItemBarcodeDTO> next = barcodes();
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
		properties.setBarcodePageSize(15);
		properties.setPacketSize(20);
		NavPosRun<ErpItemBarcodeDTO> barcodes = sync.barcodes();
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

	@Test
	@DisplayName("The connector's items fetch applies the run and hands nothing more to the job; the summary says what was applied")
	void connectorItemsApplied() {
		families();
		subFamilies();
		NavPosPagesConnector connector = new NavPosPagesConnector(sync);
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
