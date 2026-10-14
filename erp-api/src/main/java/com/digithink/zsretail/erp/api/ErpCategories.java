package com.digithink.zsretail.erp.api;

import java.util.ArrayList;
import java.util.Collections;

import com.digithink.zsretail.erp.api.dto.ErpItemCategoryDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSectionDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;

/**
 * Code cleanup step 3: one read of the ERP's categories, as families and as sub-families (both from the same read, so
 * the sub-families run and the waiting checks compare one state of the ERP). Item variants (ZEN), step 2: also the
 * catégories (above the families) and the sections (below the sub-families), from the same read; empty for a connector
 * that gives neither.
 */
public final class ErpCategories {

	private final ErpRead<ErpItemCategoryDTO> categories;
	private final ErpRead<ErpItemFamilyDTO> families;
	private final ErpRead<ErpItemSubFamilyDTO> subFamilies;
	private final ErpRead<ErpItemSectionDTO> sections;

	/** Families and sub-families only: the catégories and the sections are empty reads (0 rows read). */
	public ErpCategories(ErpRead<ErpItemFamilyDTO> families, ErpRead<ErpItemSubFamilyDTO> subFamilies) {
		this(empty(), families, subFamilies, empty());
	}

	/** Item variants (ZEN), step 2: the four levels, from one read of the ERP. */
	public ErpCategories(ErpRead<ErpItemCategoryDTO> categories, ErpRead<ErpItemFamilyDTO> families,
			ErpRead<ErpItemSubFamilyDTO> subFamilies, ErpRead<ErpItemSectionDTO> sections) {
		this.categories = categories;
		this.families = families;
		this.subFamilies = subFamilies;
		this.sections = sections;
	}

	/** A read of no row: nothing read, nothing left out. */
	private static <T> ErpRead<T> empty() {
		return new ErpRead<>(new ArrayList<>(), 0, Collections.emptyMap(), Collections.emptyMap(), null);
	}

	/** Item variants (ZEN), step 2: the catégories; an empty read when the connector gives none. */
	public ErpRead<ErpItemCategoryDTO> getCategories() {
		return categories;
	}

	public ErpRead<ErpItemFamilyDTO> getFamilies() {
		return families;
	}

	public ErpRead<ErpItemSubFamilyDTO> getSubFamilies() {
		return subFamilies;
	}

	/** Item variants (ZEN), step 2: the sections; an empty read when the connector gives none. */
	public ErpRead<ErpItemSectionDTO> getSections() {
		return sections;
	}
}
