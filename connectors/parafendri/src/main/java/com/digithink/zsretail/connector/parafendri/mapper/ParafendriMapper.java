package com.digithink.zsretail.connector.parafendri.mapper;

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
import java.util.function.Function;

import com.digithink.zsretail.connector.parafendri.dto.ParafendriItemCategoryRow;
import com.digithink.zsretail.connector.parafendri.dto.ParafendriStockPointRow;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;

/**
 * ParaFendri, step 2: the rows of the API to the Erp*DTO classes. The tree itemCategories, as ZEN's (same API family):
 * by indentation, 0 catégorie, 1 famille, 2 sous-famille, 3 section, the parent parentCategory; code and external id
 * the code, name and description the description, display order presentationOrder, active.
 * <ul>
 * <li>A famille keeps its catégorie (categoryExternalId) when its parent is a catégorie of the read, else it is kept
 * without one and noted ({@link #FAMILY_WITHOUT_CATEGORY}): its sous-familles and items keep their place.</li>
 * <li>Left out, each with its reason: a sous-famille whose parent is not a famille of the read, a section whose parent
 * is not a sous-famille of the read (the import would never save them), a blank code (counted at its level), and a row
 * without a level 0 to 3 (counted with the sections, the deepest level).</li>
 * </ul>
 * Each level's read counts the rows of that level only: a row of another level is not "left out" of it. Step 3: the
 * items of stockPoints ({@link #items}).
 */
public class ParafendriMapper {

	public static final String BLANK_CODE = "blank code";
	public static final String FAMILY_WITHOUT_CATEGORY = "famille whose parent is not a catégorie";
	public static final String PARENT_NOT_FAMILY = "parent not a famille of the read";
	public static final String PARENT_NOT_SUB_FAMILY = "parent not a sous-famille of the read";
	public static final String NO_INDENTATION = "no indentation";

	static final int CATEGORY = 0;
	static final int FAMILY = 1;
	static final int SUB_FAMILY = 2;
	static final int SECTION = 3;

	/** e.g. "indentation 4 (deeper than a section)": a row outside the four levels. */
	public static String outsideLevels(int indentation) {
		return "indentation " + indentation + (indentation > SECTION ? " (deeper than a section)" : " (not a level)");
	}

	/** The four levels of the tree from one read of the page. */
	public ErpCategories categories(List<ParafendriItemCategoryRow> rows) {
		Map<String, Integer> levels = new HashMap<>();
		for (ParafendriItemCategoryRow row : rows) {
			Integer level = row.getIndentation();
			if (!trimmed(row.getCode()).isEmpty() && level != null && level >= CATEGORY && level <= SECTION) {
				levels.putIfAbsent(trimmed(row.getCode()), level);
			}
		}
		List<ErpItemCategoryDTO> categories = new ArrayList<>();
		List<ErpItemFamilyDTO> families = new ArrayList<>();
		List<ErpItemSubFamilyDTO> subFamilies = new ArrayList<>();
		List<ErpItemSectionDTO> sections = new ArrayList<>();
		int[] read = new int[SECTION + 1];
		List<Map<String, Integer>> leftOut = new ArrayList<>();
		List<Map<String, Integer>> notes = new ArrayList<>();
		for (int level = CATEGORY; level <= SECTION; level++) {
			leftOut.add(new LinkedHashMap<>());
			notes.add(new LinkedHashMap<>());
		}
		for (ParafendriItemCategoryRow row : rows) {
			Integer level = row.getIndentation();
			if (level == null || level < CATEGORY || level > SECTION) {
				read[SECTION]++;
				count(leftOut.get(SECTION), level == null ? NO_INDENTATION : outsideLevels(level));
				continue;
			}
			read[level]++;
			String code = trimmed(row.getCode());
			if (code.isEmpty()) {
				count(leftOut.get(level), BLANK_CODE);
				continue;
			}
			String parent = trimmed(row.getParentCategory());
			Integer parentLevel = levels.get(parent);
			String name = trimmed(row.getDescription());
			switch (level) {
				case CATEGORY: {
					ErpItemCategoryDTO category = new ErpItemCategoryDTO();
					category.setExternalId(code);
					category.setCode(code);
					category.setName(name);
					category.setDescription(name);
					category.setDisplayOrder(row.getPresentationOrder());
					category.setActive(Boolean.TRUE);
					categories.add(category);
					break;
				}
				case FAMILY: {
					ErpItemFamilyDTO family = new ErpItemFamilyDTO();
					family.setExternalId(code);
					family.setCode(code);
					family.setName(name);
					family.setDescription(name);
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
					subFamily.setName(name);
					subFamily.setDescription(name);
					subFamily.setDisplayOrder(row.getPresentationOrder());
					subFamily.setActive(Boolean.TRUE);
					subFamily.setFamilyExternalId(parent);
					subFamilies.add(subFamily);
					break;
				}
				default: {
					if (parentLevel == null || parentLevel != SUB_FAMILY) {
						count(leftOut.get(SECTION), PARENT_NOT_SUB_FAMILY);
						break;
					}
					ErpItemSectionDTO section = new ErpItemSectionDTO();
					section.setExternalId(code);
					section.setCode(code);
					section.setName(name);
					section.setDescription(name);
					section.setDisplayOrder(row.getPresentationOrder());
					section.setActive(Boolean.TRUE);
					section.setSubFamilyExternalId(parent);
					sections.add(section);
					break;
				}
			}
		}
		return new ErpCategories(
				new ErpRead<>(categories, read[CATEGORY], leftOut.get(CATEGORY), notes.get(CATEGORY), null),
				new ErpRead<>(families, read[FAMILY], leftOut.get(FAMILY), notes.get(FAMILY), null),
				new ErpRead<>(subFamilies, read[SUB_FAMILY], leftOut.get(SUB_FAMILY), notes.get(SUB_FAMILY), null),
				new ErpRead<>(sections, read[SECTION], leftOut.get(SECTION), notes.get(SECTION), null));
	}

	// ─── Items (step 3, stockPoints) ────────────────────────────

	public static final String BLANK_ITEM_NO = "blank itemNo";
	public static final String OTHER_LOCATION = "row of another location";
	public static final String SAME_ITEM = "same itemNo twice";
	public static final String NO_VAT = "no vatPercent";
	public static final String ZERO_PRICE = "null or zero unitPrice";
	public static final String VAT_NOT_WHOLE = "vatPercent not a whole number (rounded)";
	public static final String ITEM_BLOCKED = "itemBlocked";
	public static final String ITEM_SALES_BLOCKED = "itemSalesBlocked";
	public static final String STOCK_POINT_BLOCKED = "stockPointBlocked";
	public static final String NO_DATE = "no lastModifiedDateTime (0001-01-01)";
	public static final String CATEGORY_NOT_IN_TREE = "categoryCode not a catégorie of the tree";
	public static final String FAMILY_NOT_IN_TREE = "familyCode not a famille of the tree";
	public static final String SUB_FAMILY_NOT_IN_TREE = "subFamilyCode not a sous-famille of the tree";
	public static final String SECTION_NOT_IN_TREE = "sectionCode not a section of the tree";

	/**
	 * Step 3: the rows of one point de vente to items. Code and external id itemNo; name and description
	 * stockPointDescription, else itemDescription; unit baseUnitOfMeasure (blank: none); the price unitPrice as received
	 * (before VAT, never rounded: the till adds the VAT); the VAT vatPercent (a whole number; else rounded and noted);
	 * lastModifiedAt lastModifiedDateTime (0001-01-01: none, noted). Inactive, kept and noted under its reason: itemBlocked,
	 * itemSalesBlocked, stockPointBlocked, a null or zero unitPrice. The four links (categoryCode, familyCode,
	 * subFamilyCode, sectionCode): a blank code none; a code that is not a row the tree keeps at its level ({@link
	 * #categories}) none and noted, the item kept. Left out with their reason: a blank itemNo, a row of another location,
	 * no vatPercent, the same itemNo twice (the first kept). Not read: itemCategoryCode, customerNo.
	 */
	public ErpRead<ErpItemDTO> items(String locationCode, List<ParafendriStockPointRow> rows, ErpCategories tree) {
		Set<String> categories = codes(tree.getCategories().getRows(), ErpItemCategoryDTO::getCode);
		Set<String> families = codes(tree.getFamilies().getRows(), ErpItemFamilyDTO::getCode);
		Set<String> subFamilies = codes(tree.getSubFamilies().getRows(), ErpItemSubFamilyDTO::getCode);
		Set<String> sections = codes(tree.getSections().getRows(), ErpItemSectionDTO::getCode);
		String location = trimmed(locationCode);
		Map<String, Integer> leftOut = new LinkedHashMap<>();
		Map<String, Integer> notes = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();
		List<ErpItemDTO> items = new ArrayList<>();
		for (ParafendriStockPointRow row : rows) {
			String itemNo = trimmed(row.getItemNo());
			if (itemNo.isEmpty()) {
				count(leftOut, BLANK_ITEM_NO);
				continue;
			}
			if (!trimmed(row.getLocationCode()).equalsIgnoreCase(location)) {
				count(leftOut, OTHER_LOCATION);
				continue;
			}
			if (row.getVatPercent() == null) {
				count(leftOut, NO_VAT);
				continue;
			}
			if (!seen.add(itemNo)) {
				count(leftOut, SAME_ITEM);
				continue;
			}
			ErpItemDTO item = new ErpItemDTO();
			item.setExternalId(itemNo);
			item.setCode(itemNo);
			String description = trimmed(row.getStockPointDescription());
			if (description.isEmpty()) {
				description = trimmed(row.getItemDescription());
			}
			item.setName(blankToNull(description));
			item.setDescription(blankToNull(description));
			item.setUnitOfMeasure(blankToNull(row.getBaseUnitOfMeasure()));
			BigDecimal vat = row.getVatPercent().setScale(0, RoundingMode.HALF_UP);
			if (vat.compareTo(row.getVatPercent()) != 0) {
				count(notes, VAT_NOT_WHOLE);
			}
			item.setDefaultVAT(vat.intValue());
			boolean active = true;
			BigDecimal price = row.getUnitPrice();
			if (price == null || price.signum() <= 0) {
				count(notes, ZERO_PRICE);
				price = price == null ? BigDecimal.ZERO : price;
				active = false;
			}
			item.setUnitPrice(price);
			if (Boolean.TRUE.equals(row.getItemBlocked())) {
				count(notes, ITEM_BLOCKED);
				active = false;
			}
			if (Boolean.TRUE.equals(row.getItemSalesBlocked())) {
				count(notes, ITEM_SALES_BLOCKED);
				active = false;
			}
			if (Boolean.TRUE.equals(row.getStockPointBlocked())) {
				count(notes, STOCK_POINT_BLOCKED);
				active = false;
			}
			item.setActive(active);
			item.setCategoryCode(link(row.getCategoryCode(), categories, notes, CATEGORY_NOT_IN_TREE));
			item.setFamilyExternalId(link(row.getFamilyCode(), families, notes, FAMILY_NOT_IN_TREE));
			item.setSubFamilyExternalId(link(row.getSubFamilyCode(), subFamilies, notes, SUB_FAMILY_NOT_IN_TREE));
			item.setSectionCode(link(row.getSectionCode(), sections, notes, SECTION_NOT_IN_TREE));
			OffsetDateTime modified = modifiedAt(row.getLastModifiedDateTime());
			if (modified == null) {
				count(notes, NO_DATE);
			}
			item.setLastModifiedAt(modified);
			items.add(item);
		}
		return new ErpRead<>(items, rows.size(), leftOut, notes, null);
	}

	/** A blank code: none; a code the tree does not keep at its level: none, noted; else the code. */
	private static String link(String value, Set<String> tree, Map<String, Integer> notes, String reason) {
		String code = trimmed(value);
		if (code.isEmpty()) {
			return null;
		}
		if (!tree.contains(code)) {
			count(notes, reason);
			return null;
		}
		return code;
	}

	/** lastModifiedDateTime, null when blank, Business Central's empty date (0001-01-01) or not readable. */
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

	private static <T> Set<String> codes(List<T> rows, Function<T, String> code) {
		Set<String> codes = new HashSet<>();
		for (T row : rows) {
			codes.add(code.apply(row));
		}
		return codes;
	}

	private static String blankToNull(String value) {
		String text = trimmed(value);
		return text.isEmpty() ? null : text;
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}

	private static void count(Map<String, Integer> counts, String reason) {
		counts.merge(reason, 1, Integer::sum);
	}
}
