package com.digithink.zsretail.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response body for POST /cart-calculate
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartCalculateResponseDTO {

	/** ID of the applied cart promotion — use this to persist promotion_id on the sales header. */
	private Long promotionId;

	/** Name of the applied cart promotion (null if none). */
	private String promotionName;

	/** Code of the applied cart promotion (null if none). */
	private String promotionCode;

	/** Monetary discount applied to the cart total (TTC). 0.0 if no promotion matched. */
	private Double cartDiscountAmount = 0.0;

	/** Percentage rate used if benefitType is PERCENTAGE_DISCOUNT. Null otherwise. */
	private Double discountPercentage;

	/** Cart total after applying the cart promotion. Equals cartTotal - cartDiscountAmount. */
	private Double finalTotal;

	/**
	 * Cross-product benefits triggered by the current cart lines
	 * ("buy N of A → benefit on Z"). Empty when none apply.
	 * Independent of the CART-scope promotion above.
	 */
	private List<CrossProductAdjustmentDTO> crossProductAdjustments = new ArrayList<>();
}
