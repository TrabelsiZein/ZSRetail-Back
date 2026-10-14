package com.digithink.zsretail.core.erp.engine;

import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.ARGENTE;
import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.FRANCHISE;
import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.category;
import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.family;
import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.section;
import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.subFamily;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;

/**
 * Item variants (ZEN), step 4: the catégories and the sections of the job SYNC_CATALOGUE, with the real import
 * ({@link CatalogueTestBed}): the tree of the ZEN sample (itemCategories.json) VET (catégorie) > HOM (famille) > CHEM
 * (sous-famille) > MANCHE (section) created then updated; the order of the parts; an ERP without catégories and
 * sections (an empty read) changes nothing and the run is clean; the families and sub-families of such an ERP are
 * summarised as before.
 */
class CatalogueCategoriesTest {

	private CatalogueTestBed bed;
	private ErpItemCategoryDTO vet;
	private ErpItemFamilyDTO hom;
	private ErpItemSectionDTO manche;

	@BeforeEach
	void setUp() {
		bed = new CatalogueTestBed();
		vet = category("VET", "Categorie Vetements", 40000);
		hom = family("HOM", "Famille Homme", "VET");
		manche = section("MANCHE", "Section Avec manche", "CHEM", 70000);
		bed.categories.add(vet);
		bed.families.add(hom);
		bed.subFamilies.add(subFamily("CHEM", "Sous-Famille Chemise", "HOM"));
		bed.sections.add(manche);
		ErpItemDTO shirt = new ErpItemDTO();
		shirt.setExternalId("70061~001");
		shirt.setCode("70061~001");
		shirt.setName("CHEMISE");
		shirt.setUnitPrice(new BigDecimal("50"));
		shirt.setDefaultVAT(19);
		shirt.setActive(Boolean.TRUE);
		shirt.setFamilyExternalId("HOM");
		shirt.setSubFamilyExternalId("CHEM");
		shirt.setNavItemNo("70061");
		shirt.setVariantCode("001");
		shirt.setCategoryCode("VET");
		shirt.setSectionCode("MANCHE");
		bed.items.put(ARGENTE, new ArrayList<>(Collections.singletonList(shirt)));
		bed.items.put(FRANCHISE, new ArrayList<>(Collections.singletonList(shirt)));
	}

	@Test
	@DisplayName("VET > HOM > CHEM > MANCHE: created in one run, in the order of the parts; then updated; then nothing to do")
	void treeCreatedThenUpdated() {
		bed.job().syncCatalogue();

		assertEquals(ErpCommunicationStatus.SUCCESS, bed.logged.get(0)[0]);
		assertEquals(Arrays.asList("categories", "families", "subFamilies", "sections", "items", "barcodes"),
				new ArrayList<>(parts().keySet()));
		ItemCategory category = savedCategory("VET");
		assertEquals("Categorie Vetements", category.getName());
		assertEquals(40000, category.getDisplayOrder());
		assertEquals("VET", category.getErpExternalId());
		assertTrue(category.getActive());
		ItemFamily family = bed.tables.familyRepository().findByCode("HOM").get();
		assertEquals("VET", family.getCategoryCode());
		assertEquals("HOM", bed.tables.subFamilyRepository().findByCode("CHEM").get().getItemFamily().getCode());
		ItemSection section = savedSection("MANCHE");
		assertEquals("Section Avec manche", section.getName());
		assertEquals("CHEM", section.getSubFamilyCode());
		assertEquals(70000, section.getDisplayOrder());
		Item shirt = bed.tables.itemByCode("70061~001").get();
		assertEquals("HOM", shirt.getItemFamily().getCode());
		assertEquals("CHEM", shirt.getItemSubFamily().getCode());
		assertEquals("VET", shirt.getCategoryCode());
		assertEquals("MANCHE", shirt.getSectionCode());
		assertEquals(1, bed.part("categories").get("new"));
		assertEquals(1, bed.part("sections").get("new"));

		// Updated at the ERP: VET renamed and moved, a new catégorie MODE for HOM, MANCHE moved to another sub-family
		vet.setName("Vetements");
		vet.setDisplayOrder(10000);
		bed.categories.add(category("MODE", "Mode", 5000));
		hom.setCategoryExternalId("MODE");
		bed.subFamilies.add(subFamily("CHEM2", "Sous-Famille Chemise 2", "HOM"));
		manche.setSubFamilyExternalId("CHEM2");
		manche.setName("Avec manches");
		bed.job().syncCatalogue();

		assertEquals(ErpCommunicationStatus.SUCCESS, bed.logged.get(1)[0]);
		assertEquals(1, bed.part("categories").get("new"));
		assertEquals(1, bed.part("categories").get("changed"));
		assertEquals(1, bed.part("families").get("changed"), "its catégorie changed");
		assertEquals(1, bed.part("sections").get("changed"));
		assertEquals("Vetements", savedCategory("VET").getName());
		assertEquals(10000, savedCategory("VET").getDisplayOrder());
		assertEquals("Mode", savedCategory("MODE").getName());
		assertEquals("MODE", bed.tables.familyRepository().findByCode("HOM").get().getCategoryCode());
		assertEquals("CHEM2", savedSection("MANCHE").getSubFamilyCode());
		assertEquals("Avec manches", savedSection("MANCHE").getName());
		assertEquals(2, bed.tables.categories.size());
		assertEquals(1, bed.tables.sections.size());

		bed.job().syncCatalogue();
		assertEquals(ErpCommunicationStatus.SUCCESS, bed.logged.get(2)[0]);
		for (String part : Arrays.asList("categories", "families", "subFamilies", "sections")) {
			assertEquals(0, bed.part(part).get("toHand"), part);
		}
	}

	@Test
	@DisplayName("An item whose catégorie and section the head office does not have is never held back: kept by code")
	void unknownCategoryAndSectionNeverHoldTheItem() {
		bed.categories.clear();
		bed.sections.clear();
		bed.items.get(ARGENTE).get(0).setCategoryCode("NOT-A-CATEGORY");
		bed.items.get(ARGENTE).get(0).setSectionCode("NOT-A-SECTION");

		bed.job().syncCatalogue();

		assertEquals(ErpCommunicationStatus.SUCCESS, bed.logged.get(0)[0]);
		Item shirt = bed.tables.itemByCode("70061~001").get();
		assertEquals("NOT-A-CATEGORY", shirt.getCategoryCode());
		assertEquals("NOT-A-SECTION", shirt.getSectionCode());
		assertTrue(bed.tables.categories.isEmpty());
	}

	@Test
	@DisplayName("An ERP without catégories and sections (an empty read): 0 read, 0 changed, nothing imported, a clean run")
	void emptyReadChangesNothing() {
		bed.twoLevelsOnly = true;

		bed.job().syncCatalogue(); // no ErpSyncWarningException: a clean run

		assertEquals(ErpCommunicationStatus.SUCCESS, bed.logged.get(0)[0]);
		assertNull(bed.logged.get(0)[1], "no warning");
		for (String part : Arrays.asList("categories", "sections")) {
			assertEquals(0, bed.part(part).get("read"), part);
			assertEquals(0, bed.part(part).get("new"), part);
			assertEquals(0, bed.part(part).get("changed"), part);
			assertEquals(0, bed.part(part).get("handed"), part);
			assertEquals(0, bed.part(part).get("heldBackByCap"), part);
		}
		assertEquals(0, bed.categoryImports);
		assertEquals(0, bed.sectionImports);
		assertTrue(bed.tables.categories.isEmpty());
		assertTrue(bed.tables.sections.isEmpty());
		assertEquals(1, bed.tables.families.size(), "the families are imported as before");
	}

	@Test
	@DisplayName("Regression: an ERP without catégories (Happyness) gets the families and sub-families summary of before")
	void familiesSummaryAsBefore() {
		bed.twoLevelsOnly = true;
		hom.setCategoryExternalId(null); // the Happyness pages give no catégorie
		bed.families.add(family("FEM", "Famille Femme", null));
		bed.subFamilies.add(subFamily("ROBE", "Robes", "FEM"));

		bed.job().syncCatalogue();

		LinkedHashSet<String> keysOfBefore = new LinkedHashSet<>(Arrays.asList("run", "page", "dryRun", "leftOut", "read",
				"new", "changed", "handed", "toHand", "heldBackByCap", "rounds", "durationMs"));
		for (String part : Arrays.asList("families", "subFamilies")) {
			Map<String, Object> summary = bed.part(part);
			assertEquals(keysOfBefore, summary.keySet(), part);
			assertEquals(2, summary.get("read"), part);
			assertEquals(2, summary.get("new"), part);
			assertEquals(0, summary.get("changed"), part);
			assertEquals(2, summary.get("handed"), part);
			assertEquals(1, summary.get("rounds"), part);
		}
		assertNull(bed.tables.familyRepository().findByCode("HOM").get().getCategoryCode());

		bed.job().syncCatalogue(); // nothing changed at the ERP: nothing handed, the family's catégorie not compared
		for (String part : Arrays.asList("families", "subFamilies")) {
			assertEquals(0, bed.part(part).get("new"), part);
			assertEquals(0, bed.part(part).get("changed"), part);
			assertEquals(0, bed.part(part).get("handed"), part);
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> parts() {
		Map<String, Object> summary = (Map<String, Object>) bed.logged.get(bed.logged.size() - 1)[2];
		return (Map<String, Object>) summary.get("parts");
	}

	private ItemCategory savedCategory(String code) {
		return bed.tables.categoryRepository().findByCode(code).orElseThrow(() -> new AssertionError("no " + code));
	}

	private ItemSection savedSection(String code) {
		return bed.tables.sectionRepository().findByCode(code).orElseThrow(() -> new AssertionError("no " + code));
	}
}
