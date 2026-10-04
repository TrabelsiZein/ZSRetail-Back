package com.digithink.zsretail.headoffice.enumeration;

/** Step 7B: what a price list of the head office holds (ho_price_list.kind; null = SELLING, every list made before). */
public enum PriceListKind {

	/** Selling prices, worked out per store and sent with the items (step 6). */
	SELLING,

	/** Supply prices: what a store whose deliveries are invoiced pays (step 7B). Never sent to a store. */
	SUPPLY
}
