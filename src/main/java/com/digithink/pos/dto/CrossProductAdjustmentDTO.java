package com.digithink.pos.dto;

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
	/** Base unit price excluding VAT — used to build the auto-added free line. */
	private Double getItemUnitPrice;
	private Integer getItemDefaultVat;

	/** floor(buyLineQty / minimumQuantity), summed over all matching buy lines. */
	private Integer entitledUnits;

	/** FREE_QUANTITY only: entitledUnits × freeQuantity. */
	private Integer freeUnits;

	/** PERCENTAGE_DISCOUNT only: percentage applied per entitled unit. */
	private Double discountPercentage;

	/** FIXED_DISCOUNT only: TND amount applied per entitled unit. */
	private Double discountAmount;
}
