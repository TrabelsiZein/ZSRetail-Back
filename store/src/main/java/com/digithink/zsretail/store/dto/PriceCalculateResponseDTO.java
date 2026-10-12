package com.digithink.zsretail.store.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for POST /price-calculate
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceCalculateResponseDTO {

	private Long itemId;

	/** Final unit price (HT — excluding VAT). Always set. */
	private Double unitPrice;

	/** Whether the original source price included VAT. */
	private Boolean priceIncludesVat;

	/** Percentage discount to apply on the unit price (0–100). From SalesDiscount or PERCENTAGE_DISCOUNT promotion. */
	private Double discountPercentage;

	/** Fixed monetary discount (HT) to apply per unit. From FIXED_DISCOUNT promotion. */
	private Double discountAmount;

	/**
	 * FREE_QUANTITY promotion: the free pieces among the line's pieces (the quantity asked is all the pieces, the free
	 * ones included; decision of 2026-10-12, a free piece is never added on top), repeated for each group of N + M pieces.
	 * Buy 2 get 1: quantity 2 → 0, 3 → 1, 6 → 2, 9 → 3.
	 */
	private Integer freeQuantity;

	/**
	 * FREE_QUANTITY promotion: the free pieces of the group in progress still to scan, for a hint "1 more piece is free".
	 * Buy 2 get 1: quantity 2 or 5 → 1; 3, 4 or 6 → 0.
	 */
	private Integer freeQuantityAvailable;

	/**
	 * The FREE_QUANTITY promotion of freeQuantity / freeQuantityAvailable: the one applied, or (0 free piece yet, Q = N)
	 * the one that applies from one more piece, while the line is priced without it (promotionId is then another
	 * promotion or none).
	 */
	private Long freePromotionId;
	private String freePromotionName;

	/** ID of the applied promotion — use this to persist promotion_id on the sales line. */
	private Long promotionId;

	/** Name of the applied promotion (null if no promotion applied). */
	private String promotionName;

	/** Code of the applied promotion (null if no promotion applied). */
	private String promotionCode;

	/** Promotion type enum name (SIMPLE_DISCOUNT, QUANTITY_PROMOTION). Null if no promotion. */
	private String promotionType;

	/**
	 * Pricing source:
	 *   "SALES_PRICE"    – price from SalesPrice table (ERP)
	 *   "SALES_DISCOUNT" – base item price with SalesDiscount (ERP)
	 *   "PROMOTION"      – base item price with a configured promotion
	 *   "BASE_PRICE"     – item unit price, no special pricing
	 */
	private String source;
}
