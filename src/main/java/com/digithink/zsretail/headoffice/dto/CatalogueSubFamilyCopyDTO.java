package com.digithink.zsretail.headoffice.dto;

import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 6: an item sub-family as it travels down (record SUBFAMILY:&lt;code&gt;), with its family by
 * code. The image does not travel (task 6.8).
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogueSubFamilyCopyDTO {

	private String kind = CatalogueKind.SUBFAMILY.name();
	private String code;
	private String name;
	private String description;
	private Integer displayOrder;

	/** ItemFamily.code of its family. */
	private String familyCode;
	private Boolean active;

	public static CatalogueSubFamilyCopyDTO of(ItemSubFamily subFamily) {
		CatalogueSubFamilyCopyDTO copy = new CatalogueSubFamilyCopyDTO();
		copy.code = subFamily.getCode();
		copy.name = subFamily.getName();
		copy.description = subFamily.getDescription();
		copy.displayOrder = subFamily.getDisplayOrder();
		copy.familyCode = subFamily.getItemFamily() == null ? null : subFamily.getItemFamily().getCode();
		copy.active = !Boolean.FALSE.equals(subFamily.getActive());
		return copy;
	}
}
