package com.digithink.zsretail.core.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Promotions for variants, step 4: one line of the target lookup of the promotion form (GET /item/lookup). The screen
 * builds the label from these fields (its i18n), never the server.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ItemLookupLineDTO {

	/**
	 * Scope ITEM: NAV_ITEM (every active variant of an ERP item number, id one of them: the lowest) or ROW (one item);
	 * the other scopes: FAMILY, SUBFAMILY, CATEGORY, SECTION.
	 */
	private String kind;

	/** The row's id; for NAV_ITEM the lowest id of its active variants (the item the form keeps with "all variants"). */
	private Long id;

	/** Item code, ERP item number (NAV_ITEM) or the code of the famille, sous-famille, catégorie, section. */
	private String code;

	private String name;

	/** ROW of a variant: its label (size / colour / choice); null otherwise. */
	private String variantDescription;

	/** NAV_ITEM, and ROW of a variant: the ERP item number; null otherwise. */
	private String navItemNo;

	/** NAV_ITEM: its active variants; 1 for a ROW; null for the other scopes. */
	private Integer variantCount;

	/** SUBFAMILY: its famille's name; SECTION: its sous-famille's name; null otherwise. */
	private String parentName;
}
