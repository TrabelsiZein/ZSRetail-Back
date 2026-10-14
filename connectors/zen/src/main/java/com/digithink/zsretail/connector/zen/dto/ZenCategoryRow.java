package com.digithink.zsretail.connector.zen.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A row of the page itemCategories: one node of the tree, its level by indentation (0 catégorie, 1 famille, 2
 * sous-famille, 3 section) and its parent by code.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ZenCategoryRow {

	private String code;
	private String description;
	private String parentCategory;
	private Integer indentation;
	private Integer presentationOrder;
}
