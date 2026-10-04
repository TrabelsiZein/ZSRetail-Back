package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.PriceListDTO;
import com.digithink.zsretail.headoffice.dto.PriceListLineDTO;
import com.digithink.zsretail.headoffice.dto.SupplyPriceDTO;
import com.digithink.zsretail.headoffice.enumeration.InvoiceRhythm;
import com.digithink.zsretail.headoffice.enumeration.PriceListKind;
import com.digithink.zsretail.headoffice.enumeration.SupplyPriceMode;
import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.headoffice.model.HoPriceList;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service._BaseService;
import com.digithink.zsretail.support.InMemoryCatalogue;

/**
 * Head office plan, step 7B, part 1: the invoicing settings of a store, the price lists of kind SUPPLY and the base
 * supply prices. The supply price of a store in PRICE_LIST mode (its supply list line, else the base supply price, else
 * none) and in PERCENT_OFF mode (its selling price, list line or base, minus the percentage), to the millime; a supply
 * price never records a change for any store. Real HoSupplyPriceService, HoPriceListService, StoreService and
 * CopiesDownFeed over in-memory tables.
 */
class HoSupplyPriceServiceTest {

	private InMemoryCatalogue ho;
	private InMemoryDownTables down;
	private HoPriceListService lists;
	private HoSupplyPriceService supply;
	private StoreService stores;
	private Item b001;
	private Item b002;
	private Item b003;
	private Store b;
	private HoPriceList tourist;
	private HoPriceList franchise;

	@BeforeEach
	void setUp() throws Exception {
		ho = new InMemoryCatalogue(1);
		down = new InMemoryDownTables();
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		HoCatalogueService catalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(),
				ho.itemRepository(), ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(),
				() -> feedRef[0], TransactionOperations.withoutTransaction());
		feedRef[0] = down.feed(Collections.singletonList(catalogue));
		lists = new HoPriceListService(ho.priceListRepository(), ho.priceLineRepository(), ho.storeRepository(),
				ho.itemRepository(), () -> catalogue);
		supply = new HoSupplyPriceService(ho.supplyPriceRepository(), ho.priceLineRepository(), ho.itemRepository());
		stores = new StoreService();
		set(stores, StoreService.class, "storeRepository", ho.storeRepository());
		set(stores, StoreService.class, "priceLists", new StaticListableBeanFactory(
				Collections.singletonMap("lists", lists)).getBeanProvider(HoPriceListService.class));
		set(stores, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}
		});
		b001 = ho.item("B001", 10.0, null);
		b002 = ho.item("B002", 20.0, null);
		b003 = ho.item("B003", 3.333, null);
		b = ho.store("B");
		tourist = ho.priceList("TOURIST"); // a selling list (kind null)
		franchise = ho.priceList("FRANCHISE");
		franchise.setKind(PriceListKind.SUPPLY);
		feedRef[0].initialise();
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private long catalogueVersion() {
		return down.changes.stream().filter(c -> c.getDomain() == DataDomain.CATALOGUE)
				.mapToLong(HoDownChange::getChangeVersion).max().orElse(0);
	}

	@Test
	@DisplayName("PRICE_LIST mode: the supply list line, else the base supply price, else none; to the millime")
	void priceListMode() throws Exception {
		ho.supplyPrice(b001, 6.0);
		ho.supplyPrice(b002, 12.3456);
		ho.priceLine(franchise, b001, 5.5);
		ho.priceLine(tourist, b002, 25.0); // a selling list line plays no part in this mode

		assertEquals("{" + b001.getId() + "=6.0, " + b002.getId() + "=12.346}",
				sorted(supply.pricesFor(b, Arrays.asList(b001, b002, b003))), "base prices; B003 has none");

		stores.setSupplyPriceList(b.getId(), franchise.getId());
		Map<Long, Double> withList = supply.pricesFor(b, Arrays.asList(b001, b002, b003));
		assertEquals(5.5, withList.get(b001.getId()), "the list line wins");
		assertEquals(12.346, withList.get(b002.getId()), "not on the list: the base supply price");
		assertFalse(withList.containsKey(b003.getId()));
	}

	@Test
	@DisplayName("PERCENT_OFF mode: the store's selling price (list line, else base) minus the percentage; no percentage: 409")
	void percentOffMode() {
		b.setSupplyPriceMode(SupplyPriceMode.PERCENT_OFF);
		IllegalStateException none = assertThrows(IllegalStateException.class,
				() -> supply.pricesFor(b, Collections.singletonList(b001)));
		assertTrue(none.getMessage().startsWith("The store B takes a percentage off its selling price"), none.getMessage());

		b.setSupplyDiscountPercent(30.0);
		ho.supplyPrice(b001, 1.0); // not used in this mode
		ho.priceLine(tourist, b002, 25.0);
		b.setSellingPriceListId(tourist.getId());
		Map<Long, Double> prices = supply.pricesFor(b, Arrays.asList(b001, b002, b003));
		assertEquals(7.0, prices.get(b001.getId()), "10.000 minus 30%");
		assertEquals(17.5, prices.get(b002.getId()), "its selling list price 25.000 minus 30%");
		assertEquals(2.333, prices.get(b003.getId()), "3.333 x 0.7 = 2.3331, to the millime");
	}

	@Test
	@DisplayName("Base supply prices: set, changed, deleted with null; all or none; the page lists products and packs only")
	void basePrices() {
		ho.item("TAX_STAMP", 0.1, null);
		ho.item("S1", 5.0, null).setType(ItemType.SERVICE);
		long version = catalogueVersion();

		supply.putPrices(Arrays.asList(line("B001", 6.0), line("B002", 12.0)));
		supply.putPrices(Collections.singletonList(line("B001", 6.5)));
		assertEquals(2, ho.supplyPrices.size());
		IllegalArgumentException bad = assertThrows(IllegalArgumentException.class,
				() -> supply.putPrices(Arrays.asList(line("B002", 1.0), line("B009", 2.0))));
		assertEquals("Unknown item code B009.", bad.getMessage());
		assertThrows(IllegalArgumentException.class, () -> supply.putPrices(Collections.singletonList(line("B002", -1.0))));
		supply.putPrices(Collections.singletonList(line("B002", null)));

		@SuppressWarnings("unchecked")
		List<SupplyPriceDTO> page = (List<SupplyPriceDTO>) supply.page(null, 0, 20).get("content");
		assertEquals(Arrays.asList("B001", "B002", "B003"),
				page.stream().map(SupplyPriceDTO::getItemCode).collect(Collectors.toList()), "no tax stamp, no service");
		assertEquals(6.5, page.get(0).getSupplyPrice());
		assertEquals(10.0, page.get(0).getSellingPrice());
		assertNull(page.get(1).getSupplyPrice(), "deleted, and the bad line above wrote nothing");
		assertEquals(version, catalogueVersion(), "a supply price is never sent to a store");
	}

	private static SupplyPriceDTO line(String itemCode, Double price) {
		SupplyPriceDTO line = new SupplyPriceDTO();
		line.setItemCode(itemCode);
		line.setSupplyPrice(price);
		return line;
	}

	@Test
	@DisplayName("Price list kinds: created SUPPLY, listed by kind, kind final; a supply line records no change; assignment by kind")
	void listKinds() throws Exception {
		PriceListDTO input = new PriceListDTO();
		input.setCode("hammamet");
		input.setName("Hammamet franchise");
		input.setKind("supply");
		PriceListDTO created = lists.create(input);
		assertEquals("SUPPLY", created.getKind());
		assertEquals(Arrays.asList("FRANCHISE", "HAMMAMET"),
				lists.findAll("SUPPLY").stream().map(PriceListDTO::getCode).collect(Collectors.toList()));
		assertEquals(Collections.singletonList("TOURIST"),
				lists.findAll("selling").stream().map(PriceListDTO::getCode).collect(Collectors.toList()));
		assertEquals(3, lists.findAll().size());
		assertThrows(IllegalArgumentException.class, () -> lists.findAll("OTHER"));
		PriceListDTO change = new PriceListDTO();
		change.setKind("SELLING");
		assertEquals("The kind of a price list cannot be changed after creation.",
				assertThrows(IllegalArgumentException.class, () -> lists.update(created.getId(), change)).getMessage());

		b.setSellingPriceListId(tourist.getId());
		long version = catalogueVersion();
		PriceListLineDTO supplyLine = new PriceListLineDTO();
		supplyLine.setItemCode("B001");
		supplyLine.setPrice(4.0);
		lists.putLines(franchise.getId(), Collections.singletonList(supplyLine));
		assertEquals(version, catalogueVersion(), "a supply list line goes to no store");

		assertEquals("The price list FRANCHISE is a supply price list, not a selling price list.",
				assertThrows(IllegalArgumentException.class, () -> stores.setSellingPriceList(b.getId(), franchise.getId()))
						.getMessage());
		assertEquals("The price list TOURIST is a selling price list, not a supply price list.",
				assertThrows(IllegalArgumentException.class, () -> stores.setSupplyPriceList(b.getId(), tourist.getId()))
						.getMessage());
		stores.setSupplyPriceList(b.getId(), franchise.getId());
		assertEquals(franchise.getId(), b.getSupplyPriceListId());
		assertEquals(1L, lists.findById(franchise.getId()).get().getStoreCount());
		assertTrue(assertThrows(IllegalStateException.class, () -> lists.delete(franchise.getId())).getMessage()
				.startsWith("This price list is the supply price list of 1 store(s)"));
		stores.setSupplyPriceList(b.getId(), null);
		assertNull(b.getSupplyPriceListId());
		assertTrue(lists.delete(franchise.getId()));
	}

	@Test
	@DisplayName("Store invoicing settings: set on create and update, kept when absent, blanks cleared, limits checked")
	void storeSettings() throws Exception {
		Store input = new Store();
		input.setCode("H1");
		input.setName("Hammamet");
		input.setDeliveriesInvoiced(true);
		input.setBillingLegalName("  Happy Hammamet SARL ");
		input.setBillingTaxNumber("1234567/A/M/000");
		input.setSupplyPriceMode(SupplyPriceMode.PERCENT_OFF);
		input.setSupplyDiscountPercent(30.0);
		input.setInvoiceRhythm(InvoiceRhythm.GROUPED);
		input.setSupplyPriceListId(franchise.getId());
		Store h1 = stores.create(input).getStore();
		assertTrue(h1.getDeliveriesInvoiced());
		assertEquals("Happy Hammamet SARL", h1.getBillingLegalName());
		assertEquals(SupplyPriceMode.PERCENT_OFF, h1.getSupplyPriceMode());
		assertEquals(30.0, h1.getSupplyDiscountPercent());
		assertEquals(InvoiceRhythm.GROUPED, h1.getInvoiceRhythm());
		assertEquals(franchise.getId(), h1.getSupplyPriceListId());

		Store rename = new Store();
		rename.setName("Hammamet centre");
		rename.setBillingAddress(" ");
		stores.update(h1.getId(), rename);
		assertTrue(h1.getDeliveriesInvoiced(), "kept when absent");
		assertEquals("Happy Hammamet SARL", h1.getBillingLegalName());
		assertNull(h1.getBillingAddress(), "blank: cleared");

		Store badPercent = new Store();
		badPercent.setSupplyDiscountPercent(120.0);
		assertEquals("The supply discount must be from 0 to 100 %.",
				assertThrows(IllegalArgumentException.class, () -> stores.update(h1.getId(), badPercent)).getMessage());
		Store longName = new Store();
		longName.setBillingLegalName(String.join("", Collections.nCopies(201, "x")));
		assertThrows(IllegalArgumentException.class, () -> stores.update(h1.getId(), longName));
		assertEquals(30.0, h1.getSupplyDiscountPercent());
		Store unknownList = new Store();
		unknownList.setCode("H2");
		unknownList.setName("H2");
		unknownList.setSupplyPriceListId(tourist.getId());
		assertThrows(IllegalArgumentException.class, () -> stores.create(unknownList), "a selling list is refused");
	}

	private static String sorted(Map<Long, Double> prices) {
		return new java.util.TreeMap<>(prices).toString();
	}
}
