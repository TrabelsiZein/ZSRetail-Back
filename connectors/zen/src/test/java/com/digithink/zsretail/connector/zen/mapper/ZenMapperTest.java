package com.digithink.zsretail.connector.zen.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.connector.zen.dto.ZenBarcodeRow;
import com.digithink.zsretail.connector.zen.dto.ZenCategoryRow;
import com.digithink.zsretail.connector.zen.dto.ZenCollection;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Item variants (ZEN), step 8: the mapper on the two samples of ZEN's API (src/test/resources/zen, as received on
 * 2026-10-11 and 2026-10-14) and on rows written here for what the samples do not have (the fields active and vat, a
 * blank variant, orphans of the tree).
 */
class ZenMapperTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static List<ZenStockPointRow> stockPoints;
	private static List<ZenCategoryRow> categories;

	private final ZenMapper mapper = new ZenMapper(19);

	@BeforeAll
	static void samples() throws IOException {
		stockPoints = sample("stockPoints.json", new TypeReference<ZenCollection<ZenStockPointRow>>() {
		});
		categories = sample("itemCategories.json", new TypeReference<ZenCollection<ZenCategoryRow>>() {
		});
	}

	private static <T> List<T> sample(String file, TypeReference<ZenCollection<T>> type) throws IOException {
		try (InputStream in = ZenMapperTest.class.getResourceAsStream("/zen/" + file)) {
			return JSON.readValue(in, type).getValue();
		}
	}

	/** A row written as the API would answer it. */
	private static ZenStockPointRow row(String json) throws IOException {
		return JSON.readValue(json.replace('`', '"'), ZenStockPointRow.class);
	}

	private static Map<String, ErpItemDTO> byCode(ErpRead<ErpItemDTO> read) {
		return read.getRows().stream().collect(Collectors.toMap(ErpItemDTO::getCode, Function.identity()));
	}

	@Test
	@DisplayName("Item code: itemNo~variantCode, itemNo alone when the variant code is blank, trimmed")
	void codeBuilding() {
		assertEquals("70061~001", ZenMapper.itemCode("70061", "001"));
		assertEquals("LS-10PC~LS-10PC-B", ZenMapper.itemCode("LS-10PC", "LS-10PC-B"));
		assertEquals("1000", ZenMapper.itemCode("1000", ""));
		assertEquals("1000", ZenMapper.itemCode(" 1000 ", null));
		assertEquals("70061~001", ZenMapper.itemCode(" 70061 ", " 001 "));
	}

	@Test
	@DisplayName("Sample, ARGENTE: its 6 rows (the others of another location), the fields of 70061~001")
	void sampleOfOneLocation() {
		ErpRead<ErpItemDTO> read = mapper.items("ARGENTE", stockPoints);

		assertEquals(100, read.getRead());
		assertEquals(6, read.getKept());
		assertEquals(94, read.getLeftOut(ZenMapper.OTHER_LOCATION));
		assertEquals(Arrays.asList("70061~001", "70061~002", "70061~003", "70061~004", "LS-10PC~LS-10PC-B", "LS-75~LS-75-B"),
				read.getRows().stream().map(ErpItemDTO::getCode).collect(Collectors.toList()));
		ErpItemDTO shirt = read.getRows().get(0);
		assertEquals("70061~001", shirt.getExternalId());
		assertEquals("CHEMISE", shirt.getName(), "itemDescription, never the variant's");
		assertEquals("CHEMISE", shirt.getDescription());
		assertEquals("70061", shirt.getNavItemNo());
		assertEquals("001", shirt.getVariantCode());
		assertEquals("S / Noir / 1er Choix", shirt.getVariantDescription());
		assertEquals("S", shirt.getSizeCode());
		assertNull(shirt.getSizeDescription(), "blank: null");
		assertEquals(20, shirt.getSizeSortOrder());
		assertEquals("001", shirt.getColorCode());
		assertEquals("Noir", shirt.getColorDescription());
		assertNull(shirt.getChoiceCode());
		assertNull(shirt.getChoiceDescription());
		assertNull(shirt.getSeasonCode());
		assertNull(shirt.getDivisionCode());
		assertNull(shirt.getCategoryCode());
		assertNull(shirt.getSectionCode());
		assertNull(shirt.getFamilyExternalId());
		assertNull(shirt.getSubFamilyExternalId());
		assertNull(shirt.getBrand(), "blank brandCode: null, the head office keeps its brand");
		assertEquals(19, shirt.getDefaultVAT());
		assertNull(shirt.getLastModifiedAt(), "NAV's empty date 0001-01-01");
		assertFalse(shirt.getClearance());
		assertEquals(0, BigDecimal.ZERO.compareTo(shirt.getClearancePrice()));
		// Every row of ARGENTE has publicPrice 0 and no date in the sample
		assertFalse(shirt.getActive());
		assertEquals(6, read.getNote(ZenMapper.ZERO_PRICE));
		assertEquals(6, read.getNote(ZenMapper.NO_DATE));
	}

	@Test
	@DisplayName("Sample, FRANCHISE: priced rows active, price before VAT, brand, clearance 7, the time read; 3 at price 0")
	void sampleOfFranchise() {
		ErpRead<ErpItemDTO> read = mapper.items("FRANCHISE", stockPoints);

		assertEquals(84, read.getKept());
		assertEquals(3, read.getNote(ZenMapper.ZERO_PRICE));
		assertEquals(81, read.getRows().stream().filter(ErpItemDTO::getActive).count());
		assertEquals(81, read.getRows().stream().filter(ErpItemDTO::getClearance).count());
		ErpItemDTO tie = byCode(read).get("14ZDU05~001");
		assertTrue(tie.getActive());
		assertEquals(0, new BigDecimal("19.5").compareTo(tie.getUnitPrice()), "publicPrice, before VAT");
		assertEquals("CRAVATE HM PETIT CARREAUX SATINE", tie.getName());
		assertEquals("ZEN", tie.getBrand());
		assertEquals("DSZ", tie.getDivisionCode());
		assertEquals("ANC", tie.getSeasonCode());
		assertEquals("STD", tie.getSizeCode());
		assertEquals(60, tie.getSizeSortOrder());
		assertEquals("VERT PISTACHE", tie.getColorDescription());
		assertEquals("001", tie.getChoiceCode());
		assertEquals("1er Choix", tie.getChoiceDescription());
		assertTrue(tie.getClearance());
		assertEquals(0, new BigDecimal("7").compareTo(tie.getClearancePrice()));
		assertEquals(OffsetDateTime.parse("2026-10-11T16:53:37.16Z"), tie.getLastModifiedAt());
		// The four locations: every row of the sample is one item of one location
		int kept = 0;
		for (String location : new String[] { "ARGENTE", "BLANC", "BLEU", "FRANCHISE" }) {
			kept += mapper.items(location, stockPoints).getKept();
		}
		assertEquals(100, kept);
	}

	@Test
	@DisplayName("A blank variant code: the item number alone, no variant code")
	void blankVariantCode() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items("BLEU", Collections.singletonList(
				row("{`locationCode`:`BLEU`,`itemNo`:`1000`,`variantCode`:``,`itemDescription`:`Bicyclette`,`publicPrice`:100}")));

		ErpItemDTO bike = read.getRows().get(0);
		assertEquals("1000", bike.getCode());
		assertEquals("1000", bike.getExternalId());
		assertEquals("1000", bike.getNavItemNo());
		assertNull(bike.getVariantCode());
	}

	@Test
	@DisplayName("active and vat absent (the API today): active, the default VAT; present: read; a VAT rounded and noted")
	void activeAndVat() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items(null, Arrays.asList(
				row("{`itemNo`:`A`,`variantCode`:`1`,`itemDescription`:`A`,`publicPrice`:10}"),
				row("{`itemNo`:`B`,`variantCode`:`1`,`itemDescription`:`B`,`publicPrice`:10,`active`:false,`vat`:7}"),
				row("{`itemNo`:`C`,`variantCode`:`1`,`itemDescription`:`C`,`publicPrice`:10,`active`:true,`vat`:12.5}")));

		Map<String, ErpItemDTO> items = byCode(read);
		assertTrue(items.get("A~1").getActive(), "active absent: true");
		assertEquals(19, items.get("A~1").getDefaultVAT(), "vat absent: the default");
		assertFalse(items.get("B~1").getActive());
		assertEquals(7, items.get("B~1").getDefaultVAT());
		assertTrue(items.get("C~1").getActive());
		assertEquals(13, items.get("C~1").getDefaultVAT());
		assertEquals(1, read.getNote(ZenMapper.INACTIVE));
		assertEquals(1, read.getNote(ZenMapper.VAT_NOT_WHOLE));
	}

	@Test
	@DisplayName("Price 0 or none: inactive, at 0; clearance absent: false, no clearance price")
	void zeroPriceAndClearance() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items(null, Arrays.asList(
				row("{`itemNo`:`A`,`variantCode`:`1`,`itemDescription`:`A`,`publicPrice`:0,`active`:true}"),
				row("{`itemNo`:`B`,`variantCode`:`1`,`itemDescription`:`B`}")));

		Map<String, ErpItemDTO> items = byCode(read);
		assertFalse(items.get("A~1").getActive(), "price 0, even when the row says active");
		assertEquals(0, BigDecimal.ZERO.compareTo(items.get("A~1").getUnitPrice()));
		assertFalse(items.get("B~1").getActive(), "no price");
		assertEquals(2, read.getNote(ZenMapper.ZERO_PRICE));
		assertFalse(items.get("B~1").getClearance(), "clearance absent: false");
		assertNull(items.get("B~1").getClearancePrice());
		assertNull(items.get("B~1").getLastModifiedAt());
	}

	@Test
	@DisplayName("Left out: a blank itemNo, ~ in a code, the same item and variant twice (the first kept)")
	void leftOut() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items(null, Arrays.asList(
				row("{`itemNo`:` `,`variantCode`:`1`,`publicPrice`:1}"),
				row("{`itemNo`:`A~B`,`variantCode`:`1`,`publicPrice`:1}"),
				row("{`itemNo`:`A`,`variantCode`:`1`,`itemDescription`:`first`,`publicPrice`:1}"),
				row("{`itemNo`:`A`,`variantCode`:`1`,`itemDescription`:`second`,`publicPrice`:1}")));

		assertEquals(4, read.getRead());
		assertEquals(1, read.getKept());
		assertEquals("first", read.getRows().get(0).getName());
		assertEquals(1, read.getLeftOut(ZenMapper.BLANK_ITEM_NO));
		assertEquals(1, read.getLeftOut(ZenMapper.SEPARATOR_IN_CODE));
		assertEquals(1, read.getLeftOut(ZenMapper.SAME_ROW));
	}

	@Test
	@DisplayName("lastModifiedDateTime: ISO with its offset; NAV's empty date, blank or unreadable: none")
	void modifiedAt() {
		assertEquals(OffsetDateTime.parse("2026-10-11T16:53:37.137Z"), ZenMapper.modifiedAt("2026-10-11T16:53:37.137Z"));
		assertEquals(OffsetDateTime.parse("2026-10-11T18:53:37+02:00"), ZenMapper.modifiedAt(" 2026-10-11T18:53:37+02:00 "));
		assertNull(ZenMapper.modifiedAt("0001-01-01T00:00:00Z"));
		assertNull(ZenMapper.modifiedAt(""));
		assertNull(ZenMapper.modifiedAt(null));
		assertNull(ZenMapper.modifiedAt("yesterday"));
	}

	@Test
	@DisplayName("Sample tree: catégories, families with their catégorie, CHEM under HOM, MANCHE under CHEM")
	void categoryTree() {
		ErpCategories tree = mapper.categories(categories);

		assertEquals(Arrays.asList("DIVERS", "MEUBLES", "VET"), codes(tree.getCategories(), ErpItemCategoryDTO::getCode));
		ErpItemCategoryDTO vet = tree.getCategories().getRows().get(2);
		assertEquals("Categorie Vetements", vet.getName());
		assertEquals(40000, vet.getDisplayOrder());
		assertEquals(Arrays.asList("BUR:MEUBLES", "CHAISE:MEUBLES", "ENF:VET", "FEM:VET", "FOURN:DIVERS", "HOM:VET",
				"TABLE:MEUBLES"), codes(tree.getFamilies(), f -> f.getCode() + ":" + f.getCategoryExternalId()));
		ErpItemFamilyDTO hom = tree.getFamilies().getRows().get(5);
		assertEquals("Famille Homme", hom.getName());
		assertEquals(50000, hom.getDisplayOrder());
		assertEquals(Arrays.asList("CHEM:HOM"), codes(tree.getSubFamilies(), s -> s.getCode() + ":" + s.getFamilyExternalId()));
		assertEquals(Arrays.asList("MANCHE:CHEM"),
				codes(tree.getSections(), s -> s.getCode() + ":" + s.getSubFamilyExternalId()));
		ErpItemSectionDTO manche = tree.getSections().getRows().get(0);
		assertEquals("Section Avec manche", manche.getName());
		assertEquals(70000, manche.getDisplayOrder());
		// Each read counts every row of the page, the other levels left out by their indentation (as Happyness)
		assertEquals(12, tree.getFamilies().getRead());
		assertEquals(3, tree.getFamilies().getLeftOut("indentation 0"));
		assertEquals(1, tree.getFamilies().getLeftOut("indentation 2"));
		assertEquals(1, tree.getFamilies().getLeftOut("indentation 3"));
		assertTrue(tree.getFamilies().getNotes().isEmpty(), "every famille has its catégorie");
	}

	@Test
	@DisplayName("Orphans: a sous-famille or a section whose parent is not of the level above is left out; a famille kept")
	void orphans() {
		ErpCategories tree = mapper.categories(Arrays.asList(new ZenCategoryRow("VET", "Vetements", "", 0, 1),
				new ZenCategoryRow("HOM", "Homme", "VET", 1, 2), new ZenCategoryRow("LOST", "Without", "", 1, 3),
				new ZenCategoryRow("CHEM", "Chemise", "HOM", 2, 4), new ZenCategoryRow("BAD", "Under a catégorie", "VET", 2, 5),
				new ZenCategoryRow("MANCHE", "Manche", "CHEM", 3, 6), new ZenCategoryRow("ODD", "Under a famille", "HOM", 3, 7),
				new ZenCategoryRow("", "No code", "", 1, 8), new ZenCategoryRow("DEEP", "Level 4", "MANCHE", 4, 9)));

		assertEquals(Arrays.asList("HOM:VET", "LOST:null"),
				codes(tree.getFamilies(), f -> f.getCode() + ":" + f.getCategoryExternalId()));
		assertEquals(1, tree.getFamilies().getNote(ZenMapper.FAMILY_WITHOUT_CATEGORY));
		assertEquals(1, tree.getFamilies().getLeftOut(ZenMapper.BLANK_CODE));
		assertEquals(Arrays.asList("CHEM"), codes(tree.getSubFamilies(), ErpItemSubFamilyDTO::getCode));
		assertEquals(1, tree.getSubFamilies().getLeftOut(ZenMapper.PARENT_NOT_FAMILY));
		assertEquals(Arrays.asList("MANCHE"), codes(tree.getSections(), ErpItemSectionDTO::getCode));
		assertEquals(1, tree.getSections().getLeftOut(ZenMapper.PARENT_NOT_SUB_FAMILY));
		assertEquals(1, tree.getCategories().getLeftOut("indentation 4"));
	}

	@Test
	@DisplayName("Barcodes: the item of the row with its variant; a blank barCode or itemNo, a date not readable left out;"
			+ " every position kept, NAV's empty date at 1")
	void barcodes() {
		long t1 = Instant.parse("2026-10-11T16:53:37.150Z").toEpochMilli();
		long t2 = Instant.parse("2026-10-11T16:53:38Z").toEpochMilli();
		ErpRead<ErpBarcodeRow> read = mapper.barcodeRows(Arrays.asList(
				new ZenBarcodeRow("a1", "70061", "001", "9000002740592", "2026-10-11T16:53:37.15Z"),
				new ZenBarcodeRow("a2", "1000", "", "6191234567890", "0001-01-01T00:00:00Z"),
				new ZenBarcodeRow("a3", "70061", "002", " ", "2026-10-11T17:53:38+01:00"),
				new ZenBarcodeRow("a4", "", "001", "9000000000000", null),
				new ZenBarcodeRow("a5", "70061", "003", "9000000000001", "yesterday")));

		assertEquals(Arrays.asList("70061~001", "1000"), read.getRows().stream()
				.map(row -> row.getBarcode().getItemExternalId()).collect(Collectors.toList()));
		assertEquals("9000002740592", read.getRows().get(0).getBarcode().getBarcode());
		assertEquals(Long.valueOf(t1), read.getRows().get(0).getPosition());
		assertEquals(Long.valueOf(ZenMapper.EMPTY_DATE_POSITION), read.getRows().get(1).getPosition());
		assertEquals(Arrays.asList(t1, 1L, t2, 1L, 1L), read.getPositions());
		assertEquals(Long.valueOf(t2), read.getHighestPosition());
		assertEquals(1, read.getLeftOut(ZenMapper.BLANK_BARCODE));
		assertEquals(1, read.getLeftOut(ZenMapper.BLANK_BARCODE_ITEM));
		assertEquals(1, read.getLeftOut(ZenMapper.UNREADABLE_DATE));
	}

	@Test
	@DisplayName("Step 12a: a barcode's position is its lastModifiedDateTime in epoch milliseconds, rounded down; 1"
			+ " without a date, before 1970 or not readable")
	void barcodePosition() {
		long at = Instant.parse("2026-10-11T16:53:37.150Z").toEpochMilli();
		assertEquals(at, ZenMapper.position("2026-10-11T16:53:37.15Z"));
		assertEquals(at, ZenMapper.position("2026-10-11T18:53:37.1509999+02:00"), "in UTC, below the millisecond cut");
		assertEquals(1, ZenMapper.position("0001-01-01T00:00:00Z"));
		assertEquals(1, ZenMapper.position(" "));
		assertEquals(1, ZenMapper.position(null));
		assertEquals(1, ZenMapper.position("1753-01-01T00:00:00Z"));
		assertEquals(1, ZenMapper.position("not a date"));
		assertEquals(253402300799999L, ZenMapper.position("9999-12-31T23:59:59.999Z"), "a long holds any date");
	}

	private static <T> List<String> codes(ErpRead<T> read, Function<T, String> code) {
		return read.getRows().stream().map(code).collect(Collectors.toList());
	}
}
