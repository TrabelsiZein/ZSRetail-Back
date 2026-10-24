package com.digithink.zsretail.connector.zen.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
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
import com.digithink.zsretail.connector.zen.dto.ZenCollection;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;
import com.digithink.zsretail.connector.zen.mapper.ZenMapper;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCatalogueSource.Part;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Item variants (ZEN), step 8: the source the catalogue engine reads, on a fake API: it reads changes only, passes the
 * checkpoint to the read of a location, keeps the barcodes of the items asked for only, names its pages; the sample of
 * stockPoints (NewstockPointS.json, 2026-10-24) read through it; a row of the old page (publicPrice, no unitPrice)
 * fails the read of its location.
 */
class ZenCatalogueSourceTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	private final List<String> reads = new ArrayList<>();
	/** The rows the fake API answers for a location; null: one row of 70061~001 without prices. */
	private List<ZenStockPointRow> stockPoints;

	private final ZenSource api = new ZenSource() {
		@Override
		public List<ZenCategoryRow> readCategories() {
			return Collections.singletonList(new ZenCategoryRow("VET", "Vetements", "", 0, 1));
		}

		@Override
		public List<ZenStockPointRow> readStockPoints(String locationCode, OffsetDateTime since) {
			reads.add(locationCode + " since " + since);
			if (stockPoints != null) {
				return stockPoints;
			}
			ZenStockPointRow row = new ZenStockPointRow();
			row.setLocationCode(locationCode);
			row.setItemNo("70061");
			row.setVariantCode("001");
			return Collections.singletonList(row);
		}

		@Override
		public List<ZenBarcodeRow> readBarcodesAfter(long position) {
			reads.add("barcodes after " + position);
			return Collections.singletonList(
					new ZenBarcodeRow("a1", "70061", "001", "9000002740592", "2026-10-11T16:53:37.151Z"));
		}

		@Override
		public List<ZenBarcodeRow> readBarcodesOfItems(List<String> itemCodes) {
			// The ERP may answer more than asked for (another variant of the same item)
			return Arrays.asList(new ZenBarcodeRow("a1", "70061", "001", "9000002740592", "2026-10-11T16:53:37.151Z"),
					new ZenBarcodeRow("a2", "70061", "002", "9000002740593", "2026-10-11T16:53:37.152Z"));
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
	@DisplayName("The sample of stockPoints through the source: 100 rows of FRANCH-01, the price before VAT")
	void sample() throws IOException {
		try (InputStream in = getClass().getResourceAsStream("/zen/NewstockPointS.json")) {
			stockPoints = JSON.readValue(in, new TypeReference<ZenCollection<ZenStockPointRow>>() {
			}).getValue();
		}

		ErpRead<ErpItemDTO> read = source.items("FRANCH-01");

		assertEquals(100, read.getKept());
		ErpItemDTO tie = read.getRows().stream().filter(item -> item.getCode().equals("14ZDU05~001")).findFirst().get();
		assertEquals(0, new BigDecimal("16.38655").compareTo(tie.getUnitPrice()));
		assertEquals(0, new BigDecimal("5.88235").compareTo(tie.getClearancePrice()));
	}

	@Test
	@DisplayName("The old page: a row with publicPrice but no unitPrice fails the read of the location, nothing mapped")
	void oldPageFails() throws IOException {
		stockPoints = Arrays.asList(
				JSON.readValue("{\"itemNo\":\"14ZDU05\",\"variantCode\":\"001\",\"unitPrice\":16.38655,\"publicPrice\":19.5}",
						ZenStockPointRow.class),
				JSON.readValue("{\"itemNo\":\"14ZDU05\",\"variantCode\":\"002\",\"publicPrice\":19.5}", ZenStockPointRow.class));

		ZenReadException failed = assertThrows(ZenReadException.class, () -> source.items("FRANCH-01", null));

		assertEquals("stockPoints has no unitPrice: update the page", failed.getMessage());
		assertEquals("stockPoints", failed.getPage());
		// No price at all (neither field): not the old page, the mapper's price 0
		stockPoints = Arrays.asList(JSON.readValue("{\"itemNo\":\"A\",\"variantCode\":\"1\"}", ZenStockPointRow.class));
		assertEquals(1, source.items("FRANCH-01").getNote(ZenMapper.ZERO_PRICE));
	}

	@Test
	@DisplayName("Barcodes: after the engine's position, each row's position its lastModifiedDateTime in epoch"
			+ " milliseconds; of some items, only those asked for")
	void barcodes() {
		long at = Instant.parse("2026-10-11T16:53:37.151Z").toEpochMilli();
		assertEquals(Long.valueOf(at), source.barcodesAfter(at - 1).getRows().get(0).getPosition());
		assertEquals(Arrays.asList("barcodes after " + (at - 1)), reads);

		ErpRead<ErpBarcodeRow> read = source.barcodesOfItems(Collections.singletonList("70061~001"));

		assertEquals(Arrays.asList("70061~001"), read.getRows().stream().map(row -> row.getBarcode().getItemExternalId())
				.collect(Collectors.toList()));
		assertEquals(2, read.getRead());
	}

	@Test
	@DisplayName("The pages: stockPoints, itemCategories, barCodes by default")
	void pages() {
		assertEquals("stockPoints", source.pageLabel(Part.ITEMS));
		assertEquals("itemCategories", source.pageLabel(Part.CATEGORIES));
		assertEquals("barCodes", source.pageLabel(Part.BARCODES));
		assertEquals("http://nav.test/stockPoints", source.pageUrl(Part.ITEMS));
	}
}
