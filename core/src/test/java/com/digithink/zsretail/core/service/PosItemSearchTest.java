package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.PosItemSearchRepository;

/**
 * 3.1.0: the till's search over the whole catalogue (ItemService.searchPos) over stubs: a whole barcode or item code
 * first, then the word search's items in its order, only items the grid lists, at most 30 with "more".
 */
class PosItemSearchTest {

	private final Map<Long, Item> items = new HashMap<>();
	private final Map<String, ItemBarcode> barcodes = new HashMap<>();
	/** What the word search answers (ids, in its order), and the words it was asked. */
	private List<Long> wordMatches = new ArrayList<>();
	private final List<List<String>> searchedWords = new ArrayList<>();
	private int searchedLimit;
	private ItemService service;

	@BeforeEach
	void setUp() {
		service = new ItemService();
		inject(service, "itemRepository", proxy(ItemRepository.class, (method, args) -> {
			switch (method) {
				case "findAllById": {
					List<Item> found = new ArrayList<>();
					for (Object id : (Iterable<?>) args[0]) {
						if (items.containsKey(id)) {
							found.add(items.get(id));
						}
					}
					found.sort((a, b) -> Long.compare(b.getId(), a.getId())); // the database's order is not the search's
					return found;
				}
				case "findByItemCode":
					return items.values().stream().filter(item -> item.getItemCode().equalsIgnoreCase((String) args[0]))
							.findFirst();
				default:
					return UNHANDLED;
			}
		}));
		inject(service, "itemBarcodeRepository", proxy(ItemBarcodeRepository.class,
				(method, args) -> "findByBarcode".equals(method) ? Optional.ofNullable(barcodes.get(args[0])) : UNHANDLED));
		inject(service, "posItemSearchRepository", new PosItemSearchRepository(null) {
			@Override
			public List<Long> findIds(List<String> words, int limit) {
				searchedWords.add(words);
				searchedLimit = limit;
				return wordMatches.stream().limit(limit).collect(Collectors.toList());
			}
		});
	}

	private Item item(long id, String code, Double price) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		item.setName("Item " + code);
		item.setUnitPrice(price);
		item.setActive(true);
		item.setShowInPos(true);
		items.put(id, item);
		return item;
	}

	private void barcode(String value, Item item, Boolean active) {
		ItemBarcode barcode = new ItemBarcode();
		barcode.setBarcode(value);
		barcode.setItem(item);
		barcode.setActive(active);
		barcodes.put(value, barcode);
	}

	private static List<Long> ids(ItemService.PosSearch search) {
		return search.getItems().stream().map(Item::getId).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Fewer than 2 characters (spaces aside): nothing, and nothing asked to the database")
	void tooShort() {
		item(1, "A", 5.0);
		wordMatches = Arrays.asList(1L);
		for (String text : new String[] { null, "", " ", "a", "  b  " }) {
			ItemService.PosSearch search = service.searchPos(text);
			assertTrue(search.getItems().isEmpty(), text);
			assertFalse(search.isMore());
		}
		assertTrue(searchedWords.isEmpty());
	}

	@Test
	@DisplayName("The words: split on spaces, once each, at most 6; the items in the search's order (by name), not the database's")
	void wordsAndOrder() {
		item(1, "V1", 5.0);
		item(2, "V2", 5.0);
		item(3, "V3", 5.0);
		wordMatches = Arrays.asList(2L, 3L, 1L);

		ItemService.PosSearch search = service.searchPos("  vanil   1l vanil ");
		assertEquals(Arrays.asList(2L, 3L, 1L), ids(search));
		assertEquals(null, search.getExactId(), "no whole code typed");
		assertEquals(Arrays.asList("vanil", "1l"), searchedWords.get(0));
		assertEquals(ItemService.POS_SEARCH_LIMIT + 1, searchedLimit, "one more than shown, to know there are more");

		service.searchPos("a b c d e f g h");
		assertEquals(Arrays.asList("a", "b", "c", "d", "e", "f"), searchedWords.get(1));
	}

	@Test
	@DisplayName("A whole barcode typed: its item first, once, then the word search's; an inactive barcode is not a match")
	void exactBarcodeFirst() {
		Item scanned = item(1, "P1", 5.0);
		item(2, "P2", 5.0);
		barcode("6192464102681", scanned, true);
		wordMatches = Arrays.asList(2L, 1L);

		ItemService.PosSearch search = service.searchPos("6192464102681");
		assertEquals(Arrays.asList(1L, 2L), ids(search));
		assertEquals(1L, search.getExactId(), "the till adds it on Enter");

		Item old = item(3, "P3", 5.0);
		barcode("6190000000001", old, false);
		wordMatches = new ArrayList<>();
		search = service.searchPos("6190000000001");
		assertTrue(search.getItems().isEmpty());
		assertEquals(null, search.getExactId());
	}

	@Test
	@DisplayName("A whole item code typed (any case): its item first")
	void exactCodeFirst() {
		item(1, "VH52-1L", 5.0);
		item(2, "VH52-1L-REFILL", 5.0);
		wordMatches = Arrays.asList(2L);

		ItemService.PosSearch search = service.searchPos("vh52-1l");
		assertEquals(Arrays.asList(1L, 2L), ids(search));
		assertEquals(1L, search.getExactId());
	}

	@Test
	@DisplayName("An exact match the grid would not list is left out: no price, inactive, hidden from the POS, inactive sub-family")
	void exactMatchFollowsTheGrid() {
		item(1, "NOPRICE", 0.0);
		item(2, "INACTIVE", 5.0).setActive(false);
		item(3, "HIDDEN", 5.0).setShowInPos(false);
		ItemSubFamily closed = new ItemSubFamily();
		closed.setActive(false);
		item(4, "CLOSED", 5.0).setItemSubFamily(closed);
		ItemSubFamily open = new ItemSubFamily();
		open.setActive(true);
		item(5, "OPEN", 5.0).setItemSubFamily(open);

		for (String code : new String[] { "NOPRICE", "INACTIVE", "HIDDEN", "CLOSED" }) {
			assertTrue(service.searchPos(code).getItems().isEmpty(), code);
		}
		assertEquals(Arrays.asList(5L), ids(service.searchPos("OPEN")));
	}

	@Test
	@DisplayName("At most 30 items: 31 found gives 30 and more; an exact match on top of 30 also gives 30 and more; 30 found: not more")
	void limit() {
		LongStream.rangeClosed(1, 32).forEach(id -> item(id, "C" + id, 5.0));
		wordMatches = LongStream.rangeClosed(1, 31).boxed().collect(Collectors.toList());
		ItemService.PosSearch search = service.searchPos("item c");
		assertEquals(30, search.getItems().size());
		assertTrue(search.isMore());

		wordMatches = LongStream.rangeClosed(1, 30).boxed().collect(Collectors.toList());
		search = service.searchPos("item c");
		assertEquals(30, search.getItems().size());
		assertFalse(search.isMore());

		search = service.searchPos("C32"); // the exact code, then the 30 of the word search
		assertEquals(30, search.getItems().size());
		assertEquals(32L, search.getItems().get(0).getId());
		assertTrue(search.isMore());
	}
}
