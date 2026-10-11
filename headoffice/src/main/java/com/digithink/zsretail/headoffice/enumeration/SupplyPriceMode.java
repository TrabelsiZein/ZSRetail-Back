package com.digithink.zsretail.headoffice.enumeration;

/** Step 7B: how the supply price of a store is worked out (ho_store.supply_price_mode; null = PRICE_LIST). */
public enum SupplyPriceMode {

	/** The line of the store's supply price list, otherwise the base supply price of the item. */
	PRICE_LIST,

	/** The selling price the head office works out for the store, minus ho_store.supply_discount_percent. */
	PERCENT_OFF
}
