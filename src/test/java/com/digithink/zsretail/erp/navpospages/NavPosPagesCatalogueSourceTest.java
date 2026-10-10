package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.engine.CatalogueEngine;
import com.digithink.zsretail.erp.engine.CatalogueImport;
import com.digithink.zsretail.erp.engine.CatalogueRun;
import com.digithink.zsretail.erp.engine.CatalogueState;
import com.digithink.zsretail.erp.engine.HeadOfficeCatalogue;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesCatalogueSource;
import com.digithink.zsretail.erp.navpospages.client.NavPosPagesSource;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesCatalogueSettings;
import com.digithink.zsretail.erp.navpospages.config.NavPosPagesProperties;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosPagesMapper;
import com.digithink.zsretail.erp.spi.ErpBarcodeRow;
import com.digithink.zsretail.erp.spi.ErpCatalogueSource.Part;
import com.digithink.zsretail.erp.spi.ErpCategories;
import com.digithink.zsretail.erp.spi.ErpRead;
import com.digithink.zsretail.erp.spi.StockPoints;
import com.fasterxml.jackson.core.type.TypeReference;

/**
 * Code cleanup step 3: the thin part of the navpospages connector as the catalogue engine reads it
 * (NavPosPagesCatalogueSource: the pages through the mapper), and the barcode cursor by position over the rows the
 * connector leaves out. NavPosPagesSyncTest runs the whole engine through this adapter unchanged.
 */
class NavPosPagesCatalogueSourceTest {

	private final NavPosPagesProperties properties = NavPosPagesTestSupport.properties();
	private final NavPosPagesMapper mapper = new NavPosPagesMapper(19, true);

	@Test
	@DisplayName("Barcode rows one by one: blank barcode or Item_No left out with the connector's reasons, the positions of every row kept")
	void barcodeRows() {
		List<NavPosBarcodeRow> rows = Arrays.asList(new NavPosBarcodeRow(" I1 ", " B1 ", 5L),
				new NavPosBarcodeRow("I1", "  ", 7L), new NavPosBarcodeRow(" ", "B9", 8L),
				new NavPosBarcodeRow("I2", "B1", 10L), new NavPosBarcodeRow("I3", "B3", null));
		ErpRead<ErpBarcodeRow> read = mapper.barcodeRows(rows);
		assertEquals(5, read.getRead());
		assertEquals(Arrays.asList(5L, 7L, 8L, 10L, null), read.getPositions(), "every row read, left out ones too");
		assertEquals(Long.valueOf(10), read.getHighestPosition());
		assertEquals(1, read.getLeftOut(NavPosPagesMapper.BLANK_BARCODE));
		assertEquals(1, read.getLeftOut(NavPosPagesMapper.BLANK_ITEM_NO));
		assertEquals(Arrays.asList("B1/I1/5", "B1/I2/10", "B3/I3/null"), read.getRows().stream()
				.map(row -> row.getBarcode().getBarcode() + "/" + row.getBarcode().getItemExternalId() + "/"
						+ row.getPosition())
				.collect(Collectors.toList()), "kept one by one, trimmed: the same barcode twice is the engine's");
		assertEquals("B1", read.getRows().get(0).getBarcode().getExternalId());

		// The DTO of a kept row is the one the mapper's barcodes() gives for that row
		ErpItemBarcodeDTO before = mapper.barcodes(Collections.singletonList(rows.get(3))).getRows().get(0);
		ErpItemBarcodeDTO now = read.getRows().get(1).getBarcode();
		assertEquals(before.getExternalId() + "|" + before.getBarcode() + "|" + before.getItemExternalId() + "|"
				+ before.getPrimaryBarcode(), now.getExternalId() + "|" + now.getBarcode() + "|" + now.getItemExternalId()
						+ "|" + now.getPrimaryBarcode());

		ErpRead<ErpBarcodeRow> empty = mapper.barcodeRows(new ArrayList<>());
		assertEquals(0, empty.getRead());
		assertNull(empty.getHighestPosition());
	}

	@Test
	@DisplayName("Categories, items and page names through the adapter: what the mapper gives for the same rows")
	void adapter() {
		Pages pages = new Pages();
		pages.categories = NavPosPagesTestSupport.rows("ItemCategory",
				new TypeReference<com.digithink.zsretail.erp.navpospages.dto.NavPosCollection<NavPosCategoryRow>>() {
				});
		pages.items = NavPosPagesTestSupport.rows("PointStockPOS",
				new TypeReference<com.digithink.zsretail.erp.navpospages.dto.NavPosCollection<NavPosStockRow>>() {
				});
		NavPosPagesCatalogueSource source = new NavPosPagesCatalogueSource(pages, mapper, properties);

		ErpCategories categories = source.categories();
		assertEquals(1, pages.categoryReads, "families and sub-families from one read");
		assertEquals(mapper.families(pages.categories).toString(), categories.getFamilies().toString());
		assertEquals(mapper.subFamilies(pages.categories).toString(), categories.getSubFamilies().toString());
		assertEquals(mapper.items(pages.items).toString(), source.items("FRANCHISE").toString());
		assertEquals(Collections.singletonList("FRANCHISE"), pages.locations);

		assertEquals("ItemCategory", source.pageLabel(Part.CATEGORIES));
		assertEquals("PointStockPOS", source.pageLabel(Part.ITEMS));
		assertEquals("ItemBarCodePOS", source.pageLabel(Part.BARCODES));
		assertEquals("url/ItemBarCodePOS", source.pageUrl(Part.BARCODES));
		properties.getPage().setBarcodes("OtherBarcodes");
		assertEquals("OtherBarcodes", source.pageLabel(Part.BARCODES), "the settings in effect, read each time");
	}

	@Test
	@DisplayName("The cursor passes a blank row below a barcode the import did not save, as by Entry_No before")
	void cursorOverLeftOutRows() {
		Pages pages = new Pages();
		pages.barcodes = Arrays.asList(new NavPosBarcodeRow("I1", "A", 1L), new NavPosBarcodeRow("I1", "", 2L),
				new NavPosBarcodeRow("I1", "B", 3L));
		HeadOffice headOffice = new HeadOffice();
		Map<String, String> state = new HashMap<>();
		state.put("items.run", "true");
		state.put("items.pending", "0");
		CatalogueEngine sync = new CatalogueEngine(new NavPosPagesCatalogueSource(pages, mapper, properties), headOffice,
				new NoStockPoints(), new MapState(state), new NavPosPagesCatalogueSettings(properties), new CatalogueImport() {
					@Override
					public void items(List<ErpItemDTO> packet) {
					}

					@Override
					public void barcodes(List<ErpItemBarcodeDTO> packet) {
						packet.stream().filter(dto -> !"B".equals(dto.getBarcode())).forEach(dto -> headOffice.barcodes
								.put(dto.getBarcode(), new HeadOfficeCatalogue.Barcode(dto.getBarcode(), "I1", true)));
					}
				});

		CatalogueRun<ErpItemBarcodeDTO> run = sync.barcodes();

		assertEquals("2", state.get("barcodes.cursor"), "over A and the blank row, never over B");
		assertEquals(Collections.singletonList("A"),
				run.getHanded().stream().map(ErpItemBarcodeDTO::getBarcode).collect(Collectors.toList()));
		assertEquals(3, run.count("read"));
		assertEquals(Collections.singletonMap(NavPosPagesMapper.BLANK_BARCODE, 1), run.getSummary().get("leftOut"));
		assertTrue(String.valueOf(run.getSummary().get("guard")).startsWith("1 barcodes handed but not at the head office"),
				run.toString());
		assertEquals("url/ItemBarCodePOS", run.getUrl());
	}

	/** The pages: rows given by the test; the barcodes after a position by Entry_No. */
	static final class Pages implements NavPosPagesSource {
		List<NavPosCategoryRow> categories = new ArrayList<>();
		List<NavPosStockRow> items = new ArrayList<>();
		List<NavPosBarcodeRow> barcodes = new ArrayList<>();
		int categoryReads;
		List<String> locations = new ArrayList<>();

		@Override
		public List<NavPosCategoryRow> readCategories() {
			categoryReads++;
			return categories;
		}

		@Override
		public List<NavPosStockRow> readItems(String locationCode) {
			locations.add(locationCode);
			return items;
		}

		@Override
		public List<NavPosBarcodeRow> readBarcodesAfter(long entryNo) {
			return barcodes.stream().filter(row -> row.getEntryNo() > entryNo).collect(Collectors.toList());
		}

		@Override
		public List<NavPosBarcodeRow> readBarcodesOfItems(List<String> itemNos) {
			return new ArrayList<>();
		}

		@Override
		public List<NavPosInvoiceRow> readInvoicesAfter(String yearPrefix, String afterNumber) {
			return new ArrayList<>();
		}

		@Override
		public List<NavPosInvoiceRow> readInvoicesByNumbers(List<String> numbers) {
			return new ArrayList<>();
		}

		@Override
		public String readFirstInvoiceNumber(String numberPrefix) {
			return null;
		}

		@Override
		public String pageUrl(String page) {
			return "url/" + page;
		}
	}

	/** A head office with the item I1 and the barcodes the import saved. */
	static final class HeadOffice implements HeadOfficeCatalogue {
		final Map<String, Barcode> barcodes = new LinkedHashMap<>();

		@Override
		public Map<String, Family> families() {
			return new LinkedHashMap<>();
		}

		@Override
		public Map<String, SubFamily> subFamilies() {
			return new LinkedHashMap<>();
		}

		@Override
		public Map<String, Item> items() {
			return Collections.singletonMap("I1",
					new Item("I1", "I1", null, 1.0, 19, true, "I1", null, null, null, null));
		}

		@Override
		public Map<String, Barcode> barcodes(Collection<String> values) {
			Map<String, Barcode> found = new LinkedHashMap<>();
			values.stream().filter(barcodes::containsKey).forEach(value -> found.put(value, barcodes.get(value)));
			return found;
		}

		@Override
		public String taxStampErpCode() {
			return null;
		}
	}

	static final class MapState implements CatalogueState {
		final Map<String, String> values;

		MapState(Map<String, String> values) {
			this.values = values;
		}

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
			return values.keySet().stream().filter(key -> key.startsWith(prefix)).sorted().collect(Collectors.toList());
		}
	}

	static final class NoStockPoints implements StockPoints {
		@Override
		public List<Point> activePoints() {
			return new ArrayList<>();
		}

		@Override
		public Map<String, Row> rows(long pointId) {
			return new LinkedHashMap<>();
		}

		@Override
		public int write(long pointId, List<Row> packet) {
			return 0;
		}

		@Override
		public void recordRead(long pointId, String status, String summary) {
		}
	}
}
