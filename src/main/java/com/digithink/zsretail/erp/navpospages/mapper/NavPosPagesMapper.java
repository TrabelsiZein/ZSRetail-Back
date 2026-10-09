package com.digithink.zsretail.erp.navpospages.mapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
import com.digithink.zsretail.erp.dto.ErpSupplyInvoiceDTO;
import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceLineRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;
import com.digithink.zsretail.utils.Quantities;

/**
 * ERP catalogue, step 5: the rows of the "POS pages" to the existing Erp*DTO classes, with the counts of each read. No
 * Spring: built with the VAT settings.
 * <ul>
 * <li>Categories: Type trimmed, any case. Family rows give the families; Subfamily rows the sub-families, whose family is
 * Parent_Category, left out when that parent is not a Family row of the same read; any other Type (Categorie) is
 * ignored; a blank Code is left out.</li>
 * <li>Items: one item per Item_No, the row with a blank Variant_Code first, else the lowest Variant_Code; the VAT is
 * the configured one; with price-includes-vat the price is brought back before VAT (10 decimals, HALF_UP). A null or
 * zero price is kept as 0, noted, and the item is inactive (step 6).</li>
 * <li>Barcodes: the same barcode twice keeps the highest Entry_No.</li>
 * <li>Invoices (invoices from the ERP, step a): see {@link #invoices}.</li>
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
	public static final String BLANK_NUMBER = "blank No";
	public static final String COMMENT_LINE = "line without type (comment)";
	public static final String ZERO_OTHER_LINE = "line of another type with amount 0";
	public static final String WITH_WARNINGS = "invoice with warnings";
	public static final String NO_LINE = "no line: no totals";
	public static final String PRICES_INCLUDE_VAT = "the prices include the VAT (Prices_Including_VAT): Line_Amount"
			+ " includes it";
	public static final String NOT_FRANCHISE = "Client_Franchise is false";

	/** Lines and Total_Amount_Excl_VAT may differ by this much (rounding of the ERP). */
	public static final BigDecimal TOTAL_TOLERANCE = new BigDecimal("0.005");
	static final String ITEM_TYPE = "Item";
	/** The blank option as some OData versions write it. */
	static final String BLANK_TYPE = "_x0020_";

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
			BigDecimal price = row.getUnitPrice();
			if (price == null || price.signum() == 0) {
				// Step 6: an item without a price is not sold: inactive, active again when the ERP gives it a price
				count(notes, ZERO_PRICE);
				item.setUnitPrice(BigDecimal.ZERO);
				item.setActive(Boolean.FALSE);
			} else {
				item.setUnitPrice(priceBeforeVat(price));
				item.setActive(Boolean.TRUE);
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

	// ─── Invoices (invoices from the ERP, step a) ───────────────

	/**
	 * The invoices page to {@link ErpSupplyInvoiceDTO}, in the order read. customerField: the header field of the
	 * customer (a configuration line). A row without a number is left out. Lines: Type Item with an item number is ITEM;
	 * another type with an amount is OTHER (no item code); a line without type (a comment, " ") and a line of another type
	 * with amount 0 are left out. The three totals come from the first line. Warnings: an item quantity with more than 3
	 * decimals (2.2.1: a decimal quantity is kept as the ERP sends it), prices including the VAT, lines that differ from
	 * Total_Amount_Excl_VAT by more than {@link #TOTAL_TOLERANCE}, Client_Franchise false, an Item line without item number, no line.
	 */
	public NavPosResult<ErpSupplyInvoiceDTO> invoices(List<NavPosInvoiceRow> rows, String customerField) {
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		Map<String, Integer> notes = new LinkedHashMap<>();
		List<ErpSupplyInvoiceDTO> invoices = new ArrayList<>();
		for (NavPosInvoiceRow row : rows) {
			String number = trimmed(row.text(NavPosInvoiceRow.NO));
			if (number.isEmpty()) {
				count(leftOut, BLANK_NUMBER);
				continue;
			}
			ErpSupplyInvoiceDTO invoice = new ErpSupplyInvoiceDTO();
			invoice.setNumber(number);
			invoice.setCustomerNo(blankToNull(row.text(customerField)));
			invoice.setCustomerName(blankToNull(row.text(NavPosInvoiceRow.CUSTOMER_NAME)));
			invoice.setDocumentDate(date(row.text(NavPosInvoiceRow.DOCUMENT_DATE)));
			invoice.setPostingDate(date(row.text(NavPosInvoiceRow.POSTING_DATE)));
			if (row.has(NavPosInvoiceRow.PRICES_INCLUDING_VAT) && row.text(NavPosInvoiceRow.PRICES_INCLUDING_VAT) != null) {
				invoice.setPricesIncludingVat(Boolean.valueOf(row.text(NavPosInvoiceRow.PRICES_INCLUDING_VAT)));
			}
			List<String> warnings = invoice.getWarnings();
			if (row.getLines().isEmpty()) {
				warnings.add(NO_LINE);
			} else {
				NavPosInvoiceLineRow first = row.getLines().get(0);
				invoice.setTotalExclVat(first.getTotalAmountExclVat());
				invoice.setTotalVat(first.getTotalVatAmount());
				invoice.setTotalInclVat(first.getTotalAmountInclVat());
			}
			BigDecimal sum = BigDecimal.ZERO;
			for (NavPosInvoiceLineRow source : row.getLines()) {
				String type = trimmed(source.getType());
				BigDecimal amount = source.getLineAmount() == null ? BigDecimal.ZERO : source.getLineAmount();
				if (type.isEmpty() || BLANK_TYPE.equals(type)) {
					count(leftOut, COMMENT_LINE);
					continue;
				}
				boolean item = ITEM_TYPE.equalsIgnoreCase(type);
				if (item && trimmed(source.getNo()).isEmpty()) {
					warnings.add("line " + source.getLineNo() + ": an Item line without an item number");
					item = false;
				}
				if (!item && amount.signum() == 0) {
					count(leftOut, ZERO_OTHER_LINE);
					continue;
				}
				ErpSupplyInvoiceDTO.Line line = new ErpSupplyInvoiceDTO.Line();
				line.setLineNo(source.getLineNo());
				line.setType(item ? ErpSupplyInvoiceDTO.LineType.ITEM : ErpSupplyInvoiceDTO.LineType.OTHER);
				line.setItemCode(item ? trimmed(source.getNo()) : null);
				line.setDescription(blankToNull(source.getDescription()));
				line.setQuantity(source.getQuantity());
				line.setUnitOfMeasure(blankToNull(source.getUnitOfMeasureCode()));
				line.setUnitPrice(source.getUnitPrice());
				line.setLineDiscountPercent(source.getLineDiscountPercent());
				line.setLineAmount(source.getLineAmount());
				// 2.2.1: a decimal quantity is read as the ERP sends it; more than 3 decimals cannot be kept
				if (item && source.getQuantity() != null && Quantities.decimals(source.getQuantity()) > Quantities.SCALE) {
					warnings.add("line " + source.getLineNo() + ": quantity " + source.getQuantity().toPlainString()
							+ " of item " + line.getItemCode() + " has more than " + Quantities.SCALE + " decimals");
				}
				sum = sum.add(amount);
				invoice.getLines().add(line);
			}
			if (Boolean.TRUE.equals(invoice.getPricesIncludingVat())) {
				warnings.add(PRICES_INCLUDE_VAT);
			}
			if (invoice.getTotalExclVat() != null && sum.subtract(invoice.getTotalExclVat()).abs().compareTo(TOTAL_TOLERANCE) > 0) {
				warnings.add("the lines add up to " + sum.toPlainString() + ", Total_Amount_Excl_VAT is "
						+ invoice.getTotalExclVat().toPlainString());
			}
			if ("false".equalsIgnoreCase(trimmed(row.text(NavPosInvoiceRow.CLIENT_FRANCHISE)))) {
				warnings.add(NOT_FRANCHISE);
			}
			if (!warnings.isEmpty()) {
				count(notes, WITH_WARNINGS);
			}
			invoices.add(invoice);
		}
		return new NavPosResult<>(invoices, rows.size(), leftOut, notes, null);
	}

	/** yyyy-MM-dd; null when blank, not a date, or the ERP's empty date 0001-01-01. */
	private static LocalDate date(String value) {
		String text = trimmed(value);
		if (text.isEmpty() || text.startsWith("0001-01-01")) {
			return null;
		}
		try {
			return LocalDate.parse(text.length() > 10 ? text.substring(0, 10) : text);
		} catch (DateTimeParseException e) {
			return null;
		}
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
