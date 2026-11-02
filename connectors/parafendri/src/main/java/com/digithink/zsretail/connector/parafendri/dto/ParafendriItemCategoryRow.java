package com.digithink.zsretail.connector.parafendri.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Step 2: a row of the page itemCategories of the company, the whole tree in one table: its depth is indentation (0
 * catégorie, 1 famille, 2 sous-famille, 3 section), its parent parentCategory (blank on a catégorie). hasChildren and
 * lastModifiedDateTime are read, not used (the tree is read whole at each run).
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParafendriItemCategoryRow {

	private String code;
	private String description;
	private String parentCategory;
	private Integer indentation;
	private Boolean hasChildren;
	private Integer presentationOrder;
	private String lastModifiedDateTime;
}
