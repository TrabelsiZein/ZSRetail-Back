package com.digithink.zsretail.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for POST /cart-calculate
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CartCalculateRequestDTO {

	private Long customerId;

	/** Cart total TTC (including VAT, after all line-level discounts). */
	private Double cartTotal;

	private List<CartItemDTO> cartItems = new ArrayList<>();

	/**
	 * Promo codes currently entered by the cashier.
	 * CART-scoped promotions with requiresCode=true will only apply if their code is here.
	 */
	private List<String> appliedCodes = new ArrayList<>();

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CartItemDTO {
		private Long itemId;
		private Integer quantity;
		private Double unitPrice;
	}
}
