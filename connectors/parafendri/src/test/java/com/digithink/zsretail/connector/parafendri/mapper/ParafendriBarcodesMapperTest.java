package com.digithink.zsretail.connector.parafendri.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.connector.parafendri.dto.ParafendriBarcodeRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriCollection;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ParaFendri, step 4: the rows of barCodes (the sample barCodes.json, the shape of the API) to barcodes: the value, the
 * item, the row's id as external id (the value without one), the date; a barcode of an item this store does not have
 * kept (the import leaves it out); a blank barCode and a blank itemNo left out with their reason; the primary flag never
 * set.
 */
class ParafendriBarcodesMapperTest {

	static List<ParafendriBarcodeRow> sample() throws IOException {
		try (InputStream in = ParafendriBarcodesMapperTest.class.getResourceAsStream("/parafendri/barCodes.json")) {
			return new ObjectMapper().readValue(in, new TypeReference<ParafendriCollection<ParafendriBarcodeRow>>() {
			}).getValue();
		}
	}

	@Test
	@DisplayName("7 rows read, 5 kept: value, item, the GUID as external id, the date; no primary flag")
	void barcodes() throws IOException {
		ErpRead<ErpItemBarcodeDTO> read = new ParafendriMapper().barcodes(sample());
		assertEquals(7, read.getRead());
		assertEquals("6191234500011 ITM001, 6191234500028 ITM002, 6191234500035 ITM999, 6191234500059 ITM011,"
				+ " 6191234500066 ITM004", read.getRows().stream().map(b -> b.getBarcode() + " " + b.getItemExternalId())
						.collect(Collectors.joining(", ")));
		ErpItemBarcodeDTO first = read.getRows().get(0);
		assertEquals("b1c2d3e4-0001-f111-9a06-00155d66bd01", first.getExternalId(), "a changed value replaces the row's");
		assertEquals(OffsetDateTime.parse("2026-10-25T09:00:00.100Z"), first.getLastModifiedAt());
		assertNull(first.getPrimaryBarcode(), "left to the import, as EMTOP's");
		assertEquals("6191234500066", read.getRows().get(4).getExternalId(), "no id: the value");
	}

	@Test
	@DisplayName("Left out with their reason: a blank barCode, a blank itemNo; an empty date noted, the barcode kept")
	void leftOutAndNoted() throws IOException {
		ErpRead<ErpItemBarcodeDTO> read = new ParafendriMapper().barcodes(sample());
		Map<String, Integer> expected = new LinkedHashMap<>();
		expected.put(ParafendriMapper.BLANK_BARCODE, 1);
		expected.put(ParafendriMapper.BLANK_BARCODE_ITEM, 1);
		assertEquals(expected, read.getLeftOut());
		assertEquals(1, read.getNote(ParafendriMapper.NO_DATE));
		assertNull(read.getRows().get(3).getLastModifiedAt(), "ITM011's 0001-01-01: no date, never the checkpoint");
	}
}
