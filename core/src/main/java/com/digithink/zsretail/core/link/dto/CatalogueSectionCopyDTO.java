package com.digithink.zsretail.core.link.dto;

import com.digithink.zsretail.core.model.ItemSection;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Item variants (ZEN), step 7: a section as it travels down (record SECTION:&lt;code&gt;), with its sub-family by code (a
 * code only: the store never waits for it). Also used by the store to compare what it has with what it receives.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CatalogueSectionCopyDTO {

	private String kind = CatalogueKind.SECTION.name();
	private String code;
	private String name;
	private String description;
	private Integer displayOrder;
	/** ItemSubFamily.code of its sub-family; null when none. */
	private String subFamilyCode;
	private Boolean active;

	public static CatalogueSectionCopyDTO of(ItemSection section) {
		CatalogueSectionCopyDTO copy = new CatalogueSectionCopyDTO();
		copy.code = section.getCode();
		copy.name = section.getName();
		copy.description = section.getDescription();
		copy.displayOrder = section.getDisplayOrder();
		copy.subFamilyCode = section.getSubFamilyCode();
		copy.active = !Boolean.FALSE.equals(section.getActive());
		return copy;
	}
}
