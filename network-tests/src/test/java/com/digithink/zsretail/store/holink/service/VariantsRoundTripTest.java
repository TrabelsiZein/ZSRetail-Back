package com.digithink.zsretail.store.holink.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import com.digithink.zsretail.core.link.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.core.link.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.support.InMemoryLoyalty;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.service.CopiesDownFeed;
import com.digithink.zsretail.headoffice.service.HoCatalogueService;
import com.digithink.zsretail.headoffice.service.InMemoryDownTables;
import com.digithink.zsretail.headoffice.support.InMemoryCatalogue;
import com.digithink.zsretail.store.holink.dto.DownApplyResult;
import com.digithink.zsretail.store.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.store.holink.model.LinkRight;
import com.digithink.zsretail.store.support.InMemoryStoreLink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Item variants (ZEN), step 7: the catégories, the sections and the variant fields of the items from the head office to
 * a store, through the real feed (head office) and the real handler and writer (store), in memory, as
 * CatalogueRoundTripTest. The tree of the ZEN sample VET (catégorie) > HOM (famille) > CHEM (sous-famille) > MANCHE
 * (section) and the item 70061~001; a second round trip changes nothing; an unknown field is ignored; a blank brand
 * never erases the store's; a head office without variants gives the store what it gave before.
 */
class VariantsRoundTripTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private InMemoryCatalogue ho;
	private InMemoryCatalogue store;
	private InMemoryStoreLink link;
	private CopiesDownFeed feed;
	private HoCatalogueService hoCatalogue;
	private CatalogueDownHandler handler;
	private Store b;
	private String cursor = "";

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		store = new InMemoryCatalogue(1000);
		link = new InMemoryStoreLink(new InMemoryLoyalty(5000));
		InMemoryDownTables down = new InMemoryDownTables();
		CopiesDownFeed[] feedRef = new CopiesDownFeed[1];
		hoCatalogue = new HoCatalogueService(ho.familyRepository(), ho.subFamilyRepository(), ho.itemRepository(),
				ho.barcodeRepository(), ho.compositionRepository(), ho.priceLineRepository(), () -> feedRef[0],
				TransactionOperations.withoutTransaction(), null, null, ho.categoryRepository(), ho.sectionRepository());
		feed = down.feed(Collections.singletonList(hoCatalogue));
		feedRef[0] = feed;
		CatalogueRights rights = new CatalogueRights(InMemoryLoyalty.proxy(
				com.digithink.zsretail.store.holink.repository.LinkRightRepository.class, (method, args) -> {
					if ("findByCode".equals(method)) {
						return java.util.Optional.<LinkRight>empty();
					}
					return InMemoryLoyalty.UNHANDLED;
				}));
		CatalogueCopyWriter writer = new CatalogueCopyWriter(store.familyRepository(), store.subFamilyRepository(),
				store.itemRepository(), store.barcodeRepository(), store.compositionRepository(),
				store.categoryRepository(), store.sectionRepository());
		handler = new CatalogueDownHandler(writer, link.downRecordLog(), rights, link.exchangeLog(),
				TransactionOperations.withoutTransaction());
		b = ho.store("B");
	}

	/** The ZEN tree and item at the head office, as the ERP import leaves them. */
	private Item zenCatalogue() {
		ItemCategory vet = new ItemCategory();
		vet.setCode("VET");
		vet.setName("Categorie Vetements");
		vet.setDisplayOrder(40000);
		ho.categoryRepository().save(vet);
		ItemFamily hom = ho.family("HOM");
		hom.setCategoryCode("VET");
		ItemSubFamily chem = ho.subFamily("CHEM", hom);
		ItemSection manche = new ItemSection();
		manche.setCode("MANCHE");
		manche.setName("Section Avec manche");
		manche.setDisplayOrder(70000);
		manche.setSubFamilyCode("CHEM");
		ho.sectionRepository().save(manche);
		Item shirt = ho.item("70061~001", 50.0, chem);
		shirt.setName("CHEMISE");
		shirt.setBrand("Marque Zen");
		shirt.setNavItemNo("70061");
		shirt.setVariantCode("001");
		shirt.setVariantDescription("S / Noir / 1er Choix");
		shirt.setSizeCode("S");
		shirt.setSizeSortOrder(20);
		shirt.setColorCode("001");
		shirt.setColorDescription("Noir");
		shirt.setChoiceCode("01");
		shirt.setChoiceDescription("1er Choix");
		shirt.setSeasonCode("ANC");
		shirt.setDivisionCode("DSZ");
		shirt.setCategoryCode("VET");
		shirt.setSectionCode("MANCHE");
		ho.barcode("9000002740592", shirt);
		return shirt;
	}

	/** One cycle as CopiesDownPuller runs it: the backfill, the pull, the retries. */
	private DownApplyResult sync() {
		feed.initialise(); // the startup backfill: records without a change row
		CopiesDownAnswerDTO page = feed.pull(b, "CATALOGUE", cursor, 500);
		DownApplyResult result = handler.apply(page.getRecords(), page.getRemoved());
		cursor = page.getCursor();
		handler.retry();
		return result;
	}

	@Test
	@DisplayName("VET > HOM > CHEM > MANCHE and 70061~001: the store gets the same catégorie, section, item fields and brand")
	void zenCatalogueReachesTheStore() {
		zenCatalogue();
		assertEquals(Arrays.asList("CATEGORY:VET", "FAMILY:HOM", "SUBFAMILY:CHEM", "SECTION:MANCHE", "ITEM:70061~001",
				"BARCODE:9000002740592"), new ArrayList<>(hoCatalogue.currentTargets().keySet()), "the backfill order");

		DownApplyResult result = sync();

		assertEquals(6, result.getApplied());
		assertEquals(0, result.getProblems());
		ItemCategory vet = store.categoryRepository().findByCode("VET").get();
		assertEquals("Categorie Vetements", vet.getName());
		assertEquals(40000, vet.getDisplayOrder());
		assertEquals(RecordOrigin.HEAD_OFFICE, vet.getOrigin());
		assertEquals("VET", store.familyRepository().findByCode("HOM").get().getCategoryCode());
		ItemSection manche = store.sectionRepository().findByCode("MANCHE").get();
		assertEquals("Section Avec manche", manche.getName());
		assertEquals("CHEM", manche.getSubFamilyCode());
		assertEquals(RecordOrigin.HEAD_OFFICE, manche.getOrigin());
		Item shirt = store.itemByCode("70061~001").get();
		assertEquals("CHEMISE", shirt.getName());
		assertEquals("Marque Zen", shirt.getBrand());
		assertEquals("70061", shirt.getNavItemNo());
		assertEquals("001", shirt.getVariantCode());
		assertEquals("S / Noir / 1er Choix", shirt.getVariantDescription());
		assertEquals("S", shirt.getSizeCode());
		assertNull(shirt.getSizeDescription());
		assertEquals(20, shirt.getSizeSortOrder());
		assertEquals("001", shirt.getColorCode());
		assertEquals("Noir", shirt.getColorDescription());
		assertEquals("01", shirt.getChoiceCode());
		assertEquals("1er Choix", shirt.getChoiceDescription());
		assertEquals("ANC", shirt.getSeasonCode());
		assertEquals("DSZ", shirt.getDivisionCode());
		assertEquals("VET", shirt.getCategoryCode());
		assertEquals("MANCHE", shirt.getSectionCode());
		assertEquals("CHEM", shirt.getItemSubFamily().getCode());
		assertEquals(DownRecordStatus.APPLIED, link.downRecords.get("CATEGORY:VET").getStatus());
		assertEquals(DownRecordStatus.APPLIED, link.downRecords.get("SECTION:MANCHE").getStatus());

		// A second round trip of every record: nothing written
		cursor = "";
		DownApplyResult again = sync();
		assertEquals(0, again.getApplied());
		assertEquals(6, again.getUnchanged());
		assertEquals(0, again.getProblems());
	}

	@Test
	@DisplayName("The store applies the kinds in this order: catégories, families, sub-families, sections, items, barcodes")
	void copyOrder() {
		assertEquals(Arrays.asList(CatalogueKind.CATEGORY, CatalogueKind.FAMILY, CatalogueKind.SUBFAMILY,
				CatalogueKind.SECTION, CatalogueKind.ITEM, CatalogueKind.BARCODE), Arrays.asList(CatalogueKind.values()));
		assertEquals(CatalogueKind.SECTION, CatalogueKind.ofRecordCode("SECTION:MANCHE"));
		assertEquals(CatalogueKind.SUBFAMILY, CatalogueKind.ofRecordCode("SUBFAMILY:CHEM"));
		assertEquals("VET", CatalogueKind.codeOf("CATEGORY:VET"));
	}

	@Test
	@DisplayName("An item copy with a field the store does not know is applied, the field ignored")
	void unknownFieldIgnored() throws Exception {
		Item shirt = zenCatalogue();
		ObjectNode copy = MAPPER.valueToTree(CatalogueItemCopyDTO.of(shirt, 50.0, null));
		copy.put("clearance", true);
		copy.put("aFieldOfALaterBuild", "anything");
		copy.put("familyCode", (String) null);
		copy.put("subFamilyCode", (String) null);

		assertEquals("ITEM:70061~001", CatalogueDownHandler.read(copy).code);
		DownApplyResult result = handler.apply(Collections.<JsonNode>singletonList(copy), Collections.emptyList());

		assertEquals(1, result.getApplied());
		assertEquals(0, result.getProblems());
		assertEquals("Noir", store.itemByCode("70061~001").get().getColorDescription());
	}

	@Test
	@DisplayName("A blank brand at the head office never erases the store's; a catégorie deleted there is inactive here")
	void blankBrandAndRemovedCategory() {
		Item shirt = zenCatalogue();
		sync();
		shirt.setBrand(null);
		shirt.setColorDescription("Blanc");
		hoCatalogue.afterSave(CatalogueKind.ITEM, null, shirt);
		ItemCategory vet = ho.categoryRepository().findByCode("VET").get();
		hoCatalogue.beforeDelete(CatalogueKind.CATEGORY, vet);
		ho.categories.remove(vet.getId());

		DownApplyResult result = sync();

		Item atStore = store.itemByCode("70061~001").get();
		assertEquals("Blanc", atStore.getColorDescription());
		assertEquals("Marque Zen", atStore.getBrand(), "kept");
		assertEquals(1, result.getRemoved());
		assertFalse(store.categoryRepository().findByCode("VET").get().getActive(), "inactive, never deleted");
		assertEquals("VET", atStore.getCategoryCode(), "the item keeps its code");
	}

	@Test
	@DisplayName("Regression: a head office without variants gives the store what it gave before (no catégorie, no section)")
	void withoutVariantsAsBefore() {
		ItemFamily f1 = ho.family("F1");
		ItemSubFamily sf1 = ho.subFamily("SF1", f1);
		Item b001 = ho.item("B001", 10.0, sf1);
		ho.barcode("6191234567890", b001);

		DownApplyResult result = sync();

		assertEquals(4, result.getApplied());
		assertEquals(Arrays.asList("FAMILY:F1", "SUBFAMILY:SF1", "ITEM:B001", "BARCODE:6191234567890"),
				new ArrayList<>(hoCatalogue.currentTargets().keySet()));
		assertTrue(store.categories.isEmpty());
		assertTrue(store.sections.isEmpty());
		assertNull(store.familyRepository().findByCode("F1").get().getCategoryCode());
		Item item = store.itemByCode("B001").get();
		assertEquals("Item B001", item.getName());
		assertEquals(10.0, item.getUnitPrice());
		assertEquals("SF1", item.getItemSubFamily().getCode());
		assertNull(item.getBrand());
		List<Object> variant = Arrays.asList(item.getNavItemNo(), item.getVariantCode(), item.getVariantDescription(),
				item.getSizeCode(), item.getSizeDescription(), item.getSizeSortOrder(), item.getColorCode(),
				item.getColorDescription(), item.getChoiceCode(), item.getChoiceDescription(), item.getSeasonCode(),
				item.getDivisionCode(), item.getCategoryCode(), item.getSectionCode());
		assertTrue(variant.stream().allMatch(value -> value == null), "every new column null: " + variant);
	}
}
