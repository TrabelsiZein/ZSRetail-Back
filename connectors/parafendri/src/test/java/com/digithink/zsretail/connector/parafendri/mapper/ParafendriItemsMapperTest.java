package com.digithink.zsretail.connector.parafendri.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.connector.parafendri.dto.ParafendriCollection;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriStockPointRow;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * ParaFendri, step 3: the rows of stockPoints (the sample stockPoints.json, the shape of the API) to items, checked
 * against the tree of the sample itemCategories.json as step 2 keeps it: the fields, the price before VAT as received,
 * the VAT, the unit, the inactive reasons, the links (a code not kept by the tree: none, noted), the rows left out.
 */
class ParafendriItemsMapperTest {

	private final ParafendriMapper mapper = new ParafendriMapper();
	private ErpRead<ErpItemDTO> read;
	private Map<String, ErpItemDTO> byCode;

	static List<ParafendriStockPointRow> sample() throws IOException {
		try (InputStream in = ParafendriItemsMapperTest.class.getResourceAsStream("/parafendri/stockPoints.json")) {
			return new ObjectMapper().readValue(in,
					new TypeReference<ParafendriCollection<ParafendriStockPointRow>>() {
					}).getValue();
		}
	}

	@BeforeEach
	void setUp() throws IOException {
		ErpCategories tree = mapper.categories(ParafendriMapperTest.sample());
		read = mapper.items(" PV-GREMDA ", sample(), tree);
		byCode = new LinkedHashMap<>();
		read.getRows().forEach(item -> byCode.put(item.getCode(), item));
	}

	@Test
	@DisplayName("13 rows read, 9 items kept in the order of the page")
	void counts() {
		assertEquals(13, read.getRead());
		assertEquals(9, read.getKept());
		assertEquals("ITM001, ITM002, ITM003, ITM004, ITM005, ITM006, ITM007, ITM008, ITM011",
				read.getRows().stream().map(ErpItemDTO::getCode).collect(Collectors.joining(", ")));
	}

	@Test
	@DisplayName("An item: code and external id itemNo, the stock point's description, unit, links, price as received")
	void oneItem() {
		ErpItemDTO item = byCode.get("ITM001");
		assertEquals("ITM001", item.getExternalId());
		assertEquals("Coffret parfum Rose 50ml", item.getName());
		assertEquals("Coffret parfum Rose 50ml", item.getDescription());
		assertEquals("PCS", item.getUnitOfMeasure());
		assertEquals(new BigDecimal("18.90756"), item.getUnitPrice(), "before VAT, never rounded");
		assertEquals(Integer.valueOf(19), item.getDefaultVAT());
		assertEquals(Boolean.TRUE, item.getActive());
		assertEquals("COS", item.getCategoryCode());
		assertEquals("COS-COF", item.getFamilyExternalId());
		assertEquals("COS-COF-PAR", item.getSubFamilyExternalId());
		assertNull(item.getSectionCode(), "a blank sectionCode: none");
		assertEquals(OffsetDateTime.parse("2026-10-25T08:15:42.317Z"), item.getLastModifiedAt());
		// 18.90756 × 1.19 = 22.4999964: the till shows 22.500 (18.908 rounded would give 22.501)
		assertEquals("22.500", item.getUnitPrice().multiply(new BigDecimal("1.19")).setScale(3,
				java.math.RoundingMode.HALF_UP).toPlainString());
	}

	@Test
	@DisplayName("No stock point description: the item's; a blank unit: none")
	void fallbacks() {
		ErpItemDTO item = byCode.get("ITM002");
		assertEquals("PARFUM HOMME BOIS", item.getName());
		assertEquals("PARFUM HOMME BOIS", item.getDescription());
		assertNull(item.getUnitOfMeasure());
		assertEquals("COS-PAR-HOM", item.getSubFamilyExternalId());
	}

	@Test
	@DisplayName("Inactive, kept and noted: itemBlocked, itemSalesBlocked, stockPointBlocked, a zero price")
	void inactive() {
		for (String code : new String[] { "ITM003", "ITM004", "ITM005", "ITM006" }) {
			assertEquals(Boolean.FALSE, byCode.get(code).getActive(), code);
		}
		assertEquals(0, BigDecimal.ZERO.compareTo(byCode.get("ITM006").getUnitPrice()));
		assertEquals(1, read.getNote(ParafendriMapper.ITEM_BLOCKED));
		assertEquals(1, read.getNote(ParafendriMapper.ITEM_SALES_BLOCKED));
		assertEquals(1, read.getNote(ParafendriMapper.STOCK_POINT_BLOCKED));
		assertEquals(1, read.getNote(ParafendriMapper.ZERO_PRICE));
		assertTrue(byCode.get("ITM007").getActive());
	}

	@Test
	@DisplayName("A VAT that is not a whole number: rounded, noted")
	void vatRounded() {
		assertEquals(Integer.valueOf(8), byCode.get("ITM007").getDefaultVAT(), "7.5 rounded half up");
		assertEquals(new BigDecimal("12.34567"), byCode.get("ITM007").getUnitPrice());
		assertEquals(1, read.getNote(ParafendriMapper.VAT_NOT_WHOLE));
	}

	@Test
	@DisplayName("Codes the tree does not keep: no link, noted, the item kept; a code it keeps linked")
	void codesOutsideTheTree() {
		ErpItemDTO item = byCode.get("ITM008");
		assertNull(item.getCategoryCode(), "ZZZ is no catégorie");
		assertNull(item.getFamilyExternalId(), "XXX-UNKNOWN is no famille");
		assertNull(item.getSubFamilyExternalId(), "SF-ORPH: left out of the tree by step 2");
		assertEquals("COS-COF-PAR-LUX", item.getSectionCode());
		assertEquals(1, read.getNote(ParafendriMapper.CATEGORY_NOT_IN_TREE));
		assertEquals(1, read.getNote(ParafendriMapper.FAMILY_NOT_IN_TREE));
		assertEquals(1, read.getNote(ParafendriMapper.SUB_FAMILY_NOT_IN_TREE));
		assertEquals(0, read.getNote(ParafendriMapper.SECTION_NOT_IN_TREE));
	}

	@Test
	@DisplayName("Business Central's empty date: no lastModifiedAt (it never moves the checkpoint), noted, the item kept")
	void emptyDate() {
		assertNull(byCode.get("ITM011").getLastModifiedAt());
		assertEquals(1, read.getNote(ParafendriMapper.NO_DATE));
	}

	@Test
	@DisplayName("Left out with their reason: a blank itemNo, another location, no vatPercent, the same itemNo twice")
	void leftOut() {
		Map<String, Integer> expected = new LinkedHashMap<>();
		expected.put(ParafendriMapper.NO_VAT, 1);
		expected.put(ParafendriMapper.BLANK_ITEM_NO, 1);
		expected.put(ParafendriMapper.SAME_ITEM, 1);
		expected.put(ParafendriMapper.OTHER_LOCATION, 1);
		assertEquals(expected, read.getLeftOut());
		assertEquals("Coffret parfum Rose 50ml", byCode.get("ITM001").getName(), "the first ITM001 kept");
		assertFalse(byCode.containsKey("ITM009"));
		assertFalse(byCode.containsKey("ITM010"));
	}
}
