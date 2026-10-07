package com.digithink.zsretail.erp.navpospages.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.digithink.zsretail.erp.dto.ErpItemBarcodeDTO;
import com.digithink.zsretail.erp.dto.ErpItemDTO;
import com.digithink.zsretail.erp.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.dto.ErpItemSubFamilyDTO;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;

/**
 * ERP catalogue, step 5: the rows of the "POS pages" to the existing Erp*DTO classes, with the counts of each read. No
 * Spring: built with the VAT settings.
 * <ul>
 * <li>Categories: Type trimmed, any case. Family rows give the families; Subfamily rows the sub-families, whose family is
 * Parent_Category, left out when that parent is not a Family row of the same read; any other Type (Categorie) is
 * ignored; a blank Code is left out.</li>
 * <li>Items: one item per Item_No, the row with a blank Variant_Code first, else the lowest Variant_Code; the VAT is
 * the configured one; with price-includes-vat the price is brought back before VAT (10 decimals, HALF_UP). A null or
 * zero price is kept as 0 and noted.</li>
 * <li>Barcodes: the same barcode twice keeps the highest Entry_No.</li>
 * </ul>
 */
public class NavPosPagesMapper {

	public static final String BLANK_CODE = "blank Code";
	public static final String FAMILY_NOT_IN_READ = "family not in the read";
	public static final String BLANK_ITEM_NO = "blank Item_No";
	public static final String VARIANT_ROW = "variant row";
	public static final String ZERO_PRICE = "null or zero price";
	public static final String BLANK_BARCODE = "blank Cross_Reference_No";
	public static final String SAME_BARCODE = "same barcode, lower Entry_No";

	static final String FAMILY = "family";
	static final String SUBFAMILY = "subfamily";
	static final int PRICE_SCALE = 10;

	private final int defaultVat;
	private final boolean priceIncludesVat;

	public NavPosPagesMapper(int defaultVat, boolean priceIncludesVat) {
		this.defaultVat = defaultVat;
		this.priceIncludesVat = priceIncludesVat;
	}

	// ─── Categories ─────────────────────────────────────────────

	public NavPosResult<ErpItemFamilyDTO> families(List<NavPosCategoryRow> rows) {
		List<ErpItemFamilyDTO> families = new ArrayList<>();
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		for (NavPosCategoryRow row : rows) {
			String type = type(row);
			if (!FAMILY.equals(type)) {
				count(leftOut, "type " + trimmed(row.getType()));
			} else if (trimmed(row.getCode()).isEmpty()) {
				count(leftOut, BLANK_CODE);
			} else {
				ErpItemFamilyDTO family = new ErpItemFamilyDTO();
				family.setExternalId(trimmed(row.getCode()));
				family.setCode(trimmed(row.getCode()));
				family.setName(trimmed(row.getDescription()));
				family.setDescription(trimmed(row.getDescription()));
				family.setActive(Boolean.TRUE);
				families.add(family);
			}
		}
		return new NavPosResult<>(families, rows.size(), leftOut, new LinkedHashMap<>(), null);
	}

	public NavPosResult<ErpItemSubFamilyDTO> subFamilies(List<NavPosCategoryRow> rows) {
		Set<String> familyCodes = new HashSet<>();
		for (NavPosCategoryRow row : rows) {
			if (FAMILY.equals(type(row)) && !trimmed(row.getCode()).isEmpty()) {
				familyCodes.add(trimmed(row.getCode()));
			}
		}
		List<ErpItemSubFamilyDTO> subFamilies = new ArrayList<>();
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		for (NavPosCategoryRow row : rows) {
			String type = type(row);
			if (!SUBFAMILY.equals(type)) {
				count(leftOut, "type " + trimmed(row.getType()));
			} else if (trimmed(row.getCode()).isEmpty()) {
				count(leftOut, BLANK_CODE);
			} else if (!familyCodes.contains(trimmed(row.getParentCategory()))) {
				count(leftOut, FAMILY_NOT_IN_READ);
			} else {
				ErpItemSubFamilyDTO subFamily = new ErpItemSubFamilyDTO();
				subFamily.setExternalId(trimmed(row.getCode()));
				subFamily.setCode(trimmed(row.getCode()));
				subFamily.setName(trimmed(row.getDescription()));
				subFamily.setDescription(trimmed(row.getDescription()));
				subFamily.setFamilyExternalId(trimmed(row.getParentCategory()));
				subFamily.setActive(Boolean.TRUE);
				subFamilies.add(subFamily);
			}
		}
		return new NavPosResult<>(subFamilies, rows.size(), leftOut, new LinkedHashMap<>(), null);
	}

	// ─── Items ──────────────────────────────────────────────────

	public NavPosResult<ErpItemDTO> items(List<NavPosStockRow> rows) {
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		Map<String, Integer> notes = new LinkedHashMap<>();
		// One row per Item_No, in the order of the first row read
		Map<String, NavPosStockRow> chosen = new LinkedHashMap<>();
		for (NavPosStockRow row : rows) {
			String itemNo = trimmed(row.getItemNo());
			if (itemNo.isEmpty()) {
				count(leftOut, BLANK_ITEM_NO);
				continue;
			}
			NavPosStockRow current = chosen.get(itemNo);
			if (current == null) {
				chosen.put(itemNo, row);
			} else {
				count(leftOut, VARIANT_ROW);
				if (variantComesFirst(row, current)) {
					chosen.put(itemNo, row);
				}
			}
		}
		List<ErpItemDTO> items = new ArrayList<>();
		for (Map.Entry<String, NavPosStockRow> entry : chosen.entrySet()) {
			NavPosStockRow row = entry.getValue();
			ErpItemDTO item = new ErpItemDTO();
			item.setExternalId(entry.getKey());
			item.setCode(entry.getKey());
			item.setName(trimmed(row.getDescription()));
			item.setDescription(trimmed(row.getDescription()));
			item.setFamilyExternalId(blankToNull(row.getFamily()));
			item.setSubFamilyExternalId(blankToNull(row.getSubfamily()));
			item.setDefaultVAT(defaultVat);
			item.setActive(Boolean.TRUE);
			BigDecimal price = row.getUnitPrice();
			if (price == null || price.signum() == 0) {
				count(notes, ZERO_PRICE);
				item.setUnitPrice(BigDecimal.ZERO);
			} else {
				item.setUnitPrice(priceBeforeVat(price));
			}
			items.add(item);
		}
		return new NavPosResult<>(items, rows.size(), leftOut, notes, null);
	}

	/** The price of the page before VAT: divided by (1 + VAT/100), 10 decimals, HALF_UP, when it includes the VAT. */
	public BigDecimal priceBeforeVat(BigDecimal unitPrice) {
		if (!priceIncludesVat) {
			return unitPrice;
		}
		BigDecimal rate = BigDecimal.ONE.add(BigDecimal.valueOf(defaultVat).movePointLeft(2));
		return unitPrice.divide(rate, PRICE_SCALE, RoundingMode.HALF_UP);
	}

	/** True when candidate is the row to keep rather than current: a blank variant first, then the lowest code. */
	private static boolean variantComesFirst(NavPosStockRow candidate, NavPosStockRow current) {
		String a = trimmed(candidate.getVariantCode());
		String b = trimmed(current.getVariantCode());
		if (b.isEmpty()) {
			return false;
		}
		return a.isEmpty() || a.compareTo(b) < 0;
	}

	// ─── Barcodes ───────────────────────────────────────────────

	public NavPosResult<ErpItemBarcodeDTO> barcodes(List<NavPosBarcodeRow> rows) {
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		Map<String, NavPosBarcodeRow> chosen = new LinkedHashMap<>();
		Long highest = null;
		for (NavPosBarcodeRow row : rows) {
			if (row.getEntryNo() != null && (highest == null || row.getEntryNo() > highest)) {
				highest = row.getEntryNo();
			}
			String barcode = trimmed(row.getCrossReferenceNo());
			if (barcode.isEmpty()) {
				count(leftOut, BLANK_BARCODE);
				continue;
			}
			if (trimmed(row.getItemNo()).isEmpty()) {
				count(leftOut, BLANK_ITEM_NO);
				continue;
			}
			NavPosBarcodeRow current = chosen.get(barcode);
			if (current == null) {
				chosen.put(barcode, row);
			} else {
				count(leftOut, SAME_BARCODE);
				if (entryNo(row) > entryNo(current)) {
					chosen.put(barcode, row);
				}
			}
		}
		List<ErpItemBarcodeDTO> barcodes = new ArrayList<>();
		for (Map.Entry<String, NavPosBarcodeRow> entry : chosen.entrySet()) {
			ErpItemBarcodeDTO barcode = new ErpItemBarcodeDTO();
			barcode.setExternalId(entry.getKey());
			barcode.setBarcode(entry.getKey());
			barcode.setItemExternalId(trimmed(entry.getValue().getItemNo()));
			barcodes.add(barcode);
		}
		return new NavPosResult<>(barcodes, rows.size(), leftOut, new LinkedHashMap<>(), highest);
	}

	private static long entryNo(NavPosBarcodeRow row) {
		return row.getEntryNo() == null ? Long.MIN_VALUE : row.getEntryNo();
	}

	// ─── Helpers ────────────────────────────────────────────────

	private static String type(NavPosCategoryRow row) {
		return trimmed(row.getType()).toLowerCase(Locale.ROOT);
	}

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
