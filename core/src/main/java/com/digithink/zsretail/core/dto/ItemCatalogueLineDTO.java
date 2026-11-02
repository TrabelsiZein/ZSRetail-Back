package com.digithink.zsretail.core.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Fiches items (3.1.0), step 1: one line of the head office page "Fiches items" (3.1.1: and of the store's grouped Articles
 * page), a NAV item number (its variants) or one item without NAV number. Its key is navItemNo or itemId, never both.
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

	/** The active points de stock where one of the line's items has an active row; 0 without any (head office only: 0 on
	 * a store). */
	private long stockPoints;

	/** True when at least one item counted is active (always true with the status Active). */
	private boolean active;

	/** Sum of the items' stock; null where the head office keeps no stock of its own. */
	private BigDecimal stockQuantity;

	/** True when the line is one item (no NAV number): the page offers its actions on the line. */
	private boolean single;

	/** The smallest and largest of its items' own prices with VAT (the variants of the status); null without price. */
	private Double priceMin;

	private Double priceMax;

	/**
	 * 3.1.1, head office with a point de stock chosen: priceMin / priceMax are the selling prices with VAT of the line's
	 * rows at that point (the variants of the status with a row of the row status), not the items' own prices; false
	 * otherwise (always false on a store).
	 */
	private boolean pointPrice;

	/** 3.1.1, with pointPrice: the line's rows in clearance (selling at their clearance price); 0 otherwise. */
	private long clearanceVariants;

	/** 3.1.1, with pointPrice and a row in clearance: the rows' normal prices with VAT (struck on the page); else null. */
	private Double normalPriceMin;

	private Double normalPriceMax;

	/**
	 * 3.1.1: with a search, the ids of the line's items that match it (NAV number, item code, name or a barcode), so that a
	 * page highlights the variants found; null without a search.
	 */
	private List<Long> matchedItemIds;

	// 3.1.2, the store's Articles page (every store): what its old flat list showed, read for the page's lines
	// (ItemCatalogueService.storeDetails); never set on the head office, and left out of the JSON when null.

	/** A single item's origin, HEAD_OFFICE or LOCAL (null when the item has none). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private String origin;

	/** A single item: true when its price is the store's own price on a head office item. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Boolean ownPrice;

	/** A single item with its own price: the head office's price with VAT. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double headOfficePrice;

	/** A single item: its price before VAT (the price cell's tooltip, with vatPercent). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double unitPrice;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Integer vatPercent;

	/** A single item in clearance, sold below its public price: that public price with VAT (struck on the page). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double publicPrice;

	/** A single item: its minimum stock level, when it has one (the low stock warning). */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Integer minStockLevel;

	/** A single item: the ERP's maximum discount %, when it has one. */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double maximumAuthorizedDiscount;

	/**
	 * The weighted average cost (CMP) before VAT, the smallest and the largest of the line's items: only where the store
	 * keeps its own stock and for a role with write:admin-item-cost or read:admin-margins; null otherwise or without cost.
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double costMin;

	@JsonInclude(JsonInclude.Include.NON_NULL)
	private Double costMax;
}
