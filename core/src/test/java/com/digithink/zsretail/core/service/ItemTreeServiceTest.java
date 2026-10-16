package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.ItemTreeDTO;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;

/**
 * Item variants (ZEN), step 11b: the "Catégories" and "Sections" pages over stubbed repositories, on the tree of ZEN's
 * sample (VET > HOM > CHEM > MANCHE): the order, the family counts, the section path and its gaps, the menu rules. The
 * count query itself: ItemTreeQueryTest.
 */
class ItemTreeServiceTest {

	private final ItemCategoryRepository categories = mock(ItemCategoryRepository.class);
	private final ItemSectionRepository sections = mock(ItemSectionRepository.class);
	private final ItemFamilyRepository families = mock(ItemFamilyRepository.class);
	private final ItemSubFamilyRepository subFamilies = mock(ItemSubFamilyRepository.class);
	private final ItemTreeService service = new ItemTreeService(categories, sections, families, subFamilies);

	private static ItemCategory category(String code, String name, Integer order, Boolean active) {
		ItemCategory category = new ItemCategory();
		category.setCode(code);
		category.setName(name);
		category.setDisplayOrder(order);
		category.setActive(active);
		return category;
	}

	private static ItemSection section(String code, String subFamilyCode, Integer order) {
		ItemSection section = new ItemSection();
		section.setCode(code);
		section.setName("Section " + code);
		section.setSubFamilyCode(subFamilyCode);
		section.setDisplayOrder(order);
		return section;
	}

	@Test
	@DisplayName("Catégories: by display order then code, each with its families (0 when none), inactive kept")
	void categories() {
		when(categories.findAll()).thenReturn(Arrays.asList(category("VET", "Vêtements", 2, true),
				category("DIVERS", "Divers", null, false), category("MEUBLES", "Meubles", 1, true),
				category("ACC", "Accessoires", 2, null)));
		when(families.countFamiliesByCategory())
				.thenReturn(Arrays.asList(new Object[] { "VET", 2L }, new Object[] { "MEUBLES", 1L }));
		List<ItemTreeDTO.Category> lines = service.categories();
		assertEquals(Arrays.asList("MEUBLES", "ACC", "VET", "DIVERS"),
				lines.stream().map(ItemTreeDTO.Category::getCode).collect(Collectors.toList()));
		assertEquals(1L, lines.get(0).getFamilies());
		assertEquals(0L, lines.get(1).getFamilies());
		assertEquals(2L, lines.get(2).getFamilies());
		assertFalse(lines.get(3).isActive());
		assertTrue(lines.get(1).isActive(), "active null reads active");
	}

	@Test
	@DisplayName("Families of a catégorie: the repository's list order; a blank code reads nothing")
	void familiesOf() {
		ItemFamily hom = new ItemFamily();
		hom.setCode("HOM");
		hom.setName("Homme");
		hom.setActive(false);
		when(families.findByCategoryCodeOrderByDisplayOrderAscCodeAsc("VET")).thenReturn(Collections.singletonList(hom));
		List<ItemTreeDTO.Family> lines = service.familiesOf(" VET ");
		assertEquals(1, lines.size());
		assertEquals("Homme", lines.get(0).getName());
		assertFalse(lines.get(0).isActive());
		assertTrue(service.familiesOf("  ").isEmpty());
		verify(families, never()).findByCategoryCodeOrderByDisplayOrderAscCodeAsc("");
	}

	@Test
	@DisplayName("Sections: by sous-famille, order, code, with the path catégorie > famille > sous-famille and its gaps")
	void sections() {
		when(sections.findAll()).thenReturn(Arrays.asList(section("POCHE", "CHEM", 2), section("MANCHE", "CHEM", 1),
				section("PIED", "TABLE", null), section("LOST", "GONE", null), section("NONE", null, null)));
		ItemFamily hom = new ItemFamily();
		hom.setCode("HOM");
		hom.setName("Homme");
		hom.setCategoryCode("VET");
		ItemSubFamily chem = new ItemSubFamily();
		chem.setCode("CHEM");
		chem.setName("Chemises");
		chem.setItemFamily(hom);
		ItemSubFamily table = new ItemSubFamily();
		table.setCode("TABLE");
		table.setName("Tables"); // no family
		when(subFamilies.findByCodeIn(anyCollection())).thenReturn(Arrays.asList(chem, table));
		when(categories.findByCodeIn(anyCollection())).thenReturn(Collections.singletonList(category("VET", "Vêtements", 1, true)));

		List<ItemTreeDTO.Section> lines = service.sections();
		assertEquals(Arrays.asList("MANCHE", "POCHE", "LOST", "PIED", "NONE"),
				lines.stream().map(ItemTreeDTO.Section::getCode).collect(Collectors.toList()));
		ItemTreeDTO.Section manche = lines.get(0);
		assertEquals("Chemises", manche.getSubFamilyName());
		assertEquals("HOM", manche.getFamilyCode());
		assertEquals("Homme", manche.getFamilyName());
		assertEquals("VET", manche.getCategoryCode());
		assertEquals("Vêtements", manche.getCategoryName());
		ItemTreeDTO.Section lost = lines.get(2);
		assertEquals("GONE", lost.getSubFamilyCode());
		assertNull(lost.getSubFamilyName(), "no sous-famille row of that code");
		ItemTreeDTO.Section pied = lines.get(3);
		assertEquals("Tables", pied.getSubFamilyName());
		assertNull(pied.getFamilyCode(), "a sous-famille without famille");
		assertNull(lines.get(4).getSubFamilyCode());
	}

	@Test
	@DisplayName("No section, no catégorie: no lookups; the menu rules are the exists queries")
	void emptyAndMenuRules() {
		when(sections.findAll()).thenReturn(Collections.emptyList());
		assertTrue(service.sections().isEmpty());
		verify(subFamilies, never()).findByCodeIn(any());
		verify(categories, never()).findByCodeIn(any());

		when(categories.existsByCodeIsNotNull()).thenReturn(false);
		when(sections.existsByCodeIsNotNull()).thenReturn(true);
		assertFalse(service.hasCategories());
		assertTrue(service.hasSections());
	}
}
