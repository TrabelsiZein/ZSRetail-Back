package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.model.HoPriceList;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.service.CatalogueCodeChangeException;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Head office plan, task 6.1: the catalogue on the copies down. Payload by codes only (never stock, cost, franchise,
 * ERP or image fields), every record for every store, one change number per change, the price worked out per store
 * (task 6.4), the barcodes of a deactivated item sent inactive, TAX_STAMP never sent, a code change refused, the
 * startup backfill. In-memory tables, no Spring context.
 */
class HoCatalogueServiceTest {

	private InMemoryCatalogue ho;
	private InMemoryDownTables down;
	private HoCatalogueService catalogue;
	private CopiesDownFeed feed;

	private ItemFamily f1;
	private ItemSubFamily sf1;
	private Item b001;
	private Store a;
	private Store b;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		down = new InMemoryDownTables();
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		catalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(), ho.itemRepository(),
				ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(), () -> feedRef[0],
				TransactionOperations.withoutTransaction());
		feed = down.feed(Collections.singletonList(catalogue));
		feedRef[0] = feed;

		f1 = ho.family("F1");
		sf1 = ho.subFamily("SF1", f1);
		b001 = ho.item("B001", 10.0, sf1);
		b001.setStockQuantity(40);
		b001.setMinStockLevel(5);
		b001.setCostPrice(6.0);
		b001.setLastDirectCost(6.1);
		b001.setLastDirectNetCost(5.9);
		b001.setFranchiseSalesPrice(12.0);
		b001.setFromFranchiseAdmin(true);
		b001.setErpExternalId("NAV-1");
		b001.setImageUrl("3.jpg");
		b001.setBrand("ACME");
		ho.barcode("6191234567890", b001);
		a = ho.store("A");
		b = ho.store("B");
		feed.initialise(); // backfill: every record for every store
	}

	private Map<String, JsonNode> pullAll(Store store) {
		Map<String, JsonNode> records = new LinkedHashMap<>();
		CopiesDownAnswerDTO answer = feed.pull(store, "catalogue", "", 500);
		for (JsonNode record : answer.getRecords()) {
			records.put(recordCode(record), record);
		}
		for (String removed : answer.getRemoved()) {
			records.put(removed, null);
		}
		return records;
	}

	private List<String> changedSince(Store store, String cursor) {
		CopiesDownAnswerDTO answer = feed.pull(store, "CATALOGUE", cursor, 500);
		List<String> codes = new ArrayList<>();
		answer.getRecords().forEach(r -> codes.add(recordCode(r)));
		codes.addAll(answer.getRemoved());
		return codes;
	}

	/** FAMILY:F1, ITEM:B001, BARCODE:619... of a copy. */
	private static String recordCode(JsonNode record) {
		CatalogueKind kind = CatalogueKind.valueOf(record.get("kind").asText());
		String field = kind == CatalogueKind.ITEM ? "itemCode" : kind == CatalogueKind.BARCODE ? "barcode" : "code";
		return kind.recordCode(record.get(field).asText());
	}

	private String cursor(Store store) {
		return feed.pull(store, "CATALOGUE", "", 500).getCursor();
	}

	@Test
	@DisplayName("Backfill: families, sub-families, items and barcodes for every store, by codes only, one number each")
	void backfillByCodes() {
		Map<String, JsonNode> records = pullAll(a);
		assertEquals(Arrays.asList("FAMILY:F1", "SUBFAMILY:SF1", "ITEM:B001", "BARCODE:6191234567890"),
				new ArrayList<>(records.keySet()));
		assertEquals(records.keySet(), pullAll(b).keySet(), "every store");
		assertEquals(Long.valueOf(4), down.sequences.get(DataDomain.CATALOGUE));

		assertEquals("F1", records.get("SUBFAMILY:SF1").get("familyCode").asText());
		JsonNode item = records.get("ITEM:B001");
		assertEquals("F1", item.get("familyCode").asText());
		assertEquals("SF1", item.get("subFamilyCode").asText());
		assertEquals(10.0, item.get("unitPrice").asDouble());
		assertEquals(19, item.get("defaultVAT").asInt());
		assertEquals("ACME", item.get("brand").asText());
		assertTrue(item.get("active").asBoolean());
		for (String never : Arrays.asList("id", "stockQuantity", "minStockLevel", "costPrice", "lastDirectCost",
				"lastDirectNetCost", "franchiseSalesPrice", "fromFranchiseAdmin", "erpExternalId", "imageUrl",
				"imageFilename", "itemFamily", "itemSubFamily")) {
			assertFalse(item.has(never), "never sent: " + never);
		}
		assertFalse(records.get("FAMILY:F1").has("imageFilename"));
		assertFalse(records.get("FAMILY:F1").has("id"));
		assertEquals("B001", records.get("BARCODE:6191234567890").get("itemCode").asText());
	}

	@Test
	@DisplayName("TAX_STAMP and its barcodes never travel (backfill and saves)")
	void taxStampNeverSent() {
		Item stamp = ho.item(CatalogueKind.TAX_STAMP_CODE, 1.0, null);
		ItemBarcode stampBarcode = ho.barcode("STAMP", stamp);
		String cursor = cursor(a);
		catalogue.afterSave(CatalogueKind.ITEM, null, stamp);
		catalogue.afterSave(CatalogueKind.BARCODE, null, stampBarcode);
		catalogue.afterImport(CatalogueKind.ITEM, Collections.singletonList(CatalogueKind.TAX_STAMP_CODE));
		assertEquals(Collections.emptyList(), changedSince(a, cursor));
		assertFalse(catalogue.currentTargets().containsKey("ITEM:TAX_STAMP"));
		assertFalse(catalogue.currentTargets().containsKey("BARCODE:STAMP"));
	}

	@Test
	@DisplayName("A save is one change for every store; an item's save also sends its barcodes (active only with the item)")
	void itemSaveSendsItsBarcodes() {
		String cursorA = cursor(a);
		String cursorB = cursor(b);
		b001.setActive(false);
		catalogue.afterSave(CatalogueKind.ITEM, "B001", b001);
		assertEquals(Arrays.asList("ITEM:B001", "BARCODE:6191234567890"), changedSince(a, cursorA));
		assertEquals(Arrays.asList("ITEM:B001", "BARCODE:6191234567890"), changedSince(b, cursorB));
		Map<String, JsonNode> records = pullAll(a);
		assertFalse(records.get("ITEM:B001").get("active").asBoolean());
		assertFalse(records.get("BARCODE:6191234567890").get("active").asBoolean(),
				"the barcode of an inactive item is sent inactive");

		b001.setActive(true);
		assertTrue(pullAll(a).get("BARCODE:6191234567890").get("active").asBoolean());
	}

	@Test
	@DisplayName("Price per store: no list, a list with the item, a list without the item")
	void pricePerStore() {
		Item b002 = ho.item("B002", 20.0, sf1);
		HoPriceList tourist = ho.priceList("TOURIST");
		ho.priceLine(tourist, b001, 11.0);
		HoPriceList airport = ho.priceList("AIRPORT");
		Store c = ho.store("C");
		b.setSellingPriceListId(tourist.getId());
		c.setSellingPriceListId(airport.getId());
		catalogue.afterSave(CatalogueKind.ITEM, null, b002);

		assertEquals(10.0, pullAll(a).get("ITEM:B001").get("unitPrice").asDouble(), "no list: base price");
		assertEquals(11.0, pullAll(b).get("ITEM:B001").get("unitPrice").asDouble(), "the list's line");
		assertEquals(20.0, pullAll(b).get("ITEM:B002").get("unitPrice").asDouble(), "list without the item: base");
		assertEquals(10.0, pullAll(c).get("ITEM:B001").get("unitPrice").asDouble(), "empty list: base");
	}

	@Test
	@DisplayName("A pack carries its active components by item code, sorted")
	void packComponents() {
		Item b002 = ho.item("B002", 2.0, sf1);
		Item b003 = ho.item("B003", 3.0, sf1);
		Item pack = ho.item("PACK1", 0.0, sf1);
		pack.setType(ItemType.PACKAGE);
		ho.component(pack, b003, 2);
		ho.component(pack, b002, 1).setActive(false);
		ho.component(pack, b001, 3);
		String cursor = cursor(a);
		catalogue.afterPackChanged(pack.getId());
		assertEquals(Collections.singletonList("ITEM:PACK1"), changedSince(a, cursor));
		JsonNode components = pullAll(a).get("ITEM:PACK1").get("components");
		assertEquals(2, components.size());
		assertEquals("B001", components.get(0).get("itemCode").asText());
		assertEquals(3, components.get(0).get("quantity").asInt());
		assertEquals("B003", components.get(1).get("itemCode").asText());
		assertEquals(0, pullAll(a).get("ITEM:B001").get("components").size());
	}

	@Test
	@DisplayName("The code of an item, a family or a sub-family cannot change; a barcode value can (the old one is removed)")
	void codeChanges() {
		assertThrows(CatalogueCodeChangeException.class, () -> catalogue.beforeSave(CatalogueKind.ITEM, "B000", b001));
		assertThrows(CatalogueCodeChangeException.class, () -> catalogue.beforeSave(CatalogueKind.FAMILY, "F0", f1));
		assertThrows(CatalogueCodeChangeException.class,
				() -> catalogue.beforeSave(CatalogueKind.SUBFAMILY, "SF0", sf1));
		catalogue.beforeSave(CatalogueKind.ITEM, "B001", b001);
		catalogue.beforeSave(CatalogueKind.ITEM, null, b001);

		ItemBarcode barcode = ho.barcodeByValue("6191234567890").get();
		catalogue.beforeSave(CatalogueKind.BARCODE, "6191234567890", barcode);
		String cursor = cursor(a);
		barcode.setBarcode("6190000000001");
		catalogue.afterSave(CatalogueKind.BARCODE, "6191234567890", barcode);
		assertEquals(Arrays.asList("BARCODE:6190000000001", "BARCODE:6191234567890"),
				new ArrayList<>(new java.util.TreeSet<>(changedSince(a, cursor))));
		assertNull(pullAll(a).get("BARCODE:6191234567890"), "the old value is removed");

		Item longCode = ho.item(String.join("", Collections.nCopies(91, "X")), 1.0, sf1);
		assertThrows(IllegalArgumentException.class, () -> catalogue.beforeSave(CatalogueKind.ITEM, null, longCode));
	}

	@Test
	@DisplayName("A deleted item is removed for every store, with its barcodes; its price list lines are deleted")
	void deleteItem() {
		HoPriceList tourist = ho.priceList("TOURIST");
		ho.priceLine(tourist, b001, 11.0);
		String cursor = cursor(a);
		catalogue.beforeDelete(CatalogueKind.ITEM, b001);
		ho.barcodes.clear();
		ho.items.remove(b001.getId());
		assertTrue(ho.priceLines.isEmpty());
		Map<String, JsonNode> after = new LinkedHashMap<>();
		CopiesDownAnswerDTO answer = feed.pull(a, "CATALOGUE", cursor, 500);
		answer.getRemoved().forEach(code -> after.put(code, null));
		assertEquals(new java.util.HashSet<>(Arrays.asList("ITEM:B001", "BARCODE:6191234567890")), after.keySet());
	}

	@Test
	@DisplayName("A data import: each saved code one change, in chunks; blank and too long codes skipped")
	void dataImport() {
		List<String> codes = new ArrayList<>();
		for (int i = 0; i < 1203; i++) {
			ho.family("IMP" + i);
			codes.add("IMP" + i);
		}
		codes.add(" ");
		codes.add(String.join("", Collections.nCopies(91, "Y")));
		String cursor = cursor(a);
		long before = down.sequences.get(DataDomain.CATALOGUE);
		catalogue.afterImport(CatalogueKind.FAMILY, codes);
		assertEquals(before + 1203, down.sequences.get(DataDomain.CATALOGUE).longValue());
		assertEquals(500, feed.pull(a, "CATALOGUE", cursor, 500).getRecords().size());
	}

	@Test
	@DisplayName("Startup backfill on the head office's data: the next start adds nothing")
	void backfillOnce() {
		long after = down.sequences.get(DataDomain.CATALOGUE);
		feed.initialise();
		assertEquals(after, down.sequences.get(DataDomain.CATALOGUE).longValue());
	}
}
