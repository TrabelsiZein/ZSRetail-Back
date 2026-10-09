package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.StockPointDTO;
import com.digithink.zsretail.headoffice.model.HoPriceList;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Stock points, step 3a: the catalogue sent to a store with a point. Two points with different items and prices (fake
 * data): a store receives an ERP item only when its point has a row, built from the row; its barcodes only then, active
 * only with the row; a hand-made item goes everywhere; the price list line wins over the row price; a store without a
 * point receives exactly what it did before. Recording: a row change reaches the stores of its point only; a store whose
 * point changes gets everything again; a point without store records nothing. In-memory tables, no Spring context.
 */
class HoStockPointCatalogueTest {

	private InMemoryCatalogue ho;
	private InMemoryDownTables down;
	private CopiesDownFeed feed;
	private HoCatalogueService catalogue;
	private HoStockPointService points;
	private Item b001;
	private Item b002;
	private Item b003;
	private Item main;
	private long p1;
	private long p2;
	private Store a;
	private Store b;
	private Store c;

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

		ItemFamily f1 = ho.family("F1");
		ItemSubFamily sf1 = ho.subFamily("SF1", f1);
		ItemSubFamily sf2 = ho.subFamily("SF2", f1);
		b001 = erp(ho.item("B001", 10.0, sf1));
		b002 = erp(ho.item("B002", 20.0, sf1));
		b003 = erp(ho.item("B003", 30.0, sf1));
		main = ho.item("MAIN", 5.0, sf1); // made at the head office: no ERP id
		ho.barcode("111", b001);
		ho.barcode("222", b002);
		ho.barcode("333", b003);
		ho.barcode("999", main);

		p1 = point("P1");
		p2 = point("P2");
		HoStockPointItem row = ho.stockPointItem(ho.stockPoints.get(p1), b001, 12.0);
		row.setName("B001 in P1");
		row.setDescription("Sold in P1");
		row.setFamilyCode("F1");
		row.setSubFamilyCode("SF2");
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

	private CopiesDownAnswerDTO pull(Store store, String cursor) {
		return feed.pull(store, "CATALOGUE", cursor, 500);
	}

	/** Each record by its code (ITEM:B001, BARCODE:111). */
	private static Map<String, JsonNode> records(CopiesDownAnswerDTO answer) {
		Map<String, JsonNode> records = new TreeMap<>();
		for (JsonNode record : answer.getRecords()) {
			String kind = record.get("kind").asText();
			records.put(kind + ":" + record.get("ITEM".equals(kind) ? "itemCode" : "BARCODE".equals(kind) ? "barcode"
					: "code").asText(), record);
		}
		return records;
	}

	private static List<String> sorted(List<String> codes) {
		List<String> list = new ArrayList<>(codes);
		Collections.sort(list);
		return list;
	}

	@Test
	@DisplayName("A store without a point receives exactly what a head office without stock points sends")
	void storeWithoutPoint() {
		HoCatalogueService before = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(),
				ho.itemRepository(), ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(),
				() -> feed, TransactionOperations.withoutTransaction());
		List<String> codes = Arrays.asList("ITEM:B001", "ITEM:B002", "ITEM:B003", "ITEM:MAIN", "BARCODE:111",
				"BARCODE:222", "BARCODE:333", "BARCODE:999");
		assertEquals(before.load(c, codes), catalogue.load(c, codes));
		CopiesDownAnswerDTO answer = pull(c, "");
		assertTrue(answer.getRemoved().isEmpty());
		assertEquals(10.0, records(answer).get("ITEM:B001").get("unitPrice").asDouble());
	}

	@Test
	@DisplayName("Two points: each store receives the items of its point with the row's values; the others are removed; a hand-made item goes everywhere")
	void twoPoints() {
		CopiesDownAnswerDTO toA = pull(a, "");
		Map<String, JsonNode> ofA = records(toA);
		JsonNode b001A = ofA.get("ITEM:B001");
		assertEquals(12.0, b001A.get("unitPrice").asDouble());
		assertEquals("B001 in P1", b001A.get("name").asText());
		assertEquals("Sold in P1", b001A.get("description").asText());
		assertEquals("SF2", b001A.get("subFamilyCode").asText());
		assertEquals(19, b001A.get("defaultVAT").asInt(), "the item's own fields for the rest");
		assertTrue(b001A.get("active").asBoolean());
		assertFalse(ofA.get("ITEM:B002").get("active").asBoolean(), "inactive in P1");
		assertEquals(5.0, ofA.get("ITEM:MAIN").get("unitPrice").asDouble());
		assertTrue(ofA.get("BARCODE:111").get("active").asBoolean());
		assertFalse(ofA.get("BARCODE:222").get("active").asBoolean(), "the row is inactive");
		assertTrue(ofA.containsKey("BARCODE:999"));
		assertEquals(Arrays.asList("BARCODE:333", "ITEM:B003"), sorted(toA.getRemoved()));

		CopiesDownAnswerDTO toB = pull(b, "");
		Map<String, JsonNode> ofB = records(toB);
		assertEquals(25.0, ofB.get("ITEM:B002").get("unitPrice").asDouble());
		assertTrue(ofB.get("ITEM:B002").get("active").asBoolean());
		assertEquals(33.0, ofB.get("ITEM:B003").get("unitPrice").asDouble());
		assertEquals("Item B003", ofB.get("ITEM:B003").get("name").asText(), "the row's name");
		assertTrue(ofB.get("BARCODE:222").get("active").asBoolean());
		assertEquals(Arrays.asList("BARCODE:111", "ITEM:B001"), sorted(toB.getRemoved()));
	}

	@Test
	@DisplayName("Price: the store's price list line first, then the row's price")
	void priceListFirst() {
		HoPriceList list = ho.priceList("TOURIST");
		ho.priceLine(list, b001, 9.5);
		a.setSellingPriceListId(list.getId());
		Map<String, JsonNode> ofA = records(pull(a, ""));
		assertEquals(9.5, ofA.get("ITEM:B001").get("unitPrice").asDouble());
		assertEquals(20.0, ofA.get("ITEM:B002").get("unitPrice").asDouble(), "no line: the row's price");
	}

	@Test
	@DisplayName("A row change reaches the stores of its point only; an active change or an item entering sends its barcodes too")
	void rowChangesReachThePointOnly() {
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();
		String cursorC = pull(c, "").getCursor();

		catalogue.stockPointRowsChanged(p1, Collections.singletonList(b001.getId()), Collections.emptyList());
		assertEquals(Collections.singletonList("ITEM:B001"), new ArrayList<>(records(pull(a, cursorA)).keySet()));
		assertTrue(pull(b, cursorB).getRecords().isEmpty());
		assertTrue(pull(c, cursorC).getRecords().isEmpty());

		cursorA = pull(a, cursorA).getCursor();
		catalogue.stockPointRowsChanged(p1, Collections.singletonList(b002.getId()),
				Collections.singletonList(b002.getId()));
		assertEquals(Arrays.asList("BARCODE:222", "ITEM:B002"), new ArrayList<>(records(pull(a, cursorA)).keySet()));
		assertTrue(pull(b, cursorB).getRecords().isEmpty());
	}

	@Test
	@DisplayName("A point without a store records nothing: the sequence does not move")
	void pointWithoutStore() {
		long p3 = point("P3");
		ho.stockPointItem(ho.stockPoints.get(p3), b001, 15.0);
		Long before = down.sequences.get(DataDomain.CATALOGUE);
		int rows = down.changes.size();
		catalogue.stockPointRowsChanged(p3, Collections.singletonList(b001.getId()),
				Collections.singletonList(b001.getId()));
		assertEquals(before, down.sequences.get(DataDomain.CATALOGUE));
		assertEquals(rows, down.changes.size());
	}

	@Test
	@DisplayName("A store moved to no point, then back: everything is sent again to that store only, each time")
	void storeMoved() {
		String cursorA = pull(a, "").getCursor();
		String cursorB = pull(b, "").getCursor();

		a.setStockPointId(null);
		assertEquals(8, points.storeStockPointChanged(a.getId(), p1, null));
		CopiesDownAnswerDTO toA = pull(a, cursorA);
		assertEquals(8, toA.getRecords().size(), "4 items and 4 barcodes, as a store without a point");
		assertTrue(toA.getRemoved().isEmpty());
		assertEquals(30.0, records(toA).get("ITEM:B003").get("unitPrice").asDouble());
		assertTrue(pull(b, cursorB).getRecords().isEmpty());

		cursorA = toA.getCursor();
		a.setStockPointId(p1);
		assertEquals(8, points.storeStockPointChanged(a.getId(), null, p1));
		CopiesDownAnswerDTO back = pull(a, cursorA);
		Map<String, JsonNode> first = records(pull(a, ""));
		first.keySet().removeIf(code -> code.startsWith("FAMILY:") || code.startsWith("SUBFAMILY:")); // not sent again
		assertEquals(first, records(back), "the items and barcodes of a first pull with P1");
		assertEquals(Arrays.asList("BARCODE:333", "ITEM:B003"), sorted(back.getRemoved()));
		assertEquals(0, points.storeStockPointChanged(a.getId(), p1, p1), "same point: nothing");
	}

	@Test
	@DisplayName("A point can be given to a store only when it is active and has rows")
	void assignable() {
		points.checkAssignable(p1);
		long empty = point("EMPTY");
		assertEquals(String.format(HoStockPointService.NO_ROWS, "EMPTY"),
				assertThrows(IllegalArgumentException.class, () -> points.checkAssignable(empty)).getMessage());
		ho.stockPoints.get(p2).setActive(false);
		assertThrows(IllegalArgumentException.class, () -> points.checkAssignable(p2));
		assertThrows(IllegalArgumentException.class, () -> points.checkAssignable(999L));
	}
}
