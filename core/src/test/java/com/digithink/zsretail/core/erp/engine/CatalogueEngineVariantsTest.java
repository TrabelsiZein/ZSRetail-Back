package com.digithink.zsretail.core.erp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.erp.service.ErpItemBootstrapService;
import com.digithink.zsretail.core.erp.service.ItemVariant;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.model.ItemFamily;
import com.digithink.zsretail.core.model.ItemSubFamily;
import com.digithink.zsretail.core.service.GeneralSetupService;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.erp.api.CatalogueRun;
import com.digithink.zsretail.erp.api.CatalogueSettings;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * Item variants (ZEN), step 3: the items run with the real import (ErpItemBootstrapService over the in-memory tables of
 * InMemoryItems), an ERP that hands one item per variant in two points de stock, and the head office read as
 * JpaHeadOfficeCatalogue projects it (its query is run on H2 in JpaHeadOfficeCatalogueTest). The 14 variant fields are
 * written and compared, the brand is written and compared only when the ERP gives one, the copies the engine makes of a
 * head office item keep them, and an ERP without variants gives the items of before (every new column null).
 */
class CatalogueEngineVariantsTest {

	static final String ARGENTE = "ARGENTE";
	static final String FRANCHISE = "FRANCHISE";

	private InMemoryItems tables;
	private Points points;
	private CatalogueEngine engine;
	/** The ERP: its rows by location code, as templates (each read hands copies: the engine fills blank names). */
	private final Map<String, List<ErpItemDTO>> erp = new LinkedHashMap<>();

	private ErpItemDTO s001;
	private ErpItemDTO m002;

	@BeforeEach
	void setUp() {
		tables = new InMemoryItems(1);
		GeneralSetupService setup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return null; // no TAX_STAMP_ERP_ITEM_CODE
			}
		};
		ErpItemBootstrapService bootstrap = new ErpItemBootstrapService(tables.familyRepository(),
				tables.subFamilyRepository(), tables.itemRepository(), tables.barcodeRepository(), null, null, null, null,
				null, setup);
		points = new Points();
		points.points.add(new StockPoints.Point(1, ARGENTE, "Argente"));
		points.points.add(new StockPoints.Point(2, FRANCHISE, "Franchise"));
		CatalogueSettings settings = new CatalogueSettings() {
			@Override
			public int packetSize() {
				return 500;
			}

			@Override
			public boolean dryRun() {
				return false;
			}

			@Override
			public Integer defaultVat() {
				return 19;
			}
		};
		engine = new CatalogueEngine(new Source(), new Tables(), points, new State(), settings, new CatalogueImport() {
			@Override
			public void items(List<ErpItemDTO> packet) {
				bootstrap.importItems(packet);
			}

			@Override
			public void barcodes(List<ErpItemBarcodeDTO> packet) {
				bootstrap.importItemBarcodes(packet);
			}
		});
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

	/** A copy of every field the engine and the import read. */
	private static ErpItemDTO copy(ErpItemDTO from) {
		ErpItemDTO to = new ErpItemDTO();
		to.setExternalId(from.getExternalId());
		to.setCode(from.getCode());
		to.setName(from.getName());
		to.setDescription(from.getDescription());
		to.setFamilyExternalId(from.getFamilyExternalId());
		to.setSubFamilyExternalId(from.getSubFamilyExternalId());
		to.setUnitPrice(from.getUnitPrice());
		to.setDefaultVAT(from.getDefaultVAT());
		to.setItemDiscGroup(from.getItemDiscGroup());
		to.setMaximumAuthorizedDiscount(from.getMaximumAuthorizedDiscount());
		to.setActive(from.getActive());
		to.setNavItemNo(from.getNavItemNo());
		to.setVariantCode(from.getVariantCode());
		to.setVariantDescription(from.getVariantDescription());
		to.setSizeCode(from.getSizeCode());
		to.setSizeDescription(from.getSizeDescription());
		to.setSizeSortOrder(from.getSizeSortOrder());
		to.setColorCode(from.getColorCode());
		to.setColorDescription(from.getColorDescription());
		to.setChoiceCode(from.getChoiceCode());
		to.setChoiceDescription(from.getChoiceDescription());
		to.setSeasonCode(from.getSeasonCode());
		to.setDivisionCode(from.getDivisionCode());
		to.setCategoryCode(from.getCategoryCode());
		to.setSectionCode(from.getSectionCode());
		to.setBrand(from.getBrand());
		to.setClearance(from.getClearance());
		to.setClearancePrice(from.getClearancePrice());
		return to;
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

	private static <T> ErpRead<T> empty() {
		return new ErpRead<>(new ArrayList<>(), 0, Collections.emptyMap(), Collections.emptyMap(), null);
	}

	// ─── Fakes ──────────────────────────────────────────────────

	/** The ERP: no category, the item rows of each location, no barcode (none needed here). */
	private final class Source implements ErpCatalogueSource {

		@Override
		public ErpCategories categories() {
			return new ErpCategories(empty(), empty());
		}

		@Override
		public ErpRead<ErpItemDTO> items(String locationCode) {
			List<ErpItemDTO> rows = erp.getOrDefault(locationCode, new ArrayList<>()).stream()
					.map(CatalogueEngineVariantsTest::copy).collect(Collectors.toList());
			return new ErpRead<>(rows, rows.size(), Collections.emptyMap(), Collections.emptyMap(), null);
		}

		@Override
		public ErpRead<ErpBarcodeRow> barcodesAfter(long position) {
			return new ErpRead<>(new ArrayList<>(), 0, Collections.emptyMap(), new ArrayList<>());
		}

		@Override
		public ErpRead<ErpBarcodeRow> barcodesOfItems(List<String> itemCodes) {
			return new ErpRead<>(new ArrayList<>(), 0, Collections.emptyMap(), new ArrayList<>());
		}

		@Override
		public String pageLabel(Part part) {
			return part.name();
		}

		@Override
		public String pageUrl(Part part) {
			return "test:" + part.name();
		}
	}

	/** The head office tables of InMemoryItems, read as JpaHeadOfficeCatalogue projects them. */
	private final class Tables implements HeadOfficeCatalogue {

		@Override
		public Map<String, Family> families() {
			Map<String, Family> families = new LinkedHashMap<>();
			for (ItemFamily f : tables.families.values()) {
				families.put(f.getCode(),
						new Family(f.getCode(), f.getName(), f.getDescription(), f.getActive(), f.getErpExternalId()));
			}
			return families;
		}

		@Override
		public Map<String, SubFamily> subFamilies() {
			Map<String, SubFamily> subFamilies = new LinkedHashMap<>();
			for (ItemSubFamily s : tables.subFamilies.values()) {
				subFamilies.put(s.getCode(), new SubFamily(s.getCode(), s.getName(), s.getDescription(), s.getActive(),
						s.getErpExternalId(), s.getItemFamily() == null ? null : s.getItemFamily().getCode()));
			}
			return subFamilies;
		}

		@Override
		public Map<String, HeadOfficeCatalogue.Item> items() {
			Map<String, HeadOfficeCatalogue.Item> items = new LinkedHashMap<>();
			// The entity (inside this class, Item is HeadOfficeCatalogue.Item)
			for (com.digithink.zsretail.core.model.Item i : tables.items.values()) {
				ItemVariant variant = new ItemVariant(i.getNavItemNo(), i.getVariantCode(), i.getVariantDescription(),
						i.getSizeCode(), i.getSizeDescription(), i.getSizeSortOrder(), i.getColorCode(),
						i.getColorDescription(), i.getChoiceCode(), i.getChoiceDescription(), i.getSeasonCode(),
						i.getDivisionCode(), i.getCategoryCode(), i.getSectionCode());
				items.put(i.getItemCode(), new HeadOfficeCatalogue.Item(i.getItemCode(), i.getName(), i.getDescription(),
						i.getUnitPrice(), i.getDefaultVAT(), i.getActive(), i.getErpExternalId(), i.getItemDiscGroup(),
						i.getMaximumAuthorizedDiscount(), i.getItemFamily() == null ? null : i.getItemFamily().getCode(),
						i.getItemSubFamily() == null ? null : i.getItemSubFamily().getCode(), variant, i.getBrand()));
			}
			return items;
		}

		@Override
		public Map<String, Barcode> barcodes(Collection<String> values) {
			Map<String, Barcode> found = new LinkedHashMap<>();
			for (ItemBarcode b : tables.barcodes.values()) {
				if (values.contains(b.getBarcode())) {
					found.put(b.getBarcode(), new Barcode(b.getBarcode(), b.getItem().getItemCode(), b.getActive()));
				}
			}
			return found;
		}

		@Override
		public String taxStampErpCode() {
			return null;
		}
	}

	/** ho_stock_point and ho_stock_point_item: a row whose item is not at the head office is left out. */
	private final class Points implements StockPoints {
		final List<Point> points = new ArrayList<>();
		final Map<Long, Map<String, Row>> rows = new HashMap<>();
		int writes;

		@Override
		public List<Point> activePoints() {
			return new ArrayList<>(points);
		}

		@Override
		public Map<String, Row> rows(long pointId) {
			return new LinkedHashMap<>(rows.getOrDefault(pointId, new TreeMap<>()));
		}

		@Override
		public int write(long pointId, List<Row> packet) {
			writes++;
			Map<String, Row> ofPoint = rows.computeIfAbsent(pointId, id -> new TreeMap<>());
			int written = 0;
			for (Row row : packet) {
				if (tables.itemByCode(row.itemCode).isPresent()) {
					ofPoint.put(row.itemCode, row);
					written++;
				}
			}
			return written;
		}

		@Override
		public void recordRead(long pointId, String status, String summary) {
		}
	}

	/** The state table. */
	private static final class State implements CatalogueState {
		final Map<String, String> values = new TreeMap<>();

		@Override
		public String get(String key) {
			return values.get(key);
		}

		@Override
		public void put(String key, String value) {
			values.put(key, value);
		}

		@Override
		public void remove(String key) {
			values.remove(key);
		}

		@Override
		public List<String> keysStartingWith(String prefix) {
			return values.keySet().stream().filter(key -> key.startsWith(prefix)).collect(Collectors.toList());
		}
	}
}
