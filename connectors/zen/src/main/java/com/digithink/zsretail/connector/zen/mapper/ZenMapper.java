package com.digithink.zsretail.connector.zen.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.digithink.zsretail.connector.zen.dto.ZenBarcodeRow;
import com.digithink.zsretail.connector.zen.dto.ZenCategoryRow;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;
import com.digithink.zsretail.erp.api.ErpBarcodeRow;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;

/**
 * Item variants (ZEN), step 8: the rows of ZEN's API to the Erp*DTO classes, with the counts of each read. No Spring:
 * built with the default VAT.
 * <ul>
 * <li>Items (stockPoints, the rows of one location): one item of the ERP per item and variant, its code
 * {@link #itemCode} (itemNo~variantCode, itemNo alone without a variant). Name and description: itemDescription, never
 * the variant's. The 14 variant fields, blank as null, except the variant description: built here
 * ({@link #variantDescription}), NAV's variantDescription is not read (on ZEN's platform it is always the item
 * description). Brand: brandCode (blank: null, the head office keeps its own).
 * Family and sub-family by code. publicPrice is before VAT; a null or zero price makes the item inactive (noted, as
 * Happyness). active false makes it inactive; absent (the API has no such field yet) it is true. VAT: the row's vat
 * when it gives one (rounded to a whole number), else the default. lastModifiedAt: lastModifiedDateTime, none for NAV's
 * empty date 0001-01-01. The location's clearance (null: false) and clearance price. Left out: a blank itemNo, a code
 * with ~ in itemNo or variantCode (it could not be read back), a row of another location, the same item and variant
 * twice (the first kept).</li>
 * <li>Categories (itemCategories), by indentation: 0 catégorie, 1 famille, 2 sous-famille, 3 section; the parent is
 * parentCategory. A famille keeps its catégorie when the parent is a catégorie of the read, else none (noted). A
 * sous-famille whose parent is not a famille of the read, and a section whose parent is not a sous-famille of the
 * read, are left out (the import would never save them, and the items would wait for them for ever). A blank code and
 * another indentation are left out. Display order: presentationOrder. As Happyness, each read counts every row of the
 * page, the rows of the other levels as left out by their indentation.</li>
 * <li>Barcodes (step 12a, ZEN's page barCodes): the item is {@link #itemCode} of itemNo and variantCode; the position
 * {@link #position} of lastModifiedDateTime. A blank barCode or itemNo, and a date not readable, are left out; the
 * position of every row read is kept. unitOfMeasureCode is not read: every row is kept whatever its unit.</li>
 * </ul>
 */
public class ZenMapper {

	/** Between the item number and the variant code in an item code. */
	public static final String SEPARATOR = "~";

	/** Between the size, colour and choice descriptions in a variant description. */
	public static final String VARIANT_PARTS = " / ";

	public static final String BLANK_ITEM_NO = "blank itemNo";
	public static final String SEPARATOR_IN_CODE = "~ in itemNo or variantCode";
	public static final String OTHER_LOCATION = "row of another location";
	public static final String SAME_ROW = "same item and variant twice";
	public static final String ZERO_PRICE = "null or zero publicPrice";
	public static final String INACTIVE = "active false";
	public static final String VAT_NOT_WHOLE = "vat not a whole number (rounded)";
	public static final String NO_DATE = "no lastModifiedDateTime (0001-01-01)";
	public static final String BLANK_CODE = "blank code";
	public static final String FAMILY_WITHOUT_CATEGORY = "famille whose parent is not a catégorie";
	public static final String PARENT_NOT_FAMILY = "parent not a famille of the read";
	public static final String PARENT_NOT_SUB_FAMILY = "parent not a sous-famille of the read";
	public static final String BLANK_BARCODE = "blank barCode";
	public static final String BLANK_BARCODE_ITEM = "blank itemNo";
	public static final String UNREADABLE_DATE = "lastModifiedDateTime not readable";

	/**
	 * Step 12a: the position of a barcode row without a date: NAV's empty date 0001-01-01, a blank, a date before 1970
	 * or one not readable. Above 0, the cursor of nothing read yet (every row), so the cursor passes these rows too.
	 */
	public static final long EMPTY_DATE_POSITION = 1;

	static final int CATEGORY = 0;
	static final int FAMILY = 1;
	static final int SUB_FAMILY = 2;
	static final int SECTION = 3;

	private final int defaultVat;

	public ZenMapper(int defaultVat) {
		this.defaultVat = defaultVat;
	}

	/** itemNo~variantCode, itemNo alone when the variant code is blank (both trimmed). */
	public static String itemCode(String itemNo, String variantCode) {
		String item = trimmed(itemNo);
		String variant = trimmed(variantCode);
		return variant.isEmpty() ? item : item + SEPARATOR + variant;
	}

	/**
	 * The variant's description: the size, colour and choice descriptions, trimmed, the blank ones left out, in that
	 * order, joined with {@link #VARIANT_PARTS}; "" when all three are blank. db/3.1.0/update.sql builds the same from
	 * the item's columns.
	 */
	public static String variantDescription(String size, String color, String choice) {
		StringBuilder text = new StringBuilder();
		for (String part : new String[] { size, color, choice }) {
			String trimmed = trimmed(part);
			if (!trimmed.isEmpty()) {
				text.append(text.length() == 0 ? "" : VARIANT_PARTS).append(trimmed);
			}
		}
		return text.toString();
	}

	// ─── Items ──────────────────────────────────────────────────

	/** The rows of one location (locationCode null: any). */
	public ErpRead<ErpItemDTO> items(String locationCode, List<ZenStockPointRow> rows) {
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		Map<String, Integer> notes = new LinkedHashMap<>();
		List<ErpItemDTO> items = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		String location = trimmed(locationCode);
		for (ZenStockPointRow row : rows) {
			String itemNo = trimmed(row.getItemNo());
			if (itemNo.isEmpty()) {
				count(leftOut, BLANK_ITEM_NO);
				continue;
			}
			if (itemNo.contains(SEPARATOR) || trimmed(row.getVariantCode()).contains(SEPARATOR)) {
				count(leftOut, SEPARATOR_IN_CODE);
				continue;
			}
			String rowLocation = trimmed(row.getLocationCode());
			if (!location.isEmpty() && !rowLocation.isEmpty() && !rowLocation.equalsIgnoreCase(location)) {
				count(leftOut, OTHER_LOCATION);
				continue;
			}
			String code = itemCode(itemNo, row.getVariantCode());
			if (!seen.add(code)) {
				count(leftOut, SAME_ROW);
				continue;
			}
			items.add(item(code, itemNo, row, notes));
		}
		return new ErpRead<>(items, rows.size(), leftOut, notes, null);
	}

	private ErpItemDTO item(String code, String itemNo, ZenStockPointRow row, Map<String, Integer> notes) {
		ErpItemDTO item = new ErpItemDTO();
		item.setExternalId(code);
		item.setCode(code);
		item.setName(trimmed(row.getItemDescription()));
		item.setDescription(trimmed(row.getItemDescription()));
		item.setFamilyExternalId(blankToNull(row.getFamilyCode()));
		item.setSubFamilyExternalId(blankToNull(row.getSubFamilyCode()));
		item.setDefaultVAT(vat(row.getVat(), notes));
		BigDecimal price = row.getPublicPrice();
		boolean active = true;
		if (price == null || price.signum() == 0) {
			// As Happyness: an item without a price is not sold; active again when the ERP gives it a price
			count(notes, ZERO_PRICE);
			item.setUnitPrice(BigDecimal.ZERO);
			active = false;
		} else {
			item.setUnitPrice(price);
		}
		if (Boolean.FALSE.equals(row.getActive())) {
			count(notes, INACTIVE);
			active = false;
		}
		item.setActive(active);
		item.setLastModifiedAt(modifiedAt(row.getLastModifiedDateTime()));
		if (item.getLastModifiedAt() == null) {
			count(notes, NO_DATE);
		}
		item.setNavItemNo(itemNo);
		item.setVariantCode(blankToNull(row.getVariantCode()));
		item.setVariantDescription(
				variantDescription(row.getSizeDescription(), row.getColorDescription(), row.getChoiceDescription()));
		item.setSizeCode(blankToNull(row.getSizeCode()));
		item.setSizeDescription(blankToNull(row.getSizeDescription()));
		item.setSizeSortOrder(row.getSizeSortOrder());
		item.setColorCode(blankToNull(row.getColorCode()));
		item.setColorDescription(blankToNull(row.getColorDescription()));
		item.setChoiceCode(blankToNull(row.getChoiceCode()));
		item.setChoiceDescription(blankToNull(row.getChoiceDescription()));
		item.setSeasonCode(blankToNull(row.getSeasonCode()));
		item.setDivisionCode(blankToNull(row.getDivisionCode()));
		item.setCategoryCode(blankToNull(row.getCategoryCode()));
		item.setSectionCode(blankToNull(row.getSectionCode()));
		item.setBrand(blankToNull(row.getBrandCode()));
		item.setClearance(Boolean.TRUE.equals(row.getClearance()));
		item.setClearancePrice(row.getClearancePrice());
		return item;
	}

	/** The row's VAT rounded to a whole number (noted when it was not one), else the default. */
	private Integer vat(BigDecimal vat, Map<String, Integer> notes) {
		if (vat == null) {
			return defaultVat;
		}
		BigDecimal whole = vat.setScale(0, RoundingMode.HALF_UP);
		if (whole.compareTo(vat) != 0) {
			count(notes, VAT_NOT_WHOLE);
		}
		return whole.intValue();
	}

	/** ISO with its offset; null when blank, not readable, or NAV's empty date (year 1). */
	public static OffsetDateTime modifiedAt(String value) {
		String text = trimmed(value);
		if (text.isEmpty() || text.startsWith("0001-01-01")) {
			return null;
		}
		try {
			return OffsetDateTime.parse(text);
		} catch (DateTimeParseException e) {
			return null;
		}
	}

	// ─── Categories ─────────────────────────────────────────────

	/** The four levels from one read of the tree. */
	public ErpCategories categories(List<ZenCategoryRow> rows) {
		Map<String, Integer> levels = new HashMap<>();
		for (ZenCategoryRow row : rows) {
			Integer level = row.getIndentation();
			if (!trimmed(row.getCode()).isEmpty() && level != null && level >= CATEGORY && level <= SECTION) {
				levels.putIfAbsent(trimmed(row.getCode()), level);
			}
		}
		List<ErpItemCategoryDTO> categories = new ArrayList<>();
		List<ErpItemFamilyDTO> families = new ArrayList<>();
		List<ErpItemSubFamilyDTO> subFamilies = new ArrayList<>();
		List<ErpItemSectionDTO> sections = new ArrayList<>();
		List<Map<String, Integer>> leftOut = new ArrayList<>();
		List<Map<String, Integer>> notes = new ArrayList<>();
		for (int level = CATEGORY; level <= SECTION; level++) {
			leftOut.add(new LinkedHashMap<>());
			notes.add(new LinkedHashMap<>());
		}
		for (ZenCategoryRow row : rows) {
			String code = trimmed(row.getCode());
			Integer level = row.getIndentation();
			for (int read = CATEGORY; read <= SECTION; read++) {
				if (level == null || level != read) {
					count(leftOut.get(read), "indentation " + level);
				} else if (code.isEmpty()) {
					count(leftOut.get(read), BLANK_CODE);
				}
			}
			if (code.isEmpty() || level == null) {
				continue;
			}
			String parent = trimmed(row.getParentCategory());
			Integer parentLevel = levels.get(parent);
			switch (level) {
				case CATEGORY: {
					ErpItemCategoryDTO category = new ErpItemCategoryDTO();
					category.setExternalId(code);
					category.setCode(code);
					category.setName(trimmed(row.getDescription()));
					category.setDescription(trimmed(row.getDescription()));
					category.setDisplayOrder(row.getPresentationOrder());
					category.setActive(Boolean.TRUE);
					categories.add(category);
					break;
				}
				case FAMILY: {
					ErpItemFamilyDTO family = new ErpItemFamilyDTO();
					family.setExternalId(code);
					family.setCode(code);
					family.setName(trimmed(row.getDescription()));
					family.setDescription(trimmed(row.getDescription()));
					family.setDisplayOrder(row.getPresentationOrder());
					family.setActive(Boolean.TRUE);
					if (parentLevel != null && parentLevel == CATEGORY) {
						family.setCategoryExternalId(parent);
					} else {
						count(notes.get(FAMILY), FAMILY_WITHOUT_CATEGORY);
					}
					families.add(family);
					break;
				}
				case SUB_FAMILY: {
					if (parentLevel == null || parentLevel != FAMILY) {
						count(leftOut.get(SUB_FAMILY), PARENT_NOT_FAMILY);
						break;
					}
					ErpItemSubFamilyDTO subFamily = new ErpItemSubFamilyDTO();
					subFamily.setExternalId(code);
					subFamily.setCode(code);
					subFamily.setName(trimmed(row.getDescription()));
					subFamily.setDescription(trimmed(row.getDescription()));
					subFamily.setDisplayOrder(row.getPresentationOrder());
					subFamily.setActive(Boolean.TRUE);
					subFamily.setFamilyExternalId(parent);
					subFamilies.add(subFamily);
					break;
				}
				case SECTION: {
					if (parentLevel == null || parentLevel != SUB_FAMILY) {
						count(leftOut.get(SECTION), PARENT_NOT_SUB_FAMILY);
						break;
					}
					ErpItemSectionDTO section = new ErpItemSectionDTO();
					section.setExternalId(code);
					section.setCode(code);
					section.setName(trimmed(row.getDescription()));
					section.setDescription(trimmed(row.getDescription()));
					section.setDisplayOrder(row.getPresentationOrder());
					section.setActive(Boolean.TRUE);
					section.setSubFamilyExternalId(parent);
					sections.add(section);
					break;
				}
				default:
					break; // another indentation: counted above
			}
		}
		return new ErpCategories(new ErpRead<>(categories, rows.size(), leftOut.get(CATEGORY), notes.get(CATEGORY), null),
				new ErpRead<>(families, rows.size(), leftOut.get(FAMILY), notes.get(FAMILY), null),
				new ErpRead<>(subFamilies, rows.size(), leftOut.get(SUB_FAMILY), notes.get(SUB_FAMILY), null),
				new ErpRead<>(sections, rows.size(), leftOut.get(SECTION), notes.get(SECTION), null));
	}

	// ─── Barcodes ───────────────────────────────────────────────

	/**
	 * The rows of a barcode read, one by one, for the catalogue engine (its cursor, the highest position of the same
	 * barcode, the rows already at the head office): a blank barCode or itemNo, or a date not readable, left out; every
	 * other row kept with its {@link #position} and its item code built with the variant. The positions of every row
	 * read are kept.
	 */
	public ErpRead<ErpBarcodeRow> barcodeRows(List<ZenBarcodeRow> rows) {
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		List<ErpBarcodeRow> kept = new ArrayList<>();
		List<Long> positions = new ArrayList<>();
		for (ZenBarcodeRow row : rows) {
			long position = position(row.getLastModifiedDateTime());
			positions.add(position);
			String barcode = trimmed(row.getBarCode());
			if (barcode.isEmpty()) {
				count(leftOut, BLANK_BARCODE);
			} else if (trimmed(row.getItemNo()).isEmpty()) {
				count(leftOut, BLANK_BARCODE_ITEM);
			} else if (!readableDate(row.getLastModifiedDateTime())) {
				count(leftOut, UNREADABLE_DATE);
			} else {
				ErpItemBarcodeDTO dto = new ErpItemBarcodeDTO();
				dto.setExternalId(barcode);
				dto.setBarcode(barcode);
				dto.setItemExternalId(itemCode(row.getItemNo(), row.getVariantCode()));
				kept.add(new ErpBarcodeRow(dto, position));
			}
		}
		return new ErpRead<>(kept, rows.size(), leftOut, positions);
	}

	/**
	 * Step 12a: the position of a barcode row for the engine's cursor, its lastModifiedDateTime in epoch milliseconds
	 * (the instant rounded down to the millisecond: the rows of one millisecond share a position);
	 * {@link #EMPTY_DATE_POSITION} for NAV's empty date, a blank, a date before 1970 or one not readable.
	 */
	public static long position(String lastModifiedDateTime) {
		OffsetDateTime at = modifiedAt(lastModifiedDateTime);
		return at == null ? EMPTY_DATE_POSITION : Math.max(EMPTY_DATE_POSITION, at.toInstant().toEpochMilli());
	}

	/** Blank, NAV's empty date, or an ISO date with its offset. */
	private static boolean readableDate(String value) {
		String text = trimmed(value);
		return text.isEmpty() || text.startsWith("0001-01-01") || modifiedAt(text) != null;
	}

	// ─── Helpers ────────────────────────────────────────────────

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}

	private static String blankToNull(String value) {
		String trimmed = trimmed(value);
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static void count(Map<String, Integer> counts, String reason) {
		counts.merge(reason, 1, Integer::sum);
	}
}
