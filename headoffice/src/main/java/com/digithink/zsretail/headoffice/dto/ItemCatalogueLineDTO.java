package com.digithink.zsretail.headoffice.dto;

import java.math.BigDecimal;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fiches items (3.1.0), step 1: one line of the head office page "Fiches items", a NAV item number (its variants) or
 * one item without NAV number. Its key is navItemNo or itemId, never both.
 */
@Data
@NoArgsConstructor
public class ItemCatalogueLineDTO {

	/** The line's NAV item number; null for a single item. */
	private String navItemNo;

	/** The single item's id; null for a NAV line. */
	private Long itemId;

	/** The NAV item number, or the single item's code. */
	private String code;

	private String name;

	private String familyName;

	private String subFamilyName;

	private String categoryCode;

	private String categoryName;

	private String sectionCode;

	private String sectionName;

	/** The variants counted (the active ones only with the status Active); 1 for a single item. */
	private long variants;

	/** The active points de stock where one of the line's items has an active row; 0 without any. */
	private long stockPoints;

	/** True when at least one item counted is active (always true with the status Active). */
	private boolean active;

	/** Sum of the items' stock; null where the head office keeps no stock of its own. */
	private BigDecimal stockQuantity;

	/** True when the line is one item (no NAV number): the page offers its actions on the line. */
	private boolean single;
}
