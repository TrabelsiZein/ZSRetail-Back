package com.digithink.zsretail.connector.zen.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Item variants (ZEN), step 8: the mapper on the two samples of ZEN's API (src/test/resources/zen: NewstockPointS.json
 * as received on 2026-10-24, the page with the prices before VAT, vatPercent and the blocked flags; itemCategories.json
 * of 2026-10-14) and on rows written here for what the samples do not have (several locations, a price 0, a blocked
 * flag, no vatPercent, prices that do not agree, a blank variant, orphans of the tree).
 */
class ZenMapperTest {

	private static final ObjectMapper JSON = new ObjectMapper();
	private static List<ZenStockPointRow> stockPoints;
	private static List<ZenCategoryRow> categories;

	private final ZenMapper mapper = new ZenMapper(19);

	@BeforeAll
	static void samples() throws IOException {
		stockPoints = sample("NewstockPointS.json", new TypeReference<ZenCollection<ZenStockPointRow>>() {
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
	@DisplayName("Sample, FRANCH-01: its 100 rows active, the price before VAT from unitPrice, the clearance price from"
			+ " clearancePriceExclVAT, vatPercent, the fields of 14ZDU05~001; publicPrice and clearancePrice agree")
	void sampleOfOneLocation() {
		ErpRead<ErpItemDTO> read = mapper.items("FRANCH-01", stockPoints);

		assertEquals(100, read.getRead());
		assertEquals(100, read.getKept());
		assertEquals(100, read.getRows().stream().filter(ErpItemDTO::getActive).count());
		assertEquals(100, read.getRows().stream().filter(ErpItemDTO::getClearance).count());
		assertTrue(read.getNotes().isEmpty(), "no price 0, no block, every date, the prices with VAT agree: " + read.getNotes());
		ErpItemDTO tie = byCode(read).get("14ZDU05~001");
		assertEquals("14ZDU05~001", tie.getExternalId());
		assertTrue(tie.getActive());
		assertEquals(0, new BigDecimal("16.38655").compareTo(tie.getUnitPrice()), "unitPrice, never publicPrice 19.5");
		assertEquals(0, new BigDecimal("5.88235").compareTo(tie.getClearancePrice()),
				"clearancePriceExclVAT, never clearancePrice 7");
		assertTrue(tie.getClearance());
		assertEquals(19, tie.getDefaultVAT(), "vatPercent");
		assertEquals("CRAVATE HM PETIT CARREAUX SATINE", tie.getName(), "itemDescription, never the variant's");
		assertEquals("CRAVATE HM PETIT CARREAUX SATINE", tie.getDescription());
		assertEquals("14ZDU05", tie.getNavItemNo());
		assertEquals("001", tie.getVariantCode());
		assertEquals("Taille Standard / VERT PISTACHE / 1er Choix", tie.getVariantDescription(),
				"built from the descriptions; NAV's variantDescription (the item description) not read");
		assertEquals("STD", tie.getSizeCode());
		assertEquals("Taille Standard", tie.getSizeDescription());
		assertEquals(60, tie.getSizeSortOrder());
		assertEquals("07072", tie.getColorCode());
		assertEquals("VERT PISTACHE", tie.getColorDescription());
		assertEquals("01", tie.getChoiceCode());
		assertEquals("1er Choix", tie.getChoiceDescription());
		assertEquals("ANC", tie.getSeasonCode());
		assertEquals("DSZ", tie.getDivisionCode());
		assertEquals("ZEN", tie.getBrand());
		assertNull(tie.getCategoryCode(), "blank: null");
		assertNull(tie.getSectionCode());
		assertNull(tie.getFamilyExternalId());
		assertNull(tie.getSubFamilyExternalId());
		assertEquals(OffsetDateTime.parse("2026-10-23T21:42:08.763Z"), tie.getLastModifiedAt());
		// The till sells unitPrice × (1 + VAT / 100): 16.38655 × 1.19 = 19.500 at 3 decimals, ZEN's publicPrice
		assertEquals(0, new BigDecimal("19.500").compareTo(
				tie.getUnitPrice().multiply(new BigDecimal("1.19")).setScale(3, RoundingMode.HALF_UP)));
		// Another location: every row of the sample left out
		ErpRead<ErpItemDTO> other = mapper.items("ARGENTE", stockPoints);
		assertEquals(0, other.getKept());
		assertEquals(100, other.getLeftOut(ZenMapper.OTHER_LOCATION));
	}

	@Test
	@DisplayName("Several locations: the rows of the location read, the others left out; no location: every row")
	void severalLocations() throws IOException {
		List<ZenStockPointRow> rows = Arrays.asList(
				row("{`locationCode`:`ARGENTE`,`itemNo`:`70061`,`variantCode`:`001`,`itemDescription`:`CHEMISE`,`unitPrice`:10}"),
				row("{`locationCode`:`FRANCHISE`,`itemNo`:`70061`,`variantCode`:`002`,`itemDescription`:`CHEMISE`,"
						+ "`unitPrice`:12}"),
				row("{`locationCode`:` franchise `,`itemNo`:`70061`,`variantCode`:`003`,`itemDescription`:`CHEMISE`,"
						+ "`unitPrice`:12}"));

		ErpRead<ErpItemDTO> argente = mapper.items("ARGENTE", rows);
		ErpRead<ErpItemDTO> franchise = mapper.items("FRANCHISE", rows);

		assertEquals(Arrays.asList("70061~001"),
				argente.getRows().stream().map(ErpItemDTO::getCode).collect(Collectors.toList()));
		assertEquals(2, argente.getLeftOut(ZenMapper.OTHER_LOCATION));
		assertEquals(Arrays.asList("70061~002", "70061~003"),
				franchise.getRows().stream().map(ErpItemDTO::getCode).collect(Collectors.toList()), "trimmed, any case");
		assertEquals(1, franchise.getLeftOut(ZenMapper.OTHER_LOCATION));
		assertEquals(3, mapper.items(null, rows).getKept());
	}

	@Test
	@DisplayName("Variant description: size / colour / choice descriptions, trimmed, the blank ones left out, \"\" when"
			+ " none; NAV's variantDescription never read")
	void variantDescription() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items(null, Arrays.asList(
				row("{`itemNo`:`14ZDU05`,`variantCode`:`001`,`itemDescription`:`CRAVATE HM PETIT CARREAUX SATINE`,"
						+ "`variantDescription`:`CRAVATE HM PETIT CARREAUX SATINE`,`sizeDescription`:` Taille Standard `,"
						+ "`colorDescription`:`VERT PISTACHE`,`choiceDescription`:`1er Choix`,`unitPrice`:16.38655}"),
				row("{`itemNo`:`A`,`variantCode`:`1`,`itemDescription`:`A`,`sizeDescription`:`S`,"
						+ "`colorDescription`:`  `,`choiceDescription`:`1er Choix`,`unitPrice`:1}"),
				row("{`itemNo`:`B`,`variantCode`:`1`,`itemDescription`:`B`,`variantDescription`:`B`,"
						+ "`sizeDescription`:``,`choiceDescription`:null,`unitPrice`:1}")));

		Map<String, ErpItemDTO> items = byCode(read);
		ErpItemDTO tie = items.get("14ZDU05~001");
		assertEquals("Taille Standard / VERT PISTACHE / 1er Choix", tie.getVariantDescription());
		assertEquals("CRAVATE HM PETIT CARREAUX SATINE", tie.getName(), "the item description, untouched");
		assertEquals("S / 1er Choix", items.get("A~1").getVariantDescription(), "no double separator");
		assertEquals("", items.get("B~1").getVariantDescription(), "all blank: \"\", not null, not NAV's");
		assertEquals("", ZenMapper.variantDescription(null, null, null));
		assertEquals("Noir", ZenMapper.variantDescription("", "Noir", " "));
		assertEquals("S", ZenMapper.variantDescription("S", null, ""), "no trailing separator");
		// A colour description changed at ZEN changes the variant description (the engine compares it)
		ErpItemDTO recoloured = mapper.items(null, Collections.singletonList(
				row("{`itemNo`:`14ZDU05`,`variantCode`:`001`,`itemDescription`:`CRAVATE`,`sizeDescription`:`Taille Standard`,"
						+ "`colorDescription`:`VERT D'EAU`,`choiceDescription`:`1er Choix`,`unitPrice`:16.38655}")))
				.getRows().get(0);
		assertEquals("Taille Standard / VERT D'EAU / 1er Choix", recoloured.getVariantDescription());
	}

	@Test
	@DisplayName("A blank variant code: the item number alone, no variant code")
	void blankVariantCode() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items("BLEU", Collections.singletonList(
				row("{`locationCode`:`BLEU`,`itemNo`:`1000`,`variantCode`:``,`itemDescription`:`Bicyclette`,`unitPrice`:100}")));

		ErpItemDTO bike = read.getRows().get(0);
		assertEquals("1000", bike.getCode());
		assertEquals("1000", bike.getExternalId());
		assertEquals("1000", bike.getNavItemNo());
		assertNull(bike.getVariantCode());
	}

	@Test
	@DisplayName("vatPercent: read; absent, the default VAT; not a whole number, rounded and noted")
	void vatPercent() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items(null, Arrays.asList(
				row("{`itemNo`:`A`,`variantCode`:`1`,`itemDescription`:`A`,`unitPrice`:10}"),
				row("{`itemNo`:`B`,`variantCode`:`1`,`itemDescription`:`B`,`unitPrice`:10,`vatPercent`:7}"),
				row("{`itemNo`:`C`,`variantCode`:`1`,`itemDescription`:`C`,`unitPrice`:10,`vatPercent`:12.5}")));

		Map<String, ErpItemDTO> items = byCode(read);
		assertEquals(19, items.get("A~1").getDefaultVAT(), "vatPercent absent: the default");
		assertEquals(7, items.get("B~1").getDefaultVAT());
		assertEquals(13, items.get("C~1").getDefaultVAT());
		assertEquals(1, read.getNote(ZenMapper.VAT_NOT_WHOLE));
		assertTrue(items.values().stream().allMatch(ErpItemDTO::getActive));
	}

	@Test
	@DisplayName("Blocked: itemBlocked, variantBlocked or stockPointBlocked true alone makes the row inactive, each with"
			+ " its note; false or absent: active")
	void blockedFlags() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items(null, Arrays.asList(
				row("{`itemNo`:`A`,`variantCode`:`1`,`unitPrice`:10,`itemBlocked`:true,`variantBlocked`:false,"
						+ "`stockPointBlocked`:false}"),
				row("{`itemNo`:`B`,`variantCode`:`1`,`unitPrice`:10,`itemBlocked`:false,`variantBlocked`:true,"
						+ "`stockPointBlocked`:false}"),
				row("{`itemNo`:`C`,`variantCode`:`1`,`unitPrice`:10,`itemBlocked`:false,`variantBlocked`:false,"
						+ "`stockPointBlocked`:true}"),
				row("{`itemNo`:`D`,`variantCode`:`1`,`unitPrice`:10,`itemBlocked`:false,`variantBlocked`:false,"
						+ "`stockPointBlocked`:false}"),
				row("{`itemNo`:`E`,`variantCode`:`1`,`unitPrice`:10}"),
				row("{`itemNo`:`F`,`variantCode`:`1`,`unitPrice`:0,`itemBlocked`:true,`variantBlocked`:true,"
						+ "`stockPointBlocked`:true}")));

		Map<String, ErpItemDTO> items = byCode(read);
		assertFalse(items.get("A~1").getActive(), "itemBlocked");
		assertFalse(items.get("B~1").getActive(), "variantBlocked");
		assertFalse(items.get("C~1").getActive(), "stockPointBlocked");
		assertTrue(items.get("D~1").getActive(), "all false");
		assertTrue(items.get("E~1").getActive(), "absent: not blocked");
		assertFalse(items.get("F~1").getActive());
		// F: every reason counted, so the job report says each
		assertEquals(2, read.getNote(ZenMapper.ITEM_BLOCKED));
		assertEquals(2, read.getNote(ZenMapper.VARIANT_BLOCKED));
		assertEquals(2, read.getNote(ZenMapper.STOCK_POINT_BLOCKED));
		assertEquals(1, read.getNote(ZenMapper.ZERO_PRICE));
		assertEquals(0, new BigDecimal("10").compareTo(items.get("A~1").getUnitPrice()), "a blocked row keeps its price");
	}

	@Test
	@DisplayName("Price 0 or none: inactive, at 0, never publicPrice; clearance absent: false, no clearance price;"
			+ " clearancePrice alone (with VAT): no clearance price")
	void zeroPriceAndClearance() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items(null, Arrays.asList(
				row("{`itemNo`:`A`,`variantCode`:`1`,`itemDescription`:`A`,`unitPrice`:0,`publicPrice`:0}"),
				row("{`itemNo`:`B`,`variantCode`:`1`,`itemDescription`:`B`}"),
				row("{`itemNo`:`C`,`variantCode`:`1`,`itemDescription`:`C`,`unitPrice`:0,`publicPrice`:19.5}"),
				row("{`itemNo`:`D`,`variantCode`:`1`,`itemDescription`:`D`,`unitPrice`:10,`clearance`:true,"
						+ "`clearancePrice`:7}")));

		Map<String, ErpItemDTO> items = byCode(read);
		assertFalse(items.get("A~1").getActive(), "price 0");
		assertEquals(0, BigDecimal.ZERO.compareTo(items.get("A~1").getUnitPrice()));
		assertFalse(items.get("B~1").getActive(), "no price");
		assertEquals(0, BigDecimal.ZERO.compareTo(items.get("B~1").getUnitPrice()));
		assertFalse(items.get("C~1").getActive(), "unitPrice 0: publicPrice is never the price");
		assertEquals(0, BigDecimal.ZERO.compareTo(items.get("C~1").getUnitPrice()));
		assertEquals(3, read.getNote(ZenMapper.ZERO_PRICE));
		assertFalse(items.get("B~1").getClearance(), "clearance absent: false");
		assertNull(items.get("B~1").getClearancePrice());
		assertNull(items.get("B~1").getLastModifiedAt());
		assertTrue(items.get("D~1").getClearance());
		assertNull(items.get("D~1").getClearancePrice(), "clearancePrice is with VAT: never read as the price");
		assertEquals(0, read.getNote(ZenMapper.PUBLIC_PRICE_INCONSISTENT), "a price 0 is not checked");
	}

	@Test
	@DisplayName("A check only: publicPrice not unitPrice × (1 + VAT / 100) at 3 decimals noted, and clearancePrice"
			+ " against clearancePriceExclVAT; the prices kept as read")
	void pricesWithVatChecked() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items(null, Arrays.asList(
				// Agree (sample values: 16.38655 × 1.19 = 19.4999945, 5.88235 × 1.19 = 6.9999965)
				row("{`itemNo`:`A`,`variantCode`:`1`,`unitPrice`:16.38655,`publicPrice`:19.5,`vatPercent`:19,"
						+ "`clearance`:true,`clearancePriceExclVAT`:5.88235,`clearancePrice`:7}"),
				// The row's VAT, not the default: 10 × 1.07 = 10.7
				row("{`itemNo`:`B`,`variantCode`:`1`,`unitPrice`:10,`publicPrice`:10.7,`vatPercent`:7}"),
				// Do not agree
				row("{`itemNo`:`C`,`variantCode`:`1`,`unitPrice`:16.38655,`publicPrice`:19.51,`vatPercent`:19,"
						+ "`clearance`:true,`clearancePriceExclVAT`:5.88235,`clearancePrice`:7.5}"),
				row("{`itemNo`:`D`,`variantCode`:`1`,`unitPrice`:10,`publicPrice`:10,"
						+ "`clearancePriceExclVAT`:0,`clearancePrice`:0}"),
				// No price with VAT: nothing to check
				row("{`itemNo`:`E`,`variantCode`:`1`,`unitPrice`:10,`clearancePriceExclVAT`:5}")));

		assertEquals(2, read.getNote(ZenMapper.PUBLIC_PRICE_INCONSISTENT), "C, and D (10 × 1.19 is 11.9)");
		assertEquals(1, read.getNote(ZenMapper.CLEARANCE_PRICE_INCONSISTENT), "C");
		Map<String, ErpItemDTO> items = byCode(read);
		assertTrue(items.get("C~1").getActive(), "a check only: the row stays active");
		assertEquals(0, new BigDecimal("16.38655").compareTo(items.get("C~1").getUnitPrice()));
		assertEquals(0, new BigDecimal("5.88235").compareTo(items.get("C~1").getClearancePrice()));
		assertEquals(0, new BigDecimal("5").compareTo(items.get("E~1").getClearancePrice()));
	}

	@Test
	@DisplayName("Left out: a blank itemNo, ~ in a code, the same item and variant twice (the first kept)")
	void leftOut() throws IOException {
		ErpRead<ErpItemDTO> read = mapper.items(null, Arrays.asList(
				row("{`itemNo`:` `,`variantCode`:`1`,`unitPrice`:1}"),
				row("{`itemNo`:`A~B`,`variantCode`:`1`,`unitPrice`:1}"),
				row("{`itemNo`:`A`,`variantCode`:`1`,`itemDescription`:`first`,`unitPrice`:1}"),
				row("{`itemNo`:`A`,`variantCode`:`1`,`itemDescription`:`second`,`unitPrice`:1}")));

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
