package com.digithink.zsretail.core.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.PosLevelDTO;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.repository.ItemCategoryRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSectionRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;

/**
 * 3.1.0 (ZEN): the till's catégories and sections (PosCatalogueService) over stubs, on ZEN's tree (VET &gt; HOM &gt;
 * CHEM &gt; MANCHE, the local TEST-FAM &gt; TEST-SF &gt; TEST-SEC): the lists (active, the empty ones hidden with the
 * family / sub-family settings), the fall-through familles, the search's two groups and the paths of every hit; with
 * neither level, empty lists and the search of before.
 */
class PosCatalogueLevelsTest {

	private final List<ItemCategory> categories = new ArrayList<>();
	private final List<ItemFamily> families = new ArrayList<>();
	private final List<ItemSubFamily> subFamilies = new ArrayList<>();
	private final List<ItemSection> sections = new ArrayList<>();
	/** countPosGridItems rows: {sub-family's family, item's family, sub-family, count}. */
	private final List<Object[]> gridRows = new ArrayList<>();
	/** countPosSectionItems rows: {sub-family, section code, count}. */
	private final List<Object[]> sectionRows = new ArrayList<>();
	/** countPosCategoryItems rows: {catégorie code, count}. */
	private final List<Object[]> categoryRows = new ArrayList<>();
	private final Map<String, String> settings = new HashMap<>();
	private final List<String> calls = new ArrayList<>();
	private boolean withCategories = true;
	private boolean withSections = true;
	private PosCatalogueService service;

	@BeforeEach
	void setUp() {
		service = new PosCatalogueService();
		inject(service, "itemRepository", proxy(ItemRepository.class, (method, args) -> {
			calls.add(method);
			switch (method) {
				case "countPosGridItems": return gridRows;
				case "countPosSectionItems": return sectionRows;
				case "countPosCategoryItems": return categoryRows;
				default: return UNHANDLED;
			}
		}));
		inject(service, "itemFamilyRepository", proxy(ItemFamilyRepository.class, (method, args) -> {
			calls.add(method);
			switch (method) {
				case "findAll": return args.length == 0 ? families : UNHANDLED;
				case "findByCategoryCodeOrderByDisplayOrderAscCodeAsc":
					return families.stream().filter(f -> args[0].equals(f.getCategoryCode())).collect(Collectors.toList());
				default: return UNHANDLED;
			}
		}));
		inject(service, "itemSubFamilyRepository", proxy(ItemSubFamilyRepository.class, (method, args) -> {
			calls.add(method);
			switch (method) {
				case "findAll": return args.length == 0 ? subFamilies : UNHANDLED;
				case "findById": return subFamilies.stream().filter(s -> s.getId().equals(args[0])).findFirst();
				case "findByCodeIn":
					return subFamilies.stream().filter(s -> ((Collection<?>) args[0]).contains(s.getCode()))
							.collect(Collectors.toList());
				default: return UNHANDLED;
			}
		}));
		inject(service, "itemCategoryRepository", proxy(ItemCategoryRepository.class, (method, args) -> {
			calls.add(method);
			switch (method) {
				case "findAll": return args.length == 0 ? categories : UNHANDLED;
				case "findAllCodes": return categories.stream().map(ItemCategory::getCode).collect(Collectors.toList());
				default: return UNHANDLED;
			}
		}));
		inject(service, "itemSectionRepository", proxy(ItemSectionRepository.class, (method, args) -> {
			calls.add(method);
			switch (method) {
				case "findAll": return args.length == 0 ? sections : UNHANDLED;
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
		inject(service, "generalSetupService", new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return settings.get(code);
			}
		});
		catalogue();
	}

	/**
	 * Catégories VET (Homme with items, Femme empty), MEUBLES (Bureau, empty), DIVERS (no famille, items falling through),
	 * KIDS (its only family with items inactive), OLD (inactive); familles without catégorie TEST-FAM (items) and LOST (a
	 * code of no catégorie, empty); ARCH of the inactive OLD. Sections of CHEM: MANCHE (items), COURT (empty), SANS
	 * (inactive, items); VIDE of PANT (empty); TEST-SEC of TEST-SF (items); ORPH of no sub-family.
	 */
	private void catalogue() {
		category(1, "VET", "Vêtements", 10, true);
		category(2, "MEUBLES", "Meubles", 5, true);
		category(3, "DIVERS", "Divers", 20, true);
		category(4, "KIDS", "Enfants", 30, true);
		category(5, "OLD", "Ancienne", 1, false);
		ItemFamily hom = family(11, "HOM", "Homme", "VET", true);
		family(12, "FEM", "Femme", "VET", true);
		ItemFamily enf = family(13, "ENF", "Enfant", "KIDS", false);
		family(14, "BUR", "Bureau", "MEUBLES", true);
		ItemFamily testFam = family(15, "TEST-FAM", "TEST Famille", null, true);
		family(16, "LOST", "Perdue", "NOPE", true);
		ItemFamily arch = family(17, "ARCH", "Archive", "OLD", true);
		ItemSubFamily chem = subFamily(21, "CHEM", "Chemise", hom, true);
		ItemSubFamily pant = subFamily(22, "PANT", "Pantalon", hom, true);
		ItemSubFamily testSf = subFamily(23, "TEST-SF", "TEST Sous-famille", testFam, true);
		subFamily(24, "CLOSED", "Chemise fermée", hom, false);
		section(31, "MANCHE", "Avec manche", "CHEM", 2, true);
		section(32, "COURT", "Manche courte", "CHEM", 1, true);
		section(33, "SANS", "Sans manche", "CHEM", 0, false);
		section(34, "TEST-SEC", "TEST Section", "TEST-SF", 0, true);
		section(35, "ORPH", "Section orpheline", "NOPE", 0, true);
		section(36, "VIDE", "Manche vide", "PANT", 0, true);
		gridRows.add(new Object[] { hom.getId(), hom.getId(), chem.getId(), 3L });
		gridRows.add(new Object[] { hom.getId(), hom.getId(), pant.getId(), 1L });
		gridRows.add(new Object[] { testFam.getId(), testFam.getId(), testSf.getId(), 2L });
		gridRows.add(new Object[] { null, enf.getId(), null, 1L });
		gridRows.add(new Object[] { null, arch.getId(), null, 1L });
		sectionRows.add(new Object[] { chem.getId(), "MANCHE", 2L });
		sectionRows.add(new Object[] { chem.getId(), "SANS", 1L });
		sectionRows.add(new Object[] { chem.getId(), " ", 1L });
		sectionRows.add(new Object[] { testSf.getId(), "TEST-SEC", 2L });
		categoryRows.add(new Object[] { "DIVERS", 2L });
	}

	private ItemCategory category(long id, String code, String name, int order, boolean active) {
		ItemCategory category = new ItemCategory();
		category.setId(id);
		category.setCode(code);
		category.setName(name);
		category.setDisplayOrder(order);
		category.setActive(active);
		categories.add(category);
		return category;
	}

	private ItemFamily family(long id, String code, String name, String categoryCode, boolean active) {
		ItemFamily family = new ItemFamily();
		family.setId(id);
		family.setCode(code);
		family.setName(name);
		family.setCategoryCode(categoryCode);
		family.setActive(active);
		families.add(family);
		return family;
	}

	private ItemSubFamily subFamily(long id, String code, String name, ItemFamily family, boolean active) {
		ItemSubFamily subFamily = new ItemSubFamily();
		subFamily.setId(id);
		subFamily.setCode(code);
		subFamily.setName(name);
		subFamily.setItemFamily(family);
		subFamily.setActive(active);
		subFamilies.add(subFamily);
		return subFamily;
	}

	private ItemSection section(long id, String code, String name, String subFamilyCode, int order, boolean active) {
		ItemSection section = new ItemSection();
		section.setId(id);
		section.setCode(code);
		section.setName(name);
		section.setSubFamilyCode(subFamilyCode);
		section.setDisplayOrder(order);
		section.setActive(active);
		sections.add(section);
		return section;
	}

	private static List<String> codes(List<?> entries) {
		return entries.stream().map(entry -> {
			if (entry instanceof ItemCategory) {
				return ((ItemCategory) entry).getCode();
			}
			if (entry instanceof ItemFamily) {
				return ((ItemFamily) entry).getCode();
			}
			if (entry instanceof ItemSubFamily) {
				return ((ItemSubFamily) entry).getCode();
			}
			return ((ItemSection) entry).getCode();
		}).collect(Collectors.toList());
	}

	/** A path as "level:code" steps. */
	private static List<String> path(PosCatalogueService.Found found, Object hit) {
		return found.pathOf(hit).stream().map(step -> step.getLevel() + ":" + step.getCode()).collect(Collectors.toList());
	}

	// ─── Lists ───────────────────────────────────────────────

	@Test
	@DisplayName("Catégories: the active ones by display order; with \"hide empty families\" on, those leading to an item (a family with items, or items without famille)")
	void categoriesList() {
		assertEquals(Arrays.asList("MEUBLES", "VET", "DIVERS", "KIDS"), codes(service.categories()));
		settings.put(PosCatalogueService.HIDE_EMPTY_FAMILIES, "true");
		assertEquals(Arrays.asList("VET", "DIVERS"), codes(service.categories()),
				"MEUBLES empty, KIDS only through an inactive family");
		settings.put(PosCatalogueService.HIDE_EMPTY_SUB_FAMILIES, "true");
		settings.remove(PosCatalogueService.HIDE_EMPTY_FAMILIES);
		assertEquals(4, service.categories().size(), "the sub-family setting does not hide catégories");
	}

	@Test
	@DisplayName("Familles of a catégorie: the active ones of that code (trimmed), as the family list; blank or unknown: none")
	void familiesOfCategory() {
		assertEquals(Arrays.asList("HOM", "FEM"), codes(service.familiesOfCategory("VET")));
		assertEquals(Arrays.asList("HOM", "FEM"), codes(service.familiesOfCategory(" VET ")));
		assertEquals(Arrays.asList(), codes(service.familiesOfCategory("KIDS")), "its only family is inactive");
		assertTrue(service.familiesOfCategory(" ").isEmpty());
		assertTrue(service.familiesOfCategory(null).isEmpty());
		assertTrue(service.familiesOfCategory("ZZZ").isEmpty());
		settings.put(PosCatalogueService.HIDE_EMPTY_FAMILIES, "true");
		assertEquals(Arrays.asList("HOM"), codes(service.familiesOfCategory("VET")));
	}

	@Test
	@DisplayName("Familles without catégorie: no code, or a code of no catégorie; one of an inactive catégorie stays hidden")
	void familiesWithoutCategory() {
		assertEquals(Arrays.asList("TEST-FAM", "LOST"), codes(service.familiesWithoutCategory()));
		settings.put(PosCatalogueService.HIDE_EMPTY_FAMILIES, "true");
		assertEquals(Arrays.asList("TEST-FAM"), codes(service.familiesWithoutCategory()));
	}

	@Test
	@DisplayName("Sections of a sous-famille: the active ones by display order; with \"hide empty sub-families\" on, those holding an item; unknown sous-famille refused")
	void sectionsList() {
		assertEquals(Arrays.asList("COURT", "MANCHE"), codes(service.sections(21L)));
		assertEquals(Arrays.asList("VIDE"), codes(service.sections(22L)));
		assertEquals(Arrays.asList("TEST-SEC"), codes(service.sections(23L)));
		settings.put(PosCatalogueService.HIDE_EMPTY_SUB_FAMILIES, "true");
		assertEquals(Arrays.asList("MANCHE"), codes(service.sections(21L)));
		assertTrue(service.sections(22L).isEmpty());
		assertEquals("Item sub-family not found: 99",
				assertThrows(IllegalArgumentException.class, () -> service.sections(99L)).getMessage());
	}

	@Test
	@DisplayName("Without catégories or sections (EMTOP, Happyness): every new list is empty, no error, nothing read")
	void levelsOff() {
		withCategories = false;
		withSections = false;
		settings.put(PosCatalogueService.HIDE_EMPTY_FAMILIES, "true");
		settings.put(PosCatalogueService.HIDE_EMPTY_SUB_FAMILIES, "true");
		assertTrue(service.categories().isEmpty());
		assertTrue(service.familiesOfCategory("VET").isEmpty());
		assertTrue(service.familiesWithoutCategory().isEmpty());
		assertTrue(service.sections(21L).isEmpty());
		assertTrue(service.sections(99L).isEmpty(), "no error for an unknown sous-famille either");
		assertEquals(Arrays.asList(), calls);
	}

	// ─── Search ───────────────────────────────────────────────

	@Test
	@DisplayName("Search, sections: active, in an active sous-famille of an active famille, holding an item; the path catégorie › famille › sous-famille › section")
	void searchSections() {
		PosCatalogueService.Found found = service.search("manche");
		assertEquals(Arrays.asList("MANCHE"), codes(found.getSections()),
				"COURT and VIDE empty, SANS inactive, ORPH of no sous-famille");
		ItemSection manche = found.getSections().get(0);
		assertEquals(Arrays.asList("category:VET", "family:HOM", "subFamily:CHEM", "section:MANCHE"), path(found, manche));
		List<PosLevelDTO.Step> steps = found.pathOf(manche);
		assertEquals(Arrays.asList("Vêtements", "Homme", "Chemise", "Avec manche"),
				steps.stream().map(PosLevelDTO.Step::getName).collect(Collectors.toList()));
		assertEquals(Arrays.asList(1L, 11L, 21L, 31L),
				steps.stream().map(PosLevelDTO.Step::getId).collect(Collectors.toList()));
		assertEquals(Arrays.asList(), found.getCategories(), "the catégories group is there, empty");
	}

	@Test
	@DisplayName("Search, catégories: active and leading to an item (through a famille or items without famille); path: the catégorie")
	void searchCategories() {
		PosCatalogueService.Found divers = service.search("divers");
		assertEquals(Arrays.asList("DIVERS"), codes(divers.getCategories()));
		assertEquals(Arrays.asList("category:DIVERS"), path(divers, divers.getCategories().get(0)));
		assertEquals(Arrays.asList("VET"), codes(service.search("vetements").getCategories()), "accents ignored");
		assertTrue(service.search("meubles").getCategories().isEmpty(), "no item under MEUBLES");
		assertTrue(service.search("enfants").getCategories().isEmpty(), "KIDS only through an inactive famille");
		assertTrue(service.search("ancienne").getCategories().isEmpty(), "OLD inactive");
	}

	@Test
	@DisplayName("Search, familles and sous-familles: the same hits as before, each with its path (\"Homme › Chemise\"); no catégorie: the path starts at the famille")
	void searchPaths() {
		PosCatalogueService.Found chemise = service.search("chemise");
		assertEquals(Arrays.asList("CHEM"), codes(chemise.getSubFamilies()), "CLOSED inactive");
		assertEquals(Arrays.asList("category:VET", "family:HOM", "subFamily:CHEM"),
				path(chemise, chemise.getSubFamilies().get(0)));
		PosCatalogueService.Found homme = service.search("homme");
		assertEquals(Arrays.asList("category:VET", "family:HOM"), path(homme, homme.getFamilies().get(0)));
		PosCatalogueService.Found test = service.search("test");
		assertEquals(Arrays.asList("TEST-FAM"), codes(test.getFamilies()));
		assertEquals(Arrays.asList("family:TEST-FAM"), path(test, test.getFamilies().get(0)));
		assertEquals(Arrays.asList("family:TEST-FAM", "subFamily:TEST-SF"), path(test, test.getSubFamilies().get(0)));
		assertEquals(Arrays.asList("family:TEST-FAM", "subFamily:TEST-SF", "section:TEST-SEC"),
				path(test, test.getSections().get(0)));
	}

	@Test
	@DisplayName("Search with neither level: no catégorie or section group, no path, the catégorie and section tables never read")
	void searchLevelsOff() {
		withCategories = false;
		withSections = false;
		PosCatalogueService.Found found = service.search("chemise");
		assertEquals(Arrays.asList("CHEM"), codes(found.getSubFamilies()));
		assertNull(found.getCategories());
		assertNull(found.getSections());
		assertFalse(found.hasPaths());
		assertNull(found.pathOf(found.getSubFamilies().get(0)));
		assertEquals(Arrays.asList("findAll", "findAll", "countPosGridItems"), calls,
				"families, sub-families, the grid's count: the reads of before");
	}

	@Test
	@DisplayName("Search with sections only: the sections group, no catégorie group, paths without a catégorie")
	void searchSectionsOnly() {
		withCategories = false;
		PosCatalogueService.Found found = service.search("manche");
		assertNull(found.getCategories());
		assertEquals(Arrays.asList("family:HOM", "subFamily:CHEM", "section:MANCHE"),
				path(found, found.getSections().get(0)));
	}

	@Test
	@DisplayName("Search with the levels, no name matching: the empty groups, nothing counted")
	void searchNothingMatched() {
		PosCatalogueService.Found found = service.search("zzqx");
		assertTrue(found.getCategories().isEmpty());
		assertTrue(found.getSections().isEmpty());
		assertTrue(found.hasPaths());
		assertFalse(calls.contains("countPosGridItems"));
		assertFalse(calls.contains("countPosSectionItems"));
	}

	@Test
	@DisplayName("Search: at most 10 sections")
	void searchLimit() {
		for (long id = 100; id < 112; id++) {
			section(id, "L" + id, "Ligne " + id, "CHEM", 0, true);
			sectionRows.add(new Object[] { 21L, "L" + id, 1L });
		}
		assertEquals(PosCatalogueService.SEARCH_LIMIT, service.search("ligne").getSections().size());
	}

	@Test
	@DisplayName("A sous-famille found by code keeps its id in the path (findById not needed)")
	void searchOneSubFamilyRead() {
		service.search("manche");
		assertEquals(1, calls.stream().filter("findByCodeIn"::equals).count());
		assertEquals(Optional.empty(), calls.stream().filter("findById"::equals).findFirst());
	}
}
