package com.digithink.zsretail.core.link.dto;

import com.digithink.zsretail.core.model.ItemCategory;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Item variants (ZEN), step 7: a catégorie as it travels down (record CATEGORY:&lt;code&gt;), by code only, as a family.
 * Also used by the store to compare what it has with what it receives.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogueCategoryCopyDTO {

	private String kind = CatalogueKind.CATEGORY.name();
	private String code;
	private String name;
	private String description;
	private Integer displayOrder;
	private Boolean active;

	public static CatalogueCategoryCopyDTO of(ItemCategory category) {
		CatalogueCategoryCopyDTO copy = new CatalogueCategoryCopyDTO();
		copy.code = category.getCode();
		copy.name = category.getName();
		copy.description = category.getDescription();
		copy.displayOrder = category.getDisplayOrder();
		copy.active = !Boolean.FALSE.equals(category.getActive());
		return copy;
	}
}
