package com.digithink.zsretail.headoffice.dto;

import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 6: an item family as it travels down (record FAMILY:&lt;code&gt;), by code only. The image does
 * not travel (task 6.8). Also used by the store to compare what it has with what it receives.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogueFamilyCopyDTO {

	private String kind = CatalogueKind.FAMILY.name();
	private String code;
	private String name;
	private String description;
	private Integer displayOrder;
	private Boolean active;

	public static CatalogueFamilyCopyDTO of(ItemFamily family) {
		CatalogueFamilyCopyDTO copy = new CatalogueFamilyCopyDTO();
		copy.code = family.getCode();
		copy.name = family.getName();
		copy.description = family.getDescription();
		copy.displayOrder = family.getDisplayOrder();
		copy.active = !Boolean.FALSE.equals(family.getActive());
		return copy;
	}
}
