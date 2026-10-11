package com.digithink.zsretail.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of pricing calculation Contains the final price, discount, and source
 * information
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingResult {
	/**
	 * Final unit price to use (always HT - excluding VAT) If the original price was
	 * TTC, it has been converted to HT in PricingService
	 */
	private Double unitPrice;

	/**
	 * Whether the original price included VAT (true = was TTC, false = was HT) This
	 * is kept for reference/audit purposes. The unitPrice is always HT.
	 */
	private Boolean priceIncludesVat;

	/**
	 * Discount percentage from SalesDiscount (if applicable)
	 */
	private Double discountPercentage;

	/**
	 * Source of the price: "ITEM", "SALES_PRICE", etc. (for debugging/audit)
	 */
	private String source;
}
