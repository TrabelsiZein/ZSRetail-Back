package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.erp.service.ErpItemBootstrapService;
import com.digithink.zsretail.core.link.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.core.service.CatalogueHeadOfficeHooks;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.headoffice.dto.StockPointDTO;
import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.support.InMemoryCatalogue;

/**
 * Stock point copies, step 4: on a head office whose catalogue comes from the ERP, an ERP item and its barcodes are
 * recorded for the stores whose point has a row for the item (active or not), never for every store; a hand-made item
 * still goes to every store. Through the real aspect (HeadOfficeErpCatalogueRecorder) over stub imports: an item change
 * that moves no row, a barcode run, a barcode moved to another item, a barcode value changed under the same ERP id; and
 * a pack change, a new item (no row yet, then its point's rows), a delete, the startup backfill. Fake data: P1 (B001, B002
 * inactive), P2 (B002, B003), B004 in no point, MAIN hand-made; stores A (P1), B (P2), C (none). In-memory tables, no
 * Spring context.
 */
class HoStockPointSyncTest {

	private InMemoryCatalogue ho;
	private InMemoryDownTables down;
	private CopiesDownFeed feed;
	private HoCatalogueService catalogue;
	private HoStockPointService points;
	private ErpItemBootstrapService imports;
	private ItemSubFamily sf1;
	private Item b001;
	private Item b002;
	private Item b003;
	private Item b004;
	private Item main;
	private ItemBarcode bc111;
	private ItemBarcode bc333;
	private long p1;
	private long p2;
	private Store a;
	private Store b;
	private Store c;

	/** What the stub imports do: change the tables, then return what they "saved". */
	private Supplier<List<?>> importing;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		down = new InMemoryDownTables();
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		catalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(), ho.itemRepository(),
				ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(), () -> feedRef[0],
				TransactionOperations.withoutTransaction(), ho.stockPointItemRepository(), ho.storeRepository());
		feed = down.feed(Collections.singletonList(catalogue));
		feedRef[0] = feed;
		points = new HoStockPointService(ho.stockPointRepository(), ho.stockPointItemRepository(), ho.storeRepository(),
				() -> catalogue);
		imports = guarded();

		ItemFamily f1 = ho.family("F1");
		sf1 = ho.subFamily("SF1", f1);
		b001 = erp(ho.item("B001", 10.0, sf1));
		b002 = erp(ho.item("B002", 20.0, sf1));
		b003 = erp(ho.item("B003", 30.0, sf1));
		b004 = erp(ho.item("B004", 40.0, sf1)); // in no point
		main = ho.item("MAIN", 5.0, sf1); // made at the head office: no ERP id
		bc111 = ho.barcode("111", b001);
		bc111.setErpExternalId("E111");
		ho.barcode("222", b002).setErpExternalId("E222");
		bc333 = ho.barcode("333", b003);
		bc333.setErpExternalId("E333");
		ho.barcode("444", b004).setErpExternalId("E444");
		ho.barcode("999", main);

		p1 = point("P1");
		p2 = point("P2");
		ho.stockPointItem(ho.stockPoints.get(p1), b001, 12.0);
		ho.stockPointItem(ho.stockPoints.get(p1), b002, 20.0).setActive(false);
		ho.stockPointItem(ho.stockPoints.get(p2), b002, 25.0);
		ho.stockPointItem(ho.stockPoints.get(p2), b003, 33.0);

		a = ho.store("A");
		a.setStockPointId(p1);
		b = ho.store("B");
		b.setStockPointId(p2);
		c = ho.store("C"); // no point
		feed.initialise();
	}

	private static Item erp(Item item) {
		item.setErpExternalId(item.getItemCode());
		return item;
	}

	private long point(String code) {
		StockPointDTO input = new StockPointDTO();
		input.setCode(code);
		input.setName("Point " + code);
		return points.create(input).getId();
	}

	/** The item and barcode imports as stubs, called through the real aspect. */
	private ErpItemBootstrapService guarded() {
		ErpItemBootstrapService stub = new ErpItemBootstrapService(null, null, null, null, null, null, null, null, null,
				null, null, null) {
			@Override
			@SuppressWarnings("unchecked")
			public List<Item> importItems(List<ErpItemDTO> list) {
				return (List<Item>) importing.get();
			}

			@Override
			@SuppressWarnings("unchecked")
			public List<ItemBarcode> importItemBarcodes(List<ErpItemBarcodeDTO> list) {
				return (List<ItemBarcode>) importing.get();
			}
		};
		DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
		beans.registerSingleton("hooks", catalogue);
		AspectJProxyFactory factory = new AspectJProxyFactory(stub);
		factory.setProxyTargetClass(true);
		factory.addAspect(new HeadOfficeErpCatalogueRecorder(beans.getBeanProvider(CatalogueHeadOfficeHooks.class),
				TransactionOperations.withoutTransaction(), () -> {
				}));
		return factory.getProxy();
	}

	private static ErpItemBarcodeDTO dto(String externalId, String barcode) {
		ErpItemBarcodeDTO dto = new ErpItemBarcodeDTO();
		dto.setExternalId(externalId);
		dto.setBarcode(barcode);
		return dto;
	}

	/** The stores of the change rows of a code ("*" for the every-store row), sorted. */
	private Set<String> rowsOf(String recordCode) {
		Set<String> stores = new TreeSet<>();
		for (HoDownChange change : down.changes) {
			if (change.getRecordCode().equals(recordCode)) {
				stores.add(change.getStoreId() == null ? "*" : ho.stores.get(change.getStoreId()).getCode());
			}
		}
		return stores;
	}

	private static Set<String> set(String... values) {
		return new TreeSet<>(Arrays.asList(values));
	}

	private CopiesDownAnswerDTO pull(Store store, String cursor) {
		return feed.pull(store, "CATALOGUE", cursor, 500);
	}

	/** The codes of a pull's records, sorted. */
	private static List<String> codes(CopiesDownAnswerDTO answer) {
		return answer.getRecords().stream().map(r -> r.get("kind").asText() + ":" + r.get("ITEM".equals(r.get("kind")
				.asText()) ? "itemCode" : "BARCODE".equals(r.get("kind").asText()) ? "barcode" : "code").asText())
				.sorted().collect(Collectors.toList());
	}

	private static List<String> sorted(List<String> list) {
		List<String> copy = new ArrayList<>(list);
		Collections.sort(copy);
		return copy;
	}

	@Test
	@DisplayName("Backfill: an ERP item and its barcodes for the stores of its points only, none in no point; a hand-made item and the families for every store")
	void backfill() {
		assertEquals(set("A"), rowsOf("ITEM:B001"));
		assertEquals(set("A", "B"), rowsOf("ITEM:B002"), "an inactive row counts");
		assertEquals(set("B"), rowsOf("ITEM:B003"));
		assertEquals(set(), rowsOf("ITEM:B004"), "in no point: no row");
		assertEquals(set("A"), rowsOf("BARCODE:111"));
		assertEquals(set("A", "B"), rowsOf("BARCODE:222"));
		assertEquals(set(), rowsOf("BARCODE:444"));
		assertEquals(set("*"), rowsOf("ITEM:MAIN"));
		assertEquals(set("*"), rowsOf("BARCODE:999"));
		assertEquals(set("*"), rowsOf("FAMILY:F1"));
		assertTrue(down.changes.stream().noneMatch(ch -> ch.getRecordCode().equals("ITEM:TAX_STAMP")));

		CopiesDownAnswerDTO toC = pull(c, "");
		assertTrue(toC.getRemoved().isEmpty(), "a store with no point is not told about the ERP items");
		assertEquals(Arrays.asList("BARCODE:999", "FAMILY:F1", "ITEM:MAIN", "SUBFAMILY:SF1"), codes(toC));
		assertTrue(pull(a, "").getRemoved().isEmpty(), "A is not told about B003 (P2 only) nor B004 (no point)");

		Long version = down.sequences.get(DataDomain.CATALOGUE);
		int rows = down.changes.size();
		feed.initialise();
		assertEquals(version, down.sequences.get(DataDomain.CATALOGUE), "a second start writes nothing again");
		assertEquals(rows, down.changes.size());
	}

	@Test
	@DisplayName("Case 1: an item change that moves no row (VAT) reaches the stores of its points, with its barcodes; a hand-made item every store")
	void itemChangeWithoutRowChange() {
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();
		String cursorC = pull(c, "").getCursor();
		int everyStore = (int) down.changes.stream().filter(ch -> ch.getStoreId() == null).count();
		importing = () -> {
			b001.setDefaultVAT(7);
			b002.setDefaultVAT(7);
			main.setDefaultVAT(7);
			return Arrays.asList(b001, b002, main);
		};
		imports.importItems(null);

		assertEquals(Arrays.asList("BARCODE:111", "BARCODE:222", "BARCODE:999", "ITEM:B001", "ITEM:B002", "ITEM:MAIN"),
				codes(pull(a, cursorA)));
		assertEquals(Arrays.asList("BARCODE:222", "BARCODE:999", "ITEM:B002", "ITEM:MAIN"), codes(pull(b, cursorB)));
		CopiesDownAnswerDTO toC = pull(c, cursorC);
		assertEquals(Arrays.asList("BARCODE:999", "ITEM:MAIN"), codes(toC));
		assertTrue(toC.getRemoved().isEmpty(), "C is told nothing of the ERP items");
		assertEquals(everyStore, down.changes.stream().filter(ch -> ch.getStoreId() == null).count(),
				"no new every-store row: MAIN's and 999's moved");
		assertEquals(7, pull(a, "").getRecords().stream().filter(r -> "B001".equals(r.path("itemCode").asText()))
				.findFirst().get().get("defaultVAT").asInt());
	}

	@Test
	@DisplayName("Case 1: an ERP item in no point records nothing; one import list, one sequence update per group of stores")
	void itemInNoPoint() {
		Long version = down.sequences.get(DataDomain.CATALOGUE);
		int rows = down.changes.size();
		importing = () -> Collections.singletonList(b004);
		imports.importItems(null);
		assertEquals(version, down.sequences.get(DataDomain.CATALOGUE));
		assertEquals(rows, down.changes.size());
	}

	@Test
	@DisplayName("Case 2: a barcode run, a new barcode of an item already in the point: the stores of the point only")
	void barcodeRun() {
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();
		String cursorC = pull(c, "").getCursor();
		importing = () -> Collections.singletonList(ho.barcode("112", b001));
		imports.importItemBarcodes(Collections.singletonList(dto("E112", "112")));
		assertEquals(set("A"), rowsOf("BARCODE:112"));
		assertEquals(Collections.singletonList("BARCODE:112"), codes(pull(a, cursorA)));
		assertTrue(pull(b, cursorB).getRecords().isEmpty());
		assertTrue(pull(c, cursorC).getRecords().isEmpty());
	}

	@Test
	@DisplayName("Case 3: a barcode moved from B001 (P1) to B003 (P2): A gets it as removed, B as a record, C nothing")
	void barcodeMoved() {
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();
		String cursorC = pull(c, "").getCursor();
		importing = () -> {
			bc111.setItem(b003); // found by its ERP id, moved by the ERP to another item
			return Collections.singletonList(bc111);
		};
		imports.importItemBarcodes(Collections.singletonList(dto("E111", "111")));
		assertEquals(set("A", "B"), rowsOf("BARCODE:111"));
		CopiesDownAnswerDTO toA = pull(a, cursorA);
		assertTrue(toA.getRecords().isEmpty());
		assertEquals(Collections.singletonList("BARCODE:111"), toA.getRemoved());
		CopiesDownAnswerDTO toB = pull(b, cursorB);
		assertEquals(Collections.singletonList("BARCODE:111"), codes(toB));
		assertEquals("B003", toB.getRecords().get(0).get("itemCode").asText());
		assertTrue(pull(c, cursorC).getRemoved().isEmpty());
	}

	@Test
	@DisplayName("A barcode moved from the hand-made item to an ERP item: every store, so a store that had it gets it as removed")
	void barcodeMovedFromHandMadeItem() {
		ItemBarcode bc999 = ho.barcodes.values().stream().filter(x -> "999".equals(x.getBarcode())).findFirst().get();
		String cursorC = pull(c, "").getCursor();
		importing = () -> {
			bc999.setItem(b001);
			return Collections.singletonList(bc999);
		};
		imports.importItemBarcodes(Collections.singletonList(dto("E999", "999")));
		assertEquals(Collections.singletonList("BARCODE:999"), pull(c, cursorC).getRemoved());
	}

	@Test
	@DisplayName("A barcode whose value changes under the same ERP id: the old value too, to the stores of its item")
	void barcodeValueChanged() {
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();
		importing = () -> {
			bc333.setBarcode("334"); // found by its ERP id, its value changed
			return Collections.singletonList(bc333);
		};
		imports.importItemBarcodes(Collections.singletonList(dto("E333", "334")));
		assertEquals(set("B"), rowsOf("BARCODE:333"));
		assertEquals(set("B"), rowsOf("BARCODE:334"));
		CopiesDownAnswerDTO toB = pull(b, cursorB);
		assertEquals(Collections.singletonList("BARCODE:334"), codes(toB));
		assertEquals(Collections.singletonList("BARCODE:333"), toB.getRemoved(), "the old value inactive at B");
		assertTrue(pull(a, cursorA).getRemoved().isEmpty());
	}

	@Test
	@DisplayName("Case 4: a pack change of an ERP item reaches the stores of its points; of a hand-made item every store")
	void packChanged() {
		String cursorA = pull(a, "").getCursor();
		String cursorC = pull(c, "").getCursor();
		catalogue.afterPackChanged(b003.getId());
		assertEquals(set("B"), rowsOf("ITEM:B003"));
		assertTrue(pull(a, cursorA).getRecords().isEmpty());
		catalogue.afterPackChanged(main.getId());
		assertEquals(Collections.singletonList("ITEM:MAIN"), codes(pull(c, cursorC)));
	}

	@Test
	@DisplayName("Case 5: a new ERP item has no row yet: nothing recorded; its point's rows then reach the stores of the point")
	void newItem() {
		String cursorA = pull(a, "").getCursor();
		Item b005 = erp(ho.item("B005", 50.0, sf1));
		ho.barcode("555", b005);
		Long version = down.sequences.get(DataDomain.CATALOGUE);
		importing = () -> Collections.singletonList(b005);
		imports.importItems(null);
		assertEquals(set(), rowsOf("ITEM:B005"));
		assertEquals(version, down.sequences.get(DataDomain.CATALOGUE));

		ho.stockPointItem(ho.stockPoints.get(p1), b005, 55.0);
		catalogue.stockPointRowsChanged(p1, Collections.singletonList(b005.getId()),
				Collections.singletonList(b005.getId()));
		assertEquals(Arrays.asList("BARCODE:555", "ITEM:B005"), codes(pull(a, cursorA)));
		assertEquals(set("A"), rowsOf("ITEM:B005"));
	}

	@Test
	@DisplayName("Step 5: at the start, the upgrades run before the backfill; a failed one is logged and the backfill still runs")
	void upgradesBeforeBackfill() {
		InMemoryDownTables fresh = new InMemoryDownTables();
		CopiesDownFeed freshFeed = fresh.feed(Collections.singletonList(catalogue));
		List<Integer> rowsSeen = new ArrayList<>();
		freshFeed.setUpgrades(Arrays.asList(() -> {
			rowsSeen.add(fresh.changes.size());
			return "done";
		}, () -> {
			throw new IllegalStateException("upgrade failed");
		}));
		freshFeed.initialise();
		assertEquals(Collections.singletonList(0), rowsSeen, "ran first, before any row of the backfill");
		assertTrue(fresh.changes.stream().anyMatch(ch -> ch.getRecordCode().equals("ITEM:B001")), "the backfill ran");
	}

	@Test
	@DisplayName("A deleted ERP item: recorded for the stores of its points, with its barcodes")
	void deleted() {
		catalogue.beforeDelete(com.digithink.zsretail.core.model.enumeration.CatalogueKind.ITEM, b003);
		ho.barcodes.values().removeIf(x -> Objects.equals(x.getItem(), b003));
		ho.items.remove(b003.getId());
		String cursorC = pull(c, "").getCursor();
		assertEquals(set("B"), rowsOf("ITEM:B003"));
		assertEquals(set("B"), rowsOf("BARCODE:333"));
		assertEquals(sorted(Arrays.asList("BARCODE:333", "ITEM:B003")),
				sorted(pull(b, "").getRemoved().stream().filter(x -> x.endsWith("3")).collect(Collectors.toList())));
		assertTrue(pull(c, cursorC).getRemoved().isEmpty());
	}
}
