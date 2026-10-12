package com.digithink.zsretail.store.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One cross-product benefit computed by the cart pass:
 * "buy N of <buy target> → benefit on getItem".
 *
 * The POS applies it to the get-item's cart line:
 *  - PERCENTAGE_DISCOUNT / FIXED_DISCOUNT: discount min(entitledUnits, qty in cart)
 *    units of the get item (converted to a line discountAmount, TTC). No auto-add.
 *  - FREE_QUANTITY: auto-add (or update) a free line of the get item with
 *    freeUnits units at 100% discount.
 */
@Data
@NoArgsConstructor
public class CrossProductAdjustmentDTO {

	private Long promotionId;
	private String promotionName;
	private String promotionCode;

	/** PERCENTAGE_DISCOUNT | FIXED_DISCOUNT | FREE_QUANTITY */
	private String benefitType;

	// ── Get item (benefit target) ────────────────────────────────────────────
	private Long getItemId;
	private String getItemCode;
	private String getItemName;
	/** Base unit price excluding VAT (a till of before 3.1.0 builds its auto-added free line with it). */
	private Double getItemUnitPrice;
	private Integer getItemDefaultVat;

	/** floor(buyLineQty / minimumQuantity), summed over all matching buy lines. */
	private Integer entitledUnits;

	/**
	 * FREE_QUANTITY only: entitledUnits × freeQuantity, the free pieces of Y the buy lines give, whether Y is in the cart
	 * or not. Kept with this meaning for a till of before 3.1.0 still running (it adds them as a line on top).
	 */
	private Integer freeUnits;

	/**
	 * FREE_QUANTITY only (2026-10-12, a free piece only when scanned): the free pieces among the pieces of Y in the cart,
	 * min(freeUnits, Y's whole pieces in the cart lines).
	 */
	private Integer freeQuantity;

	/** FREE_QUANTITY only: the free pieces of Y still to scan (freeUnits - freeQuantity), for a hint. */
	private Integer freeQuantityAvailable;

	/** PERCENTAGE_DISCOUNT only: percentage applied per entitled unit. */
	private Double discountPercentage;

	/** FIXED_DISCOUNT only: TND amount applied per entitled unit. */
	private Double discountAmount;
}
