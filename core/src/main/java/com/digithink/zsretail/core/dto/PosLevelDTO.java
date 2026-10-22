package com.digithink.zsretail.core.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;

/**
 * 3.1.0 (ZEN): the till's levels in its search (GET /item/pos/search), when the installation has catégories or
 * sections (GET /config itemCategories, itemSections): each catégorie, famille, sous-famille and section found carries
 * its path from the catégorie down to itself, so the till shows "Homme › Chemise" and opens the level. Without both
 * levels nothing here is sent: the search answers as before.
 */
public final class PosLevelDTO {

	public static final String CATEGORY = "category";
	public static final String FAMILY = "family";
	public static final String SUB_FAMILY = "subFamily";
	public static final String SECTION = "section";

	private PosLevelDTO() {
	}

	/** One level of a path: its kind ({@link #CATEGORY}, {@link #FAMILY}, {@link #SUB_FAMILY}, {@link #SECTION}). */
	@Data
	@AllArgsConstructor
	public static class Step {
		private String level;
		private Long id;
		private String code;
		private String name;
	}

	/** A search hit: the entity's own fields as before (unwrapped), plus its path. */
	@Getter
	@AllArgsConstructor
	public static class Hit<T> {
		@JsonUnwrapped
		private final T entry;
		private final List<Step> path;
	}
}
