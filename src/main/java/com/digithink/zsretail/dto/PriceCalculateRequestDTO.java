package com.digithink.zsretail.dto;

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
	private Integer quantity;
	private Long customerId;

	/**
	 * Promo codes currently entered by the cashier.
	 * Only code-required promotions whose code is in this list will be considered.
	 */
	private List<String> appliedCodes = new ArrayList<>();
}
