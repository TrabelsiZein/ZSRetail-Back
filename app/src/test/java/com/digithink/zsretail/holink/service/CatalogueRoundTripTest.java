package com.digithink.zsretail.holink.service;

import java.math.BigDecimal;
import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.CatalogueFamilyCopyDTO;
import com.digithink.zsretail.headoffice.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.headoffice.dto.CatalogueSubFamilyCopyDTO;
import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.PriceListLineDTO;
import com.digithink.zsretail.headoffice.model.HoPriceList;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;
import com.digithink.zsretail.headoffice.service.HoCatalogueService;
import com.digithink.zsretail.headoffice.service.HoPriceListService;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.LinkExchange;
import com.digithink.zsretail.holink.model.LinkRight;
import com.digithink.zsretail.holink.repository.LinkRightRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.SalesPriceRepository;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryLoyalty;
import com.digithink.zsretail.support.InMemoryStoreLink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Head office plan, tasks 6.2 to 6.5: the catalogue from the head office to a store, through the real feed (head office)
 * and the real handler and writer (store), in memory. Codes resolved, a local item of the same code taken over (stock and
 * cost kept), deactivation and removal give inactive (never deleted), missing family waits then applies, a barcode moved
 * with its exchange row (and an old item.barcode field cleared), own price kept across a pull and given back when the
 * right goes off, price list per store, the store's other local items untouched, unchanged copies write nothing.
 */
class CatalogueRoundTripTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private InMemoryCatalogue ho;
	private InMemoryCatalogue store;
	private InMemoryStoreLink link;
	private CopiesDownFeed feed;
	private HoCatalogueService hoCatalogue;
	private HoPriceListService priceLists;
	private CatalogueDownHandler handler;
	private CatalogueRights rights;
	private StoreCatalogueGuard guard;
	private final Map<String, LinkRight> savedRights = new HashMap<>();

	private Store b;
	private String cursor = "";

	private ItemFamily f1;
	private ItemSubFamily sf1;
	private Item b001;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		store = new InMemoryCatalogue(1000);
		link = new InMemoryStoreLink(new InMemoryLoyalty(5000));
		InMemoryDownTables down = new InMemoryDownTables();
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		hoCatalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(), ho.itemRepository(),
				ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(), () -> feedRef[0],
				TransactionOperations.withoutTransaction());
		feed = down.feed(Collections.singletonList(hoCatalogue));
		feedRef[0] = feed;
		priceLists = new HoPriceListService(ho.priceListRepository(), ho.priceLineRepository(), ho.storeRepository(),
				ho.itemRepository(), () -> hoCatalogue);

		rights = new CatalogueRights(rightRepository());
		CatalogueCopyWriter writer = new CatalogueCopyWriter(store.familyRepository(), store.subFamilyRepository(),
				store.itemRepository(), store.barcodeRepository(), store.compositionRepository());
		handler = new CatalogueDownHandler(writer, link.downRecordLog(), rights, link.exchangeLog(),
				TransactionOperations.withoutTransaction());
		SalesPriceRepository salesPrices = proxy(SalesPriceRepository.class,
				(method, args) -> "countOnItemsOfOrigin".equals(method) ? 0L : UNHANDLED);
		guard = new StoreCatalogueGuard(rights, store.itemRepository(), store.familyRepository(),
				store.subFamilyRepository(), store.barcodeRepository(), salesPrices, writer, new HeadOfficeLinkStatus());

		f1 = ho.family("F1");
		sf1 = ho.subFamily("SF1", f1);
		b001 = ho.item("B001", 10.0, sf1);
		ho.barcode("6191234567890", b001);
		b = ho.store("B");
	}

	private LinkRightRepository rightRepository() {
		return proxy(LinkRightRepository.class, (method, args) -> {
			switch (method) {
				case "findByCode":
					return Optional.ofNullable(savedRights.get(args[0]));
				case "save":
					LinkRight right = (LinkRight) args[0];
					savedRights.put(right.getCode(), right);
					return right;
				default:
					return UNHANDLED;
			}
		});
	}

	/** One cycle as CopiesDownPuller runs it: prepare, the pull, the retries. */
	private void sync() {
		handler.prepare();
		feed.initialise(); // the startup backfill: records without a change row
		CopiesDownAnswerDTO page = feed.pull(b, "CATALOGUE", cursor, 500);
		handler.apply(page.getRecords(), page.getRemoved());
		cursor = page.getCursor();
		handler.retry();
	}

	private Item storeItem(String code) {
		return store.itemByCode(code).orElseThrow(() -> new AssertionError("no item " + code + " at the store"));
	}

	private List<LinkExchange> warnings() {
		return link.exchanges.stream().filter(e -> e.getResult() == LinkJobResult.WARNING).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Created at the head office: saved at the store by codes, origin HEAD_OFFICE, the price for the store")
	void created() {
		sync();
		Item item = storeItem("B001");
		assertEquals(RecordOrigin.HEAD_OFFICE, item.getOrigin());
		assertEquals(10.0, item.getUnitPrice());
		assertEquals(10.0, item.getHeadOfficePrice());
		assertNull(item.getStockQuantity(), "stock never travels");
		assertEquals("SF1", item.getItemSubFamily().getCode());
		assertEquals("F1", item.getItemFamily().getCode());
		assertEquals(RecordOrigin.HEAD_OFFICE, item.getItemFamily().getOrigin());
		ItemBarcode barcode = store.barcodeByValue("6191234567890").get();
		assertEquals(item.getId(), barcode.getItem().getId());
		assertEquals(RecordOrigin.HEAD_OFFICE, barcode.getOrigin());
		assertEquals(4, link.downRecords.values().stream().filter(r -> r.getStatus() == DownRecordStatus.APPLIED).count());
	}

	@Test
	@DisplayName("A local item with the same code becomes the head office item: values and price replaced, stock and cost kept;"
			+ " the store's other local items stay as they are")
	void sameCodeTakenOver() {
		ItemFamily localFamily = store.family("LOCALF");
		Item local = store.item("B001", 9.0, null);
		local.setItemFamily(localFamily);
		local.setName("Old name");
		local.setStockQuantity(BigDecimal.valueOf(40));
		local.setCostPrice(6.0);
		local.setLastDirectCost(6.2);
		local.setImageUrl("7.jpg");
		Item other = store.item("L9", 5.0, null);
		sync();
		Item item = storeItem("B001");
		assertEquals(local.getId(), item.getId(), "the same row");
		assertEquals(RecordOrigin.HEAD_OFFICE, item.getOrigin());
		assertEquals("Item B001", item.getName());
		assertEquals(10.0, item.getUnitPrice());
		assertEquals(BigDecimal.valueOf(40), item.getStockQuantity());
		assertEquals(6.0, item.getCostPrice());
		assertEquals(6.2, item.getLastDirectCost());
		assertEquals("7.jpg", item.getImageUrl());
		assertEquals("F1", item.getItemFamily().getCode());
		assertTrue(other.getActive() && other.getOrigin() == null, "local items are not switched off");
		assertTrue(localFamily.getActive());
		assertEquals("a local item of this store became the head office item", link.downRecords.get("ITEM:B001").getInfo());
	}

	@Test
	@DisplayName("Deactivated or deleted at the head office: inactive at the store, never deleted; a local code is not touched")
	void deactivatedAndRemoved() {
		Item b002 = ho.item("B002", 20.0, sf1);
		sync();
		b001.setActive(false);
		hoCatalogue.afterSave(CatalogueKind.ITEM, "B001", b001);
		sync();
		assertFalse(storeItem("B001").getActive());
		assertFalse(store.barcodeByValue("6191234567890").get().getActive(), "the barcode of an inactive item");

		hoCatalogue.beforeDelete(CatalogueKind.ITEM, b002);
		ho.items.remove(b002.getId());
		Item localSameCodeElsewhere = store.item("LOC1", 1.0, null);
		sync();
		assertFalse(storeItem("B002").getActive(), "deleted there: inactive here");
		assertTrue(store.items.containsKey(storeItem("B002").getId()), "never deleted");
		assertTrue(handler.apply(Collections.emptyList(), Collections.singletonList("ITEM:LOC1")).isEmpty());
		assertTrue(localSameCodeElsewhere.getActive(), "a removal never touches a local record");
	}

	@Test
	@DisplayName("A family missing at the store: the item waits, then applies by itself when the family arrives")
	void waitsForItsFamily() {
		CatalogueItemCopyDTO item = CatalogueItemCopyDTO.of(b001, 10.0, null);
		handler.apply(Collections.singletonList(MAPPER.valueToTree(item)), Collections.emptyList());
		handler.retry(); // the end of the cycle: still waiting
		assertEquals(DownRecordStatus.WAITING, link.downRecords.get("ITEM:B001").getStatus());
		assertEquals("not in this store: family F1, sub-family SF1", link.downRecords.get("ITEM:B001").getReason());
		assertFalse(store.itemByCode("B001").isPresent());

		List<JsonNode> page = Arrays.asList(MAPPER.valueToTree(CatalogueSubFamilyCopyDTO.of(sf1)),
				MAPPER.valueToTree(CatalogueFamilyCopyDTO.of(f1))); // out of order on purpose
		handler.apply(page, Collections.emptyList());
		handler.retry();
		assertEquals(DownRecordStatus.APPLIED, link.downRecords.get("ITEM:B001").getStatus());
		assertEquals(10.0, storeItem("B001").getUnitPrice());
	}

	@Test
	@DisplayName("Own price: kept across a pull with the head office price beside; given back when the right goes off")
	void ownPrice() {
		sync();
		Item item = storeItem("B001");
		assertThrowsState(() -> guard.setOwnPrice(item.getId(), 12.0)); // never received: off
		rights.received(true, false, LocalDateTime.now());
		guard.setOwnPrice(item.getId(), 12.0);
		assertEquals(12.0, item.getUnitPrice());
		assertTrue(item.getOwnPrice());

		b001.setUnitPrice(11.0);
		hoCatalogue.afterSave(CatalogueKind.ITEM, "B001", b001);
		sync();
		assertEquals(12.0, item.getUnitPrice(), "own price kept");
		assertEquals(11.0, item.getHeadOfficePrice(), "head office price beside");

		rights.received(false, false, LocalDateTime.now());
		sync();
		assertEquals(11.0, item.getUnitPrice(), "the head office price back at the next cycle");
		assertNull(item.getOwnPrice());
		List<LinkExchange> rows = warnings();
		assertEquals(1, rows.size());
		assertTrue(rows.get(0).getError().contains("1 own prices replaced by the head office price"), rows.get(0).getError());

		rights.received(true, false, LocalDateTime.now());
		guard.setOwnPrice(item.getId(), 13.0);
		guard.giveBackPrice(item.getId());
		assertEquals(11.0, item.getUnitPrice());
		assertNull(item.getOwnPrice());
	}

	@Test
	@DisplayName("A barcode of a local item moves to the head office item (one WARNING row); an old item.barcode field with"
			+ " the same value is cleared, so a scan finds only the head office item")
	void barcodeMoved() {
		Item local = store.item("L1", 3.0, null);
		ItemBarcode localBarcode = store.barcode("6191234567890", local);
		Item legacy = store.item("L2", 4.0, null);
		legacy.setBarcode("6191234567890");
		sync();
		Item item = storeItem("B001");
		assertEquals(item.getId(), localBarcode.getItem().getId(), "the same row, moved");
		assertEquals(RecordOrigin.HEAD_OFFICE, localBarcode.getOrigin());
		assertNull(legacy.getBarcode());
		List<Item> holders = store.items.values().stream()
				.filter(i -> "6191234567890".equals(i.getBarcode())).collect(Collectors.toList());
		assertTrue(holders.isEmpty());
		List<LinkExchange> rows = warnings();
		assertEquals(1, rows.size(), "one row per moved barcode");
		assertEquals("CATALOGUE: barcode 6191234567890 moved from the store's item L1 and removed from the old barcode field"
				+ " of the store's item L2 to the head office item B001", rows.get(0).getError());
		sync();
		assertEquals(1, warnings().size(), "moved once");
	}

	@Test
	@DisplayName("A copy received again unchanged writes nothing")
	void unchangedWritesNothing() {
		sync();
		int itemSaves = store.itemSaves;
		int barcodeSaves = store.barcodeSaves;
		hoCatalogue.afterSave(CatalogueKind.ITEM, "B001", b001); // sends the item and its barcode again
		sync();
		assertEquals(itemSaves, store.itemSaves);
		assertEquals(barcodeSaves, store.barcodeSaves);
	}

	@Test
	@DisplayName("Price list: the store on the list gets the list's price; a line change reaches it")
	void priceList() {
		HoPriceList tourist = ho.priceList("TOURIST");
		b.setSellingPriceListId(tourist.getId());
		sync();
		assertEquals(10.0, storeItem("B001").getUnitPrice());
		PriceListLineDTO line = new PriceListLineDTO();
		line.setItemCode("B001");
		line.setPrice(11.0);
		priceLists.putLines(tourist.getId(), Collections.singletonList(line));
		sync();
		assertEquals(11.0, storeItem("B001").getUnitPrice());
	}

	@Test
	@DisplayName("A pack arrives with its components")
	void pack() {
		Item b002 = ho.item("B002", 2.0, sf1);
		Item pack = ho.item("PACK1", 0.0, sf1);
		pack.setType(ItemType.PACKAGE);
		ho.component(pack, b001, 2);
		ho.component(pack, b002, 1);
		sync();
		Item storePack = storeItem("PACK1");
		assertEquals(ItemType.PACKAGE, storePack.getType());
		Map<String, Integer> components = store.compositions.values().stream()
				.filter(c -> c.getParentItem().getId().equals(storePack.getId()))
				.collect(Collectors.toMap(c -> c.getComponentItem().getItemCode(), c -> c.getQuantity()));
		Map<String, Integer> expected = new HashMap<>();
		expected.put("B001", 2);
		expected.put("B002", 1);
		assertEquals(expected, components);
	}

	private static void assertThrowsState(Runnable call) {
		try {
			call.run();
		} catch (IllegalStateException e) {
			assertEquals(StoreCatalogueGuard.PRICE_RIGHT_OFF, e.getMessage());
			return;
		}
		throw new AssertionError("expected the right off");
	}
}
