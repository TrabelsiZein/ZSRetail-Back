package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fiches items (3.1.0): what the head office page "Fiches items" shows, from the customer's data (no configuration):
 * the flags its columns, filters and labels follow, and the choices of its filters. Read once when the page opens.
 */
@Data
@NoArgsConstructor
public class ItemCatalogueFiltersDTO {

	/** A NAV item number has 2 items or more. */
	private boolean hasVariants;

	/** An item has a family. */
	private boolean hasFamilies;

	/** An item has a division. */
	private boolean hasDivision;

	/** An item has a catégorie. */
	private boolean hasCategory;

	/** An item has a section. */
	private boolean hasSection;

	/** The points de stock (active or not). */
	private long stockPointCount;

	/** A barcode exists. */
	private boolean hasBarcodes;

	/** The families that have an item, by name. */
	private List<Option> families = new ArrayList<>();

	/** The sub-families that have an item, by name, with their family (parentId). */
	private List<Option> subFamilies = new ArrayList<>();

	/** The division codes of the items (code only: no table). */
	private List<Option> divisions = new ArrayList<>();

	private List<Option> categories = new ArrayList<>();

	private List<Option> sections = new ArrayList<>();

	/** The points de stock in list order. */
	private List<Option> stockPoints = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class Option {

		/** The row's id; null for a code (division, catégorie, section). */
		private Long id;

		private String code;

		private String name;

		/** A sub-family's family id; null otherwise. */
		private Long parentId;
	}
}
