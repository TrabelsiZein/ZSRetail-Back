package com.digithink.zsretail.erp.api;

import com.digithink.zsretail.erp.api.dto.ErpItemFamilyDTO;
import com.digithink.zsretail.erp.api.dto.ErpItemSubFamilyDTO;

/**
 * Code cleanup step 3: one read of the ERP's categories, as families and as sub-families (both from the same read, so
 * the sub-families run and the waiting checks compare one state of the ERP).
 */
public final class ErpCategories {

	private final ErpRead<ErpItemFamilyDTO> families;
	private final ErpRead<ErpItemSubFamilyDTO> subFamilies;

	public ErpCategories(ErpRead<ErpItemFamilyDTO> families, ErpRead<ErpItemSubFamilyDTO> subFamilies) {
		this.families = families;
		this.subFamilies = subFamilies;
	}

	public ErpRead<ErpItemFamilyDTO> getFamilies() {
		return families;
	}

	public ErpRead<ErpItemSubFamilyDTO> getSubFamilies() {
		return subFamilies;
	}
}
