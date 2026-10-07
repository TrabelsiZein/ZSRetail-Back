package com.digithink.zsretail.erp.navpospages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCollection;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosPagesMapper;
import com.digithink.zsretail.erp.navpospages.mapper.NavPosResult;
import com.fasterxml.jackson.core.type.TypeReference;

/** ERP catalogue, step 5: the rows of the pages to the Erp*DTO classes, on the samples and on chosen rows. */
class NavPosPagesMapperTest {

	private final NavPosPagesMapper mapper = new NavPosPagesMapper(19, true);

	private static List<NavPosCategoryRow> categorySample() {
		return NavPosPagesTestSupport.rows("ItemCategory", new TypeReference<NavPosCollection<NavPosCategoryRow>>() {
		});
	}

	@Test
	@DisplayName("Item 000001 of the sample: name without the padding, VAT 19, price 2.0588235294; x 1.19 at 3 decimals = 2.450")
	void item000001() {
		List<NavPosStockRow> rows = NavPosPagesTestSupport.rows("PointStockPOS",
				new TypeReference<NavPosCollection<NavPosStockRow>>() {
				});
		NavPosResult<ErpItemDTO> result = mapper.items(rows);
		assertEquals(50, result.getRead());
		assertEquals(50, result.getKept());
		assertTrue(result.getLeftOut().isEmpty(), result.toString());
		ErpItemDTO item = result.getRows().get(0);
		assertEquals("000001", item.getCode());
		assertEquals("000001", item.getExternalId());
		assertEquals("FLORELLE VAO LES PETALES N° 01", item.getName());
		assertEquals(item.getName(), item.getDescription());
		assertEquals(Integer.valueOf(19), item.getDefaultVAT());
		assertEquals(new BigDecimal("2.0588235294"), item.getUnitPrice());
		assertEquals("FAM-ONG-MAQ", item.getFamilyExternalId());
		assertEquals("SF-VEO-ONG-MAQ", item.getSubFamilyExternalId());
		assertEquals(Boolean.TRUE, item.getActive());
		assertNull(item.getItemDiscGroup());
		assertNull(item.getMaximumAuthorizedDiscount());
		assertNull(item.getLastModifiedAt());
		// Back with the VAT, at 3 decimals: the price of the page, in BigDecimal and as the till computes it (double)
		assertEquals(new BigDecimal("2.450"),
				item.getUnitPrice().multiply(new BigDecimal("1.19")).setScale(3, RoundingMode.HALF_UP));
		assertEquals(2.45, Math.round(item.getUnitPrice().doubleValue() * (1.0 + 19 / 100.0) * 1000) / 1000.0);
		assertEquals(7.35, Math.round(3 * item.getUnitPrice().doubleValue() * (1.0 + 19 / 100.0) * 1000) / 1000.0);
	}

	@Test
	@DisplayName("Every item of the sample comes back to its page price at 3 decimals, 1 and 3 units")
	void everySamplePrice() {
		List<NavPosStockRow> rows = NavPosPagesTestSupport.rows("PointStockPOS",
				new TypeReference<NavPosCollection<NavPosStockRow>>() {
				});
		List<ErpItemDTO> items = mapper.items(rows).getRows();
		for (int i = 0; i < rows.size(); i++) {
			BigDecimal page = rows.get(i).getUnitPrice().setScale(3, RoundingMode.HALF_UP);
			double unit = items.get(i).getUnitPrice().doubleValue() * 1.19;
			assertEquals(page.doubleValue(), Math.round(unit * 1000) / 1000.0, rows.get(i).getItemNo());
			assertEquals(page.multiply(BigDecimal.valueOf(3)).doubleValue(), Math.round(3 * unit * 1000) / 1000.0,
					rows.get(i).getItemNo() + " x 3");
		}
	}

	@Test
	@DisplayName("Categories of the sample: 13 families, 34 sub-families, the 3 Categorie rows ignored")
	void categoriesSample() {
		NavPosResult<ErpItemFamilyDTO> families = mapper.families(categorySample());
		assertEquals(50, families.getRead());
		assertEquals(13, families.getKept());
		assertEquals(34, families.getLeftOut("type Subfamily"));
		assertEquals(3, families.getLeftOut("type Categorie"));
		ErpItemFamilyDTO first = families.getRows().get(0);
		assertEquals("FAM-ACB-BEB", first.getCode());
		assertEquals("FAM-ACB-BEB", first.getExternalId());
		assertEquals("Accessoires Bébé", first.getName());
		assertEquals(Boolean.TRUE, first.getActive());

		NavPosResult<ErpItemSubFamilyDTO> subFamilies = mapper.subFamilies(categorySample());
		assertEquals(34, subFamilies.getKept());
		assertEquals(13, subFamilies.getLeftOut("type Family"));
		assertEquals(3, subFamilies.getLeftOut("type Categorie"));
		assertEquals(0, subFamilies.getLeftOut(NavPosPagesMapper.FAMILY_NOT_IN_READ));
		assertEquals("SF-ACB-ACB-BEB", subFamilies.getRows().get(0).getCode());
		assertEquals("FAM-ACB-BEB", subFamilies.getRows().get(0).getFamilyExternalId());
	}

	@Test
	@DisplayName("Type any case and spacing; a sub-family without its family, a Categorie row, a blank code: left out and counted")
	void categoriesRules() {
		List<NavPosCategoryRow> rows = Arrays.asList(new NavPosCategoryRow(" F1 ", " Family one ", "C1", " family "),
				new NavPosCategoryRow("C1", "Category", "", "Categorie"),
				new NavPosCategoryRow("S1", "Sub one", " F1 ", "SUBFAMILY"),
				new NavPosCategoryRow("S2", "Orphan", "F9", "Subfamily"),
				new NavPosCategoryRow("S3", "Under a category", "C1", "Subfamily"),
				new NavPosCategoryRow("  ", "Blank", "", "Family"));
		NavPosResult<ErpItemFamilyDTO> families = mapper.families(rows);
		assertEquals(Arrays.asList("F1"), families.getRows().stream().map(ErpItemFamilyDTO::getCode)
				.collect(Collectors.toList()));
		assertEquals("Family one", families.getRows().get(0).getName());
		assertEquals(1, families.getLeftOut(NavPosPagesMapper.BLANK_CODE));
		assertEquals(1, families.getLeftOut("type Categorie"));

		NavPosResult<ErpItemSubFamilyDTO> subFamilies = mapper.subFamilies(rows);
		assertEquals(Arrays.asList("S1"), subFamilies.getRows().stream().map(ErpItemSubFamilyDTO::getCode)
				.collect(Collectors.toList()));
		assertEquals("F1", subFamilies.getRows().get(0).getFamilyExternalId());
		assertEquals(2, subFamilies.getLeftOut(NavPosPagesMapper.FAMILY_NOT_IN_READ), subFamilies.toString());
		assertEquals(6, subFamilies.getRead());
	}

	@Test
	@DisplayName("Variants: the blank Variant_Code wins, else the lowest; the other rows counted; blank Item_No; zero price inactive")
	void itemsRules() {
		List<NavPosStockRow> rows = Arrays.asList(new NavPosStockRow("A", "V2", "A v2", new BigDecimal("11.9"), "F", "S"),
				new NavPosStockRow("A", "", "A plain", new BigDecimal("11.9"), "F", "S"),
				new NavPosStockRow("A", "V1", "A v1", new BigDecimal("11.9"), "F", "S"),
				new NavPosStockRow("B", "V2", "B v2", new BigDecimal("5"), "F", " "),
				new NavPosStockRow(" B ", "V1", "B v1", new BigDecimal("5"), "F", null),
				new NavPosStockRow(" ", "", "no code", new BigDecimal("1"), "F", "S"),
				new NavPosStockRow("C", null, "free", null, "", "S"),
				new NavPosStockRow("D", "", "zero", BigDecimal.ZERO, "F", "S"));
		NavPosResult<ErpItemDTO> result = mapper.items(rows);
		assertEquals(Arrays.asList("A", "B", "C", "D"),
				result.getRows().stream().map(ErpItemDTO::getCode).collect(Collectors.toList()));
		assertEquals("A plain", result.getRows().get(0).getName());
		assertEquals("B v1", result.getRows().get(1).getName());
		assertNull(result.getRows().get(1).getSubFamilyExternalId(), "blank Subfamily: null");
		assertNull(result.getRows().get(2).getFamilyExternalId(), "blank Family: null");
		assertEquals(new BigDecimal("10.0000000000"), result.getRows().get(0).getUnitPrice());
		assertEquals(BigDecimal.ZERO, result.getRows().get(2).getUnitPrice());
		assertEquals(BigDecimal.ZERO, result.getRows().get(3).getUnitPrice());
		// Step 6: an item without a price is inactive; with a price it is active
		assertEquals(Boolean.TRUE, result.getRows().get(0).getActive());
		assertEquals(Boolean.FALSE, result.getRows().get(2).getActive());
		assertEquals(Boolean.FALSE, result.getRows().get(3).getActive());
		assertEquals(8, result.getRead());
		assertEquals(4, result.getKept());
		assertEquals(3, result.getLeftOut(NavPosPagesMapper.VARIANT_ROW));
		assertEquals(1, result.getLeftOut(NavPosPagesMapper.BLANK_ITEM_NO));
		assertEquals(2, result.getNote(NavPosPagesMapper.ZERO_PRICE));
	}

	@Test
	@DisplayName("price-includes-vat=false: Unit_Price as it is; another VAT is the item's VAT")
	void priceBeforeVat() {
		NavPosPagesMapper beforeVat = new NavPosPagesMapper(7, false);
		ErpItemDTO item = beforeVat.items(Arrays.asList(new NavPosStockRow("X", "", "X", new BigDecimal("2.45"), "F", "S")))
				.getRows().get(0);
		assertEquals(new BigDecimal("2.45"), item.getUnitPrice());
		assertEquals(Integer.valueOf(7), item.getDefaultVAT());
		assertEquals(new BigDecimal("10.0000000000"), new NavPosPagesMapper(0, true).priceBeforeVat(BigDecimal.TEN));
	}

	@Test
	@DisplayName("Barcodes of the sample: 50 kept, the highest Entry_No read")
	void barcodesSample() {
		NavPosResult<ErpItemBarcodeDTO> result = mapper.barcodes(NavPosPagesTestSupport.rows("ItemBarCodePOS",
				new TypeReference<NavPosCollection<NavPosBarcodeRow>>() {
				}));
		assertEquals(50, result.getKept());
		assertEquals(Long.valueOf(48793), result.getHighestEntryNo());
		ErpItemBarcodeDTO first = result.getRows().get(0);
		assertEquals("0000016", first.getBarcode());
		assertEquals("0000016", first.getExternalId());
		assertEquals("0000016", first.getItemExternalId());
		assertNull(first.getPrimaryBarcode());
	}

	@Test
	@DisplayName("Barcodes: the same barcode twice keeps the highest Entry_No; blank barcode or Item_No left out; empty page")
	void barcodesRules() {
		List<NavPosBarcodeRow> rows = Arrays.asList(new NavPosBarcodeRow("I1", " 619 ", 5L),
				new NavPosBarcodeRow("I2", "619", 9L), new NavPosBarcodeRow("I3", "619", 7L),
				new NavPosBarcodeRow("I4", " ", 12L), new NavPosBarcodeRow(" ", "700", 3L),
				new NavPosBarcodeRow(" I5 ", "701", 4L));
		NavPosResult<ErpItemBarcodeDTO> result = mapper.barcodes(rows);
		assertEquals(Arrays.asList("619", "701"),
				result.getRows().stream().map(ErpItemBarcodeDTO::getBarcode).collect(Collectors.toList()));
		assertEquals("I2", result.getRows().get(0).getItemExternalId());
		assertEquals("I5", result.getRows().get(1).getItemExternalId());
		assertEquals(2, result.getLeftOut(NavPosPagesMapper.SAME_BARCODE));
		assertEquals(1, result.getLeftOut(NavPosPagesMapper.BLANK_BARCODE));
		assertEquals(1, result.getLeftOut(NavPosPagesMapper.BLANK_ITEM_NO));
		assertEquals(Long.valueOf(12), result.getHighestEntryNo(), "the highest read, left out rows included");

		NavPosResult<ErpItemBarcodeDTO> empty = mapper.barcodes(Arrays.asList());
		assertNull(empty.getHighestEntryNo());
		assertEquals(0, empty.getRead());
	}
}
