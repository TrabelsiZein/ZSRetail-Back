package com.digithink.zsretail.core.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Item variants (ZEN), step 11b: the lines of the read-only pages "Catégories" and "Sections", the levels of the ERP's
 * tree above the family (catégorie) and below the sub-family (section). Codes link the levels (the ERP's codes); a name
 * is null where the level above has no row of that code.
 */
public final class ItemTreeDTO {

	private ItemTreeDTO() {
	}

	/** A catégorie with the number of families that name it. */
	@Data
	@NoArgsConstructor
	public static class Category {
		private String code;
		private String name;
		private String description;
		private boolean active;
		private long families;
	}

	/** A family under a catégorie (a catégorie line opened). */
	@Data
	@NoArgsConstructor
	public static class Family {
		private String code;
		private String name;
		private boolean active;
	}

	/** A section with its path: sous-famille, its famille, the famille's catégorie. */
	@Data
	@NoArgsConstructor
	public static class Section {
		private String code;
		private String name;
		private String description;
		private boolean active;
		private String subFamilyCode;
		private String subFamilyName;
		private String familyCode;
		private String familyName;
		private String categoryCode;
		private String categoryName;
	}
}
