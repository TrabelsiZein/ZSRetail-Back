package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.dto.PriceListDTO;
import com.digithink.zsretail.headoffice.dto.PriceListLineDTO;
import com.digithink.zsretail.headoffice.model.HoPriceList;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Head office plan, task 6.4: price lists. A line created, changed or deleted reaches the stores on that list only; a
 * store's list change sends the items of the old and the new list to that store only; a list used by a store cannot be
 * deactivated or deleted; lines are checked all or none. In-memory tables, no Spring context.
 */
class HoPriceListServiceTest {

	private InMemoryCatalogue ho;
	private CopiesDownFeed feed;
	private HoPriceListService lists;

	private Item b001;
	private Item b002;
	private Store a;
	private Store b;
	private Store c;
	private HoPriceList tourist;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		InMemoryDownTables down = new InMemoryDownTables();
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		HoCatalogueService catalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(),
				ho.itemRepository(), ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(),
				() -> feedRef[0], TransactionOperations.withoutTransaction());
		feed = down.feed(Collections.singletonList(catalogue));
		feedRef[0] = feed;
		lists = new HoPriceListService(ho.priceListRepository(), ho.priceLineRepository(), ho.storeRepository(),
				ho.itemRepository(), () -> catalogue);

		ItemSubFamily sf1 = ho.subFamily("SF1", ho.family("F1"));
		b001 = ho.item("B001", 10.0, sf1);
		b002 = ho.item("B002", 20.0, sf1);
		a = ho.store("A");
		b = ho.store("B");
		c = ho.store("C");
		tourist = ho.priceList("TOURIST");
		b.setSellingPriceListId(tourist.getId());
		c.setSellingPriceListId(tourist.getId());
		feed.initialise();
	}

	private String cursor(Store store) {
		return feed.pull(store, "CATALOGUE", "", 500).getCursor();
	}

	/** The item codes changed for the store since the cursor, with their price. */
	private List<String> changes(Store store, String cursor) {
		CopiesDownAnswerDTO answer = feed.pull(store, "CATALOGUE", cursor, 500);
		List<String> result = new ArrayList<>();
		for (JsonNode record : answer.getRecords()) {
			result.add(record.get("itemCode").asText() + "=" + record.get("unitPrice").asDouble());
		}
		return result;
	}

	private static PriceListLineDTO line(String itemCode, Double price) {
		PriceListLineDTO line = new PriceListLineDTO();
		line.setItemCode(itemCode);
		line.setPrice(price);
		return line;
	}

	@Test
	@DisplayName("A line created, changed or deleted reaches the stores on the list only, with their price")
	void lineChangeReachesTheListOnly() {
		String ca = cursor(a);
		String cb = cursor(b);
		String cc = cursor(c);
		lists.putLines(tourist.getId(), Collections.singletonList(line("B001", 11.0)));
		assertEquals(Collections.emptyList(), changes(a, ca));
		assertEquals(Collections.singletonList("B001=11.0"), changes(b, cb));
		assertEquals(Collections.singletonList("B001=11.0"), changes(c, cc));

		ca = cursor(a);
		cb = cursor(b);
		lists.putLines(tourist.getId(), Collections.singletonList(line("B001", 11.0)));
		assertEquals(Collections.emptyList(), changes(b, cb), "same price: nothing sent");
		lists.putLines(tourist.getId(), Collections.singletonList(line("B001", 11.5)));
		assertEquals(Collections.singletonList("B001=11.5"), changes(b, cb));
		assertEquals(Collections.emptyList(), changes(a, ca));

		cb = cursor(b);
		Long lineId = ho.priceLines.values().iterator().next().getId();
		assertTrue(lists.deleteLine(tourist.getId(), lineId));
		assertEquals(Collections.singletonList("B001=10.0"), changes(b, cb), "back to the base price");
		assertEquals(Collections.emptyList(), changes(a, ca));
		assertFalse(lists.deleteLine(tourist.getId(), lineId));
	}

	@Test
	@DisplayName("A list without any store: a line sends nothing")
	void unusedList() {
		HoPriceList airport = ho.priceList("AIRPORT");
		String ca = cursor(a);
		String cb = cursor(b);
		lists.putLines(airport.getId(), Collections.singletonList(line("B002", 25.0)));
		assertEquals(Collections.emptyList(), changes(a, ca));
		assertEquals(Collections.emptyList(), changes(b, cb));
	}

	@Test
	@DisplayName("A store's list change sends the items of the old and the new list to that store only")
	void storeListChange() {
		lists.putLines(tourist.getId(), Collections.singletonList(line("B001", 11.0)));
		HoPriceList airport = ho.priceList("AIRPORT");
		lists.putLines(airport.getId(), Collections.singletonList(line("B002", 25.0)));
		String cb = cursor(b);
		String cc = cursor(c);
		String ca = cursor(a);

		b.setSellingPriceListId(airport.getId()); // StoreService saves the row first, then calls storeListChanged
		lists.storeListChanged(b.getId(), tourist.getId(), airport.getId());
		assertEquals(Arrays.asList("B001=10.0", "B002=25.0"), changes(b, cb));
		assertEquals(Collections.emptyList(), changes(c, cc));
		assertEquals(Collections.emptyList(), changes(a, ca));

		cb = cursor(b);
		b.setSellingPriceListId(null);
		lists.storeListChanged(b.getId(), airport.getId(), null);
		assertEquals(Collections.singletonList("B002=20.0"), changes(b, cb));
	}

	@Test
	@DisplayName("A list used by a store cannot be deactivated or deleted; unused, it is deleted with its lines")
	void usedList() {
		lists.putLines(tourist.getId(), Collections.singletonList(line("B001", 11.0)));
		PriceListDTO off = new PriceListDTO();
		off.setActive(false);
		IllegalStateException used = assertThrows(IllegalStateException.class, () -> lists.update(tourist.getId(), off));
		assertTrue(used.getMessage().contains("2 store(s)"));
		assertThrows(IllegalStateException.class, () -> lists.delete(tourist.getId()));

		b.setSellingPriceListId(null);
		c.setSellingPriceListId(null);
		assertFalse(lists.update(tourist.getId(), off).get().getActive());
		assertThrows(IllegalArgumentException.class, () -> lists.checkAssignable(tourist.getId()), "inactive");
		assertTrue(lists.delete(tourist.getId()));
		assertTrue(ho.priceLines.isEmpty());
		assertFalse(lists.delete(tourist.getId()));
		assertThrows(IllegalArgumentException.class, () -> lists.checkAssignable(999L), "unknown");
	}

	@Test
	@DisplayName("Create and update rules: code required, uppercase, unique, final; name required")
	void listRules() {
		PriceListDTO input = new PriceListDTO();
		input.setCode(" airport ");
		input.setName("Airport");
		PriceListDTO created = lists.create(input);
		assertEquals("AIRPORT", created.getCode());
		assertTrue(created.getActive());
		assertEquals(Long.valueOf(0), created.getStoreCount());
		assertThrows(IllegalStateException.class, () -> lists.create(input), "exists");
		PriceListDTO noCode = new PriceListDTO();
		noCode.setName("x");
		assertThrows(IllegalArgumentException.class, () -> lists.create(noCode));
		PriceListDTO noName = new PriceListDTO();
		noName.setCode("X");
		assertThrows(IllegalArgumentException.class, () -> lists.create(noName));
		PriceListDTO rename = new PriceListDTO();
		rename.setCode("OTHER");
		assertThrows(IllegalArgumentException.class, () -> lists.update(created.getId(), rename));
		assertEquals(Long.valueOf(2), lists.findById(tourist.getId()).get().getStoreCount());
	}

	@Test
	@DisplayName("Lines all or none: an unknown item or a bad price writes nothing")
	void linesAllOrNone() {
		assertThrows(IllegalArgumentException.class, () -> lists.putLines(tourist.getId(),
				Arrays.asList(line("B001", 11.0), line("NOPE", 5.0))));
		assertThrows(IllegalArgumentException.class, () -> lists.putLines(tourist.getId(),
				Arrays.asList(line("B001", 11.0), line("B002", -1.0))));
		assertThrows(IllegalArgumentException.class,
				() -> lists.putLines(tourist.getId(), Collections.singletonList(line("B001", null))));
		assertTrue(ho.priceLines.isEmpty());
		assertFalse(lists.putLines(999L, Collections.singletonList(line("B001", 1.0))).isPresent());
		List<PriceListLineDTO> written = lists.putLines(tourist.getId(), Collections.singletonList(line("B002", 0.0))).get();
		assertEquals(20.0, written.get(0).getBasePrice());
		assertEquals(0.0, written.get(0).getPrice());
		assertEquals(b002.getId(), written.get(0).getItemId());
	}
	@Test
	@DisplayName("Release 2.2, catalogue from the ERP: no selling list listed, created or given to a store; supply lists as before")
	void sellingListsNotUsedWithTheErpCatalogue() {
		lists.setSellingListsUsed(false);
		PriceListDTO supply = new PriceListDTO();
		supply.setCode("SUP1");
		supply.setName("Supply");
		supply.setKind("SUPPLY");
		lists.create(supply);
		assertEquals(java.util.Arrays.asList("SUP1"), lists.findAll().stream().map(PriceListDTO::getCode)
				.collect(java.util.stream.Collectors.toList()), "TOURIST (selling) not listed, still in the table");
		assertTrue(ho.priceLists.values().stream().anyMatch(l -> "TOURIST".equals(l.getCode())), "existing data kept");
		PriceListDTO selling = new PriceListDTO();
		selling.setCode("SELL2");
		selling.setName("Selling");
		assertEquals(HoPriceListService.SELLING_NOT_USED,
				assertThrows(IllegalArgumentException.class, () -> lists.create(selling)).getMessage());
		assertEquals(HoPriceListService.SELLING_NOT_USED,
				assertThrows(IllegalArgumentException.class, () -> lists.checkAssignable(tourist.getId())).getMessage());
	}

}
