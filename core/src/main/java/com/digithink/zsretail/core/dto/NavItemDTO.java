package com.digithink.zsretail.core.dto;

import java.math.BigDecimal;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Item variants (ZEN), step 11a: one line of the "NAV items" page, the variants (items) of one NAV item number. The
 * catégorie and section names are null where their table has no row of that code (a store receives the codes only).
 */
@Data
@NoArgsConstructor
public class NavItemDTO {

	private String navItemNo;

	/** The NAV item's description (every variant's name). */
	private String name;

	private String familyName;

	private String subFamilyName;

	private String categoryCode;

	private String categoryName;

	private String sectionCode;

	private String sectionName;

	/** The variants counted (the active ones only with the status Active). */
	private long variants;

	/**
	 * Head office only: the active points de stock where at least one variant has an active row; null on a store (it holds
	 * no point de stock row).
	 */
	private Long stockPoints;

	/** Sum of the variants' stock; null where the installation keeps no stock of its own. */
	private BigDecimal stockQuantity;
}
