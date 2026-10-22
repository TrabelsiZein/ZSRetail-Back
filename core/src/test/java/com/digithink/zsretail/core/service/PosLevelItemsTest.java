package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;

/**
 * 3.1.0 (ZEN): the till's items of a section, of a sous-famille without section and of a catégorie without famille
 * (ItemService) over stubs. A section holds the items of its own sous-famille that carry its code; what no section of
 * the sous-famille holds falls through to it, except the items of an inactive section (hidden). Each listed item is
 * reachable once. With neither level: nothing, no error.
 */
class PosLevelItemsTest {

	private final List<Item> items = new ArrayList<>();
	private final List<ItemSubFamily> subFamilies = new ArrayList<>();
	private final List<ItemSection> sections = new ArrayList<>();
	private final List<String> calls = new ArrayList<>();
	private boolean withCategories = true;
	private boolean withSections = true;
	private ItemService service;

	private final ItemFamily testFam = family("TEST-FAM");
	private ItemSubFamily testSf;
	private ItemSubFamily chem;

	@BeforeEach
	void setUp() {
		service = new ItemService();
		inject(service, "itemRepository", proxy(ItemRepository.class, (method, args) -> {
			calls.add(method);
			switch (method) {
				case "findByItemSubFamily":
					return items.stream().filter(i -> i.getItemSubFamily() == args[0]).collect(Collectors.toList());
				case "findByCategoryCodeAndItemFamilyIsNullAndItemSubFamilyIsNull":
					return items.stream().filter(i -> args[0].equals(i.getCategoryCode()) && i.getItemFamily() == null
							&& i.getItemSubFamily() == null).collect(Collectors.toList());
				default: return UNHANDLED;
			}
		}));
		inject(service, "itemSubFamilyRepository", proxy(ItemSubFamilyRepository.class, (method, args) -> {
			calls.add(method);
			switch (method) {
				case "findById": return subFamilies.stream().filter(s -> s.getId().equals(args[0])).findFirst();
				case "findByCode": return subFamilies.stream().filter(s -> s.getCode().equals(args[0])).findFirst();
				default: return UNHANDLED;
			}
		}));
		inject(service, "itemSectionRepository", proxy(ItemSectionRepository.class, (method, args) -> {
			calls.add(method);
			switch (method) {
				case "findById": return sections.stream().filter(s -> s.getId().equals(args[0])).findFirst();
				case "findBySubFamilyCode":
					return sections.stream().filter(s -> args[0].equals(s.getSubFamilyCode())).collect(Collectors.toList());
				default: return UNHANDLED;
			}
		}));
		inject(service, "itemTreeService", new ItemTreeService(null, null, null, null) {
			@Override
			public boolean hasCategories() {
				return withCategories;
			}

			@Override
			public boolean hasSections() {
				return withSections;
			}
		});
		testSf = subFamily(23, "TEST-SF");
		chem = subFamily(21, "CHEM");
		section(34, "TEST-SEC", "TEST-SF", true);
		section(37, "TEST-OFF", "TEST-SF", false);
		section(31, "MANCHE", "CHEM", true);
		section(38, "ORPH", "NOPE", true);
		item("IN-SEC", testFam, testSf, null, "TEST-SEC", 10.0, true);
		item("IN-SEC-TRIMMED", testFam, testSf, null, " TEST-SEC ", 10.0, true);
		item("IN-SEC-NO-PRICE", testFam, testSf, null, "TEST-SEC", 0.0, true);
		item("NO-SEC", testFam, testSf, null, null, 10.0, true);
		item("BLANK-SEC", testFam, testSf, null, "", 10.0, true);
		item("UNKNOWN-SEC", testFam, testSf, null, "ZZZ", 10.0, true);
		item("OTHER-SF-SEC", testFam, testSf, null, "MANCHE", 10.0, true);
		item("INACTIVE-SEC", testFam, testSf, null, "TEST-OFF", 10.0, true);
		item("NO-SEC-HIDDEN", testFam, testSf, null, null, 10.0, false);
		item("CHEM-TEST-SEC", testFam, chem, null, "TEST-SEC", 10.0, true);
		item("CHEM-MANCHE", testFam, chem, null, "MANCHE", 10.0, true);
		// the local ZEN rows: a section code, no famille, no sous-famille
		item("15ZCR104~001", null, null, null, "TEST-SEC", 24.5, true);
		item("CAT-ONLY", null, null, "VET", null, 5.0, true);
		item("CAT-HIDDEN", null, null, "VET", null, 5.0, false);
		item("CAT-WITH-FAMILY", testFam, testSf, "VET", null, 5.0, true);
		calls.clear();
	}

	private static ItemFamily family(String code) {
		ItemFamily family = new ItemFamily();
		family.setId(15L);
		family.setCode(code);
		return family;
	}

	private ItemSubFamily subFamily(long id, String code) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setId(id);
		subFamily.setCode(code);
		subFamily.setItemFamily(testFam);
		subFamilies.add(subFamily);
		return subFamily;
	}

	private void section(long id, String code, String subFamilyCode, boolean active) {
		ItemSection section = new ItemSection();
		section.setId(id);
		section.setCode(code);
		section.setSubFamilyCode(subFamilyCode);
		section.setActive(active);
		sections.add(section);
	}

	private void item(String code, ItemFamily family, ItemSubFamily subFamily, String categoryCode, String sectionCode,
			Double price, boolean showInPos) {
		Item item = new Item();
		item.setItemCode(code);
		item.setItemFamily(family);
		item.setItemSubFamily(subFamily);
		item.setCategoryCode(categoryCode);
		item.setSectionCode(sectionCode);
		item.setUnitPrice(price);
		item.setShowInPos(showInPos);
		items.add(item);
	}

	private static List<String> codes(List<Item> list) {
		return list.stream().map(Item::getItemCode).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Items of a section: those of its own sous-famille carrying its code (trimmed), as the grid lists them; not the same code in another sous-famille, not the ZEN rows without sous-famille")
	void itemsOfSection() {
		assertEquals(Arrays.asList("IN-SEC", "IN-SEC-TRIMMED"), codes(service.findPosItemsOfSection(34L)));
		assertEquals(Arrays.asList("CHEM-MANCHE"), codes(service.findPosItemsOfSection(31L)),
				"OTHER-SF-SEC carries MANCHE but is a TEST-SF item");
		assertTrue(service.findPosItemsOfSection(38L).isEmpty(), "a section of no sous-famille");
		assertEquals("Item section not found: 99",
				assertThrows(IllegalArgumentException.class, () -> service.findPosItemsOfSection(99L)).getMessage());
	}

	@Test
	@DisplayName("Items of a sous-famille without section: no code, blank, a code of none of its sections; the items of its inactive section stay hidden")
	void itemsWithoutSection() {
		assertEquals(Arrays.asList("NO-SEC", "BLANK-SEC", "UNKNOWN-SEC", "OTHER-SF-SEC", "CAT-WITH-FAMILY"),
				codes(service.findPosItemsWithoutSection(23L)));
		assertEquals(Arrays.asList("CHEM-TEST-SEC"), codes(service.findPosItemsWithoutSection(21L)),
				"TEST-SEC is not a section of CHEM");
		assertEquals("Item sub-family not found: 99",
				assertThrows(IllegalArgumentException.class, () -> service.findPosItemsWithoutSection(99L)).getMessage());
	}

	@Test
	@DisplayName("Each listed item of a sous-famille is reached once: in one of its active sections or in its fall-through")
	void reachedOnce() {
		for (ItemSubFamily subFamily : subFamilies) {
			List<String> reached = new ArrayList<>(codes(service.findPosItemsWithoutSection(subFamily.getId())));
			sections.stream().filter(s -> s.getActive() && subFamily.getCode().equals(s.getSubFamilyCode()))
					.forEach(s -> reached.addAll(codes(service.findPosItemsOfSection(s.getId()))));
			List<String> expected = items.stream()
					.filter(i -> i.getItemSubFamily() == subFamily && ItemService.listedInPos(i)
							&& !"TEST-OFF".equals(i.getSectionCode()))
					.map(Item::getItemCode).collect(Collectors.toList());
			assertEquals(expected.stream().sorted().collect(Collectors.toList()),
					reached.stream().sorted().collect(Collectors.toList()), subFamily.getCode());
		}
	}

	@Test
	@DisplayName("Items of a catégorie without famille: its code (trimmed), no famille and no sous-famille, as the grid lists them; blank: none")
	void itemsWithoutFamily() {
		assertEquals(Arrays.asList("CAT-ONLY"), codes(service.findPosItemsWithoutFamily("VET")));
		assertEquals(Arrays.asList("CAT-ONLY"), codes(service.findPosItemsWithoutFamily(" VET ")));
		assertTrue(service.findPosItemsWithoutFamily("DIVERS").isEmpty());
		assertTrue(service.findPosItemsWithoutFamily("").isEmpty());
		assertTrue(service.findPosItemsWithoutFamily(null).isEmpty());
	}

	@Test
	@DisplayName("Without catégories or sections: the three lists are empty, no error for an unknown id, nothing read")
	void levelsOff() {
		withCategories = false;
		withSections = false;
		assertTrue(service.findPosItemsOfSection(34L).isEmpty());
		assertTrue(service.findPosItemsOfSection(99L).isEmpty());
		assertTrue(service.findPosItemsWithoutSection(23L).isEmpty());
		assertTrue(service.findPosItemsWithoutSection(99L).isEmpty());
		assertTrue(service.findPosItemsWithoutFamily("VET").isEmpty());
		assertEquals(Arrays.asList(), calls);
	}
}
