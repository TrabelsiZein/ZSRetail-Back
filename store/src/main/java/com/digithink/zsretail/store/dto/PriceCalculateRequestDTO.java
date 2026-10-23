package com.digithink.zsretail.store.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for POST /price-calculate
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PriceCalculateRequestDTO {

	private Long itemId;
	/** 2.2.1: up to 3 decimals (a bulk item). */
	private BigDecimal quantity;
	private Long customerId;

	/**
	 * Promo codes currently entered by the cashier.
	 * Only code-required promotions whose code is in this list will be considered.
	 */
	private List<String> appliedCodes = new ArrayList<>();

	/**
	 * 3.1.0, promotions counted across cart lines (rule 6a): the cart's lines in cart order, this one included (its
	 * quantity is the one above; absent from the list, it counts as the first line). Absent, empty or this line alone:
	 * the line is priced alone, as before (a till of before sends none).
	 */
	private List<CartCalculateRequestDTO.CartItemDTO> cartLines = new ArrayList<>();
}
