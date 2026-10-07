package com.digithink.zsretail.erp.navpospages.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A row of the categories page (ItemCategory): Type is "Categorie", "Family" or "Subfamily". */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NavPosCategoryRow {

	@JsonProperty("Code")
	private String code;

	@JsonProperty("Description")
	private String description;

	@JsonProperty("Parent_Category")
	private String parentCategory;

	@JsonProperty("Type")
	private String type;

	public NavPosCategoryRow(String code, String description, String parentCategory, String type) {
		this.code = code;
		this.description = description;
		this.parentCategory = parentCategory;
		this.type = type;
	}
}
