package com.digithink.zsretail.core.erp.engine;

import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.ARGENTE;
import static com.digithink.zsretail.core.erp.engine.CatalogueTestBed.FRANCHISE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * Item variants (ZEN), step 3: the items run with the real import (ErpItemBootstrapService over the in-memory tables of
 * InMemoryItems, {@link CatalogueTestBed}), an ERP that hands one item per variant in two points de stock, and the head
 * office read as JpaHeadOfficeCatalogue projects it (its query is run on H2 in JpaHeadOfficeCatalogueTest). The 14
 * variant fields are written and compared, the brand is written and compared only when the ERP gives one, the copies
 * the engine makes of a head office item keep them, and an ERP without variants gives the items of before (every new
 * column null).
 */
class CatalogueEngineVariantsTest {

	private InMemoryItems tables;
	private CatalogueTestBed.Points points;
	private CatalogueEngine engine;
	/** The ERP: its rows by location code, as templates (each read hands copies: the engine fills blank names). */
	private Map<String, List<ErpItemDTO>> erp;

	private ErpItemDTO s001;
	private ErpItemDTO m002;

	@BeforeEach
	void setUp() {
		CatalogueTestBed bed = new CatalogueTestBed();
		tables = bed.tables;
		points = bed.points;
		engine = bed.engine;
		erp = bed.items;
		s001 = variant("70061", "001", "S", 20, "001", "Noir");
		m002 = variant("70061", "002", "M", 30, "001", "Noir");
		erp.put(ARGENTE, new ArrayList<>(Arrays.asList(s001, m002)));
		erp.put(FRANCHISE, new ArrayList<>(Arrays.asList(s001, m002)));
	}

	@Test
	@DisplayName("Two variants of 70061 in two points: two items with their variant fields, four rows; again: no change")
	void twoVariantsInTwoPoints() {
		CatalogueRun<ErpItemDTO> run = engine.items();

		assertEquals(2, run.count("new"));
		assertEquals(2, run.count("applied"));
		Item small = item("70061~001");
		assertEquals("CHEMISE", small.getName(), "the ERP's item description, never joined with the variant");
		assertEquals("70061~001", small.getErpExternalId());
		assertEquals("70061", small.getNavItemNo());
		assertEquals("001", small.getVariantCode());
		assertEquals("S / Noir / 1er Choix", small.getVariantDescription());
		assertEquals("S", small.getSizeCode());
		assertNull(small.getSizeDescription(), "blank at the ERP: null");
		assertEquals(20, small.getSizeSortOrder());
		assertEquals("001", small.getColorCode());
		assertEquals("Noir", small.getColorDescription());
		assertEquals("01", small.getChoiceCode());
		assertEquals("1er Choix", small.getChoiceDescription());
		assertEquals("ANC", small.getSeasonCode());
		assertEquals("DSZ", small.getDivisionCode());
		assertEquals("VET", small.getCategoryCode());
		assertEquals("MANCHE", small.getSectionCode());
		assertEquals("Marque Zen", small.getBrand());
		Item medium = item("70061~002");
		assertEquals("M", medium.getSizeCode());
		assertEquals(30, medium.getSizeSortOrder());
		assertEquals(Arrays.asList("70061~001", "70061~002"), new ArrayList<>(points.rows(1).keySet()));
		assertEquals(Arrays.asList("70061~001", "70061~002"), new ArrayList<>(points.rows(2).keySet()));

		int saves = tables.itemSaves;
		CatalogueRun<ErpItemDTO> again = engine.items();
		assertEquals(0, again.count("new"));
		assertEquals(0, again.count("changed"), "the fields as stored compare equal to the ERP's (blank = null)");
		assertEquals(0, again.count("applied"));
		assertEquals(saves, tables.itemSaves);
	}

	@Test
	@DisplayName("The colour of 001 changes at the ERP: the item is changed and updated, its rows in the points are not")
	void colourChangeUpdatesTheItemNotItsRows() {
		engine.items();
		StockPoints.Row rowBefore = points.rows(1).get("70061~001");
		int writes = points.writes;
		s001.setColorCode("002");
		s001.setColorDescription("Blanc");
		s001.setVariantDescription("S / Blanc / 1er Choix");

		CatalogueRun<ErpItemDTO> run = engine.items();

		assertEquals(0, run.count("new"));
		assertEquals(1, run.count("changed"));
		assertEquals(0, run.count("deactivated"));
		assertEquals(Collections.singletonList("70061~001"), codes(run.getHanded()));
		assertEquals("002", item("70061~001").getColorCode());
		assertEquals("Blanc", item("70061~001").getColorDescription());
		assertEquals("S / Blanc / 1er Choix", item("70061~001").getVariantDescription());
		assertEquals("Noir", item("70061~002").getColorDescription());
		for (String point : Arrays.asList(ARGENTE, FRANCHISE)) {
			Map<String, Object> ofPoint = pointSummary(run, point);
			assertEquals(0, ofPoint.get("changed"), point);
			assertEquals(0, ofPoint.get("written"), point);
		}
		assertEquals(writes, points.writes, "no row of a point written");
		assertSame(rowBefore, points.rows(1).get("70061~001"));
	}

	@Test
	@DisplayName("The variant description alone changes at the ERP (ZEN builds it from the descriptions): the item is"
			+ " changed and updated")
	void variantDescriptionAloneIsAChange() {
		engine.items();
		s001.setVariantDescription("Noir / 1er Choix");

		CatalogueRun<ErpItemDTO> run = engine.items();

		assertEquals(1, run.count("changed"));
		assertEquals(Collections.singletonList("70061~001"), codes(run.getHanded()));
		assertEquals("Noir / 1er Choix", item("70061~001").getVariantDescription());
		assertEquals("Noir", item("70061~001").getColorDescription(), "the other fields as before");
	}

	@Test
	@DisplayName("The ERP gives a blank brand for an item that has one: not a change, and kept when the item is updated")
	void blankBrandKeepsTheBrand() {
		engine.items();
		s001.setBrand("");
		m002.setBrand(null);

		CatalogueRun<ErpItemDTO> blankOnly = engine.items();
		assertEquals(0, blankOnly.count("changed"), "a blank brand is not compared");
		assertEquals("Marque Zen", item("70061~001").getBrand());
		assertEquals("Marque Zen", item("70061~002").getBrand());

		s001.setUnitPrice(new BigDecimal("55"));
		CatalogueRun<ErpItemDTO> priceChange = engine.items();
		assertEquals(Collections.singletonList("70061~001"), codes(priceChange.getHanded()));
		assertEquals(55.0, item("70061~001").getUnitPrice());
		assertEquals("Marque Zen", item("70061~001").getBrand(), "the import keeps the brand when the ERP gives none");

		s001.setBrand("Marque Barsha");
		CatalogueRun<ErpItemDTO> newBrand = engine.items();
		assertEquals(1, newBrand.count("changed"), "a brand given by the ERP is compared");
		assertEquals("Marque Barsha", item("70061~001").getBrand());
	}

	@Test
	@DisplayName("A variant gone from every point: handed inactive with its size, colour and brand")
	void goneVariantKeepsItsFields() {
		engine.items();
		erp.get(ARGENTE).remove(m002);
		erp.get(FRANCHISE).remove(m002);

		CatalogueRun<ErpItemDTO> run = engine.items();

		assertEquals(1, run.count("deactivated"));
		Item medium = item("70061~002");
		assertFalse(medium.getActive());
		assertEquals("M", medium.getSizeCode());
		assertEquals(30, medium.getSizeSortOrder());
		assertEquals("Noir", medium.getColorDescription());
		assertEquals("70061", medium.getNavItemNo());
		assertEquals("Marque Zen", medium.getBrand());
	}

	@Test
	@DisplayName("The first point answers no row: its last rows count, with the item's variant fields, so nothing changes")
	void silentPointKeepsTheFields() {
		engine.items();
		erp.put(ARGENTE, new ArrayList<>());

		CatalogueRun<ErpItemDTO> run = engine.items();

		assertEquals(0, run.count("changed"), "the copy of the stored row carries the item's variant fields");
		assertEquals(0, run.count("applied"));
		assertEquals("S", item("70061~001").getSizeCode());
		assertEquals("Noir", item("70061~001").getColorDescription());
		assertEquals("Marque Zen", item("70061~001").getBrand());
	}

	@Test
	@DisplayName("An ERP without variants (Happyness): the items of before, every new column null, the brand untouched")
	void erpWithoutVariantsAsBefore() {
		ErpItemDTO bike = new ErpItemDTO();
		bike.setExternalId("1000");
		bike.setCode("1000");
		bike.setName("Bicyclette");
		bike.setDescription("Bicyclette");
		bike.setUnitPrice(new BigDecimal("100"));
		bike.setDefaultVAT(19);
		bike.setActive(Boolean.TRUE);
		erp.put(ARGENTE, new ArrayList<>(Collections.singletonList(bike)));
		erp.put(FRANCHISE, new ArrayList<>(Collections.singletonList(bike)));

		CatalogueRun<ErpItemDTO> run = engine.items();

		assertEquals(1, run.count("new"));
		Item item = item("1000");
		assertEquals("Bicyclette", item.getName());
		assertEquals("Bicyclette", item.getDescription());
		assertEquals(100.0, item.getUnitPrice());
		assertEquals(19, item.getDefaultVAT());
		assertTrue(item.getActive());
		assertEquals("1000", item.getErpExternalId());
		assertEquals("", item.getItemDiscGroup());
		assertNull(item.getMaximumAuthorizedDiscount());
		assertNull(item.getItemFamily());
		assertNull(item.getItemSubFamily());
		assertNull(item.getBrand());
		assertNoVariant(item);

		item.setBrand("Marque X"); // typed by hand at the head office
		CatalogueRun<ErpItemDTO> unchanged = engine.items();
		assertEquals(0, unchanged.count("changed"), "the new compare makes no item of before changed");
		assertEquals("Marque X", item("1000").getBrand());

		bike.setUnitPrice(new BigDecimal("120"));
		CatalogueRun<ErpItemDTO> priceChange = engine.items();
		assertEquals(1, priceChange.count("changed"));
		assertEquals(120.0, item("1000").getUnitPrice());
		assertEquals("Marque X", item("1000").getBrand());
		assertNoVariant(item("1000"));
	}

	// ─── Helpers ────────────────────────────────────────────────

	/** A variant row as the ZEN sample has it (stockPoints.json): blank size description, the same in every point. */
	private static ErpItemDTO variant(String itemNo, String variantCode, String size, int sortOrder, String colorCode,
			String colorName) {
		ErpItemDTO row = new ErpItemDTO();
		row.setExternalId(itemNo + "~" + variantCode);
		row.setCode(itemNo + "~" + variantCode);
		row.setName("CHEMISE");
		row.setDescription("CHEMISE");
		row.setUnitPrice(new BigDecimal("50"));
		row.setDefaultVAT(19);
		row.setActive(Boolean.TRUE);
		row.setNavItemNo(itemNo);
		row.setVariantCode(variantCode);
		row.setVariantDescription(size + " / " + colorName + " / 1er Choix");
		row.setSizeCode(size);
		row.setSizeDescription("");
		row.setSizeSortOrder(sortOrder);
		row.setColorCode(colorCode);
		row.setColorDescription(colorName);
		row.setChoiceCode("01");
		row.setChoiceDescription("1er Choix");
		row.setSeasonCode("ANC");
		row.setDivisionCode("DSZ");
		row.setCategoryCode("VET");
		row.setSectionCode("MANCHE");
		row.setBrand("Marque Zen");
		return row;
	}

	private Item item(String code) {
		return tables.itemByCode(code).orElseThrow(() -> new AssertionError("no item " + code));
	}

	private static void assertNoVariant(Item item) {
		assertNull(item.getNavItemNo());
		assertNull(item.getVariantCode());
		assertNull(item.getVariantDescription());
		assertNull(item.getSizeCode());
		assertNull(item.getSizeDescription());
		assertNull(item.getSizeSortOrder());
		assertNull(item.getColorCode());
		assertNull(item.getColorDescription());
		assertNull(item.getChoiceCode());
		assertNull(item.getChoiceDescription());
		assertNull(item.getSeasonCode());
		assertNull(item.getDivisionCode());
		assertNull(item.getCategoryCode());
		assertNull(item.getSectionCode());
	}

	private static List<String> codes(List<ErpItemDTO> items) {
		return items.stream().map(ErpItemDTO::getCode).collect(Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> pointSummary(CatalogueRun<ErpItemDTO> run, String pointCode) {
		return (Map<String, Object>) ((Map<String, Object>) run.getSummary().get("stockPoints")).get(pointCode);
	}
}
