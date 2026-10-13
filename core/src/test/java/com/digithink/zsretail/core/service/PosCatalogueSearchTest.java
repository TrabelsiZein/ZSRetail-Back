package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;

/**
 * 3.1.0: the families and sub-families of the till's search (PosCatalogueService.search) over stubs: every word in the
 * name, accents ignored; only active tiles holding an item the grid lists (whatever the "hide empty" settings); names
 * starting with the first word first; nothing counted when no name matched.
 */
class PosCatalogueSearchTest {

	private final List<ItemFamily> families = new ArrayList<>();
	private final List<ItemSubFamily> subFamilies = new ArrayList<>();
	/** countPosGridItems rows: {sub-family's family, item's family, sub-family, count}. */
	private final List<Object[]> rows = new ArrayList<>();
	private int counted;
	private PosCatalogueService service;

	@BeforeEach
	void setUp() {
		service = new PosCatalogueService();
		inject(service, "itemRepository", proxy(ItemRepository.class, (method, args) -> {
			if ("countPosGridItems".equals(method)) {
				counted++;
				return rows;
			}
			return UNHANDLED;
		}));
		inject(service, "itemFamilyRepository", proxy(ItemFamilyRepository.class,
				(method, args) -> "findAll".equals(method) && args.length == 0 ? families : UNHANDLED));
		inject(service, "itemSubFamilyRepository", proxy(ItemSubFamilyRepository.class,
				(method, args) -> "findAll".equals(method) && args.length == 0 ? subFamilies : UNHANDLED));
	}

	private ItemFamily family(long id, String name, Boolean active) {
		ItemFamily family = new ItemFamily();
		family.setId(id);
		family.setName(name);
		family.setActive(active);
		families.add(family);
		return family;
	}

	private ItemSubFamily subFamily(long id, String name, ItemFamily family, Boolean active) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setId(id);
		subFamily.setName(name);
		subFamily.setItemFamily(family);
		subFamily.setActive(active);
		subFamilies.add(subFamily);
		return subFamily;
	}

	/** A listed item in this sub-family (and its family). */
	private void listed(ItemSubFamily subFamily) {
		rows.add(new Object[] { subFamily.getItemFamily().getId(), subFamily.getItemFamily().getId(), subFamily.getId(), 1L });
	}

	private static List<String> names(List<?> entries) {
		return entries.stream().map(entry -> entry instanceof ItemFamily ? ((ItemFamily) entry).getName()
				: ((ItemSubFamily) entry).getName()).collect(Collectors.toList());
	}

	@Test
	@DisplayName("The Happyness case: \"sham\" finds Shampoing (starts with it) before Soins Shampoing; Après Shampoing (no item) is left out")
	void shampoing() {
		ItemFamily shampoing = family(1, "Shampoing", true);
		ItemFamily apres = family(2, "Après Shampoing", true);
		ItemFamily soins = family(3, "Soins Shampoing", null);
		listed(subFamily(11, "Shampoing", shampoing, true));
		subFamily(21, "Après Shampoing", apres, true); // nothing to sell
		listed(subFamily(31, "Shampoing doux", soins, true));

		PosCatalogueService.Found found = service.search("sham");

		assertEquals(Arrays.asList("Shampoing", "Soins Shampoing"), names(found.getFamilies()));
		assertEquals(Arrays.asList("Shampoing", "Shampoing doux"), names(found.getSubFamilies()));
	}

	@Test
	@DisplayName("Every word, in any order, accents and case ignored: \"creme visage\" finds \"VISAGE - CRÈME\", not \"Crème mains\"")
	void wordsAndAccents() {
		ItemFamily visage = family(1, "VISAGE - CRÈME", true);
		ItemFamily mains = family(2, "Crème mains", true);
		listed(subFamily(11, "Jour", visage, true));
		listed(subFamily(21, "Nuit", mains, true));

		assertEquals(Arrays.asList("VISAGE - CRÈME"), names(service.search("creme visage").getFamilies()));
		assertEquals(Arrays.asList("Crème mains", "VISAGE - CRÈME"), names(service.search("CREME").getFamilies()));
	}

	@Test
	@DisplayName("Inactive families and sub-families, and the sub-families of an inactive family, are left out")
	void inactiveLeftOut() {
		ItemFamily closed = family(1, "Parfum fermé", false);
		ItemFamily open = family(2, "Parfum ouvert", true);
		listed(subFamily(11, "Parfum A", closed, true));
		listed(subFamily(21, "Parfum B", open, false));
		listed(subFamily(22, "Parfum C", open, true));

		PosCatalogueService.Found found = service.search("parfum");

		assertEquals(Arrays.asList("Parfum ouvert"), names(found.getFamilies()));
		assertEquals(Arrays.asList("Parfum C"), names(found.getSubFamilies()));
	}

	@Test
	@DisplayName("Under 2 characters, or no name matching: nothing, and the items are not counted")
	void nothingCounted() {
		listed(subFamily(11, "Teint", family(1, "Teint", true), true));

		assertTrue(service.search("t").getFamilies().isEmpty());
		assertTrue(service.search("zzqx").getFamilies().isEmpty());
		assertEquals(0, counted);
		assertEquals(1, service.search("tei").getFamilies().size());
		assertEquals(1, counted, "one count for both lists");
	}

	@Test
	@DisplayName("At most 10 families and 10 sub-families")
	void limit() {
		for (long id = 1; id <= 12; id++) {
			ItemFamily family = family(id, "Ligne " + id, true);
			listed(subFamily(100 + id, "Ligne sous " + id, family, true));
		}
		PosCatalogueService.Found found = service.search("ligne");
		assertEquals(PosCatalogueService.SEARCH_LIMIT, found.getFamilies().size());
		assertEquals(PosCatalogueService.SEARCH_LIMIT, found.getSubFamilies().size());
	}
}
