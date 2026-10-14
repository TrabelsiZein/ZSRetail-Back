package com.digithink.zsretail.connector.zen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.connector.zen.config.ZenProperties;
import com.digithink.zsretail.connector.zen.dto.ZenBarcodeRow;
import com.digithink.zsretail.connector.zen.dto.ZenCategoryRow;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource.Part;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;

/**
 * Item variants (ZEN), step 8: the source the catalogue engine reads, on a fake API: it reads changes only, passes the
 * checkpoint to the read of a location, keeps the barcodes of the items asked for only, names its pages.
 */
class ZenCatalogueSourceTest {

	private final List<String> reads = new ArrayList<>();

	private final ZenSource api = new ZenSource() {
		@Override
		public List<ZenCategoryRow> readCategories() {
			return Collections.singletonList(new ZenCategoryRow("VET", "Vetements", "", 0, 1));
		}

		@Override
		public List<ZenStockPointRow> readStockPoints(String locationCode, OffsetDateTime since) {
			reads.add(locationCode + " since " + since);
			ZenStockPointRow row = new ZenStockPointRow();
			row.setLocationCode(locationCode);
			row.setItemNo("70061");
			row.setVariantCode("001");
			return Collections.singletonList(row);
		}

		@Override
		public List<ZenBarcodeRow> readBarcodesAfter(long entryNo) {
			return Collections.singletonList(new ZenBarcodeRow("70061", "001", "9000002740592", entryNo + 1));
		}

		@Override
		public List<ZenBarcodeRow> readBarcodesOfItems(List<String> itemCodes) {
			// The ERP may answer more than asked for (another variant of the same item)
			return Arrays.asList(new ZenBarcodeRow("70061", "001", "9000002740592", 1L),
					new ZenBarcodeRow("70061", "002", "9000002740593", 2L));
		}

		@Override
		public String pageUrl(String page) {
			return "http://nav.test/" + page;
		}
	};

	private final ZenCatalogueSource source = new ZenCatalogueSource(api, new ZenMapper(19), new ZenProperties());

	@Test
	@DisplayName("Changes only: the checkpoint passed to the read of the location, none for a whole read")
	void changesOnly() {
		assertTrue(source.readsChangesOnly());
		OffsetDateTime since = OffsetDateTime.parse("2026-10-11T16:53:37.15Z");

		ErpRead<ErpItemDTO> changes = source.items("ARGENTE", since);
		source.items("BLANC");

		assertEquals(Arrays.asList("ARGENTE since 2026-10-11T16:53:37.150Z", "BLANC since null"), reads);
		assertEquals("70061~001", changes.getRows().get(0).getCode());
		assertEquals("VET", source.categories().getCategories().getRows().get(0).getCode());
	}

	@Test
	@DisplayName("Barcodes: by Entry_No; of some items, only those asked for")
	void barcodes() {
		assertEquals(Long.valueOf(121), source.barcodesAfter(120).getRows().get(0).getPosition());

		ErpRead<ErpBarcodeRow> read = source.barcodesOfItems(Collections.singletonList("70061~001"));

		assertEquals(Arrays.asList("70061~001"), read.getRows().stream().map(row -> row.getBarcode().getItemExternalId())
				.collect(Collectors.toList()));
		assertEquals(2, read.getRead());
	}

	@Test
	@DisplayName("The pages: stockPoints, itemCategories, ItemBarCodePOS by default")
	void pages() {
		assertEquals("stockPoints", source.pageLabel(Part.ITEMS));
		assertEquals("itemCategories", source.pageLabel(Part.CATEGORIES));
		assertEquals("ItemBarCodePOS", source.pageLabel(Part.BARCODES));
		assertEquals("http://nav.test/stockPoints", source.pageUrl(Part.ITEMS));
	}
}
