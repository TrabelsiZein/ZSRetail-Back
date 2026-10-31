package com.digithink.zsretail.connector.parafendri.mapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.digithink.zsretail.connector.parafendri.dto.ParafendriItemCategoryRow;
import com.digithink.zsretail.erp.api.ErpCategories;
import com.digithink.zsretail.erp.api.ErpRead;
import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
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
 * Each level's read counts the rows of that level only: a row of another level is not "left out" of it.
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

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}

	private static void count(Map<String, Integer> counts, String reason) {
		counts.merge(reason, 1, Integer::sum);
	}
}
