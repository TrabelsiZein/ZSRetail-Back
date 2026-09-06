package com.digithink.zsretail.model.enumeration;

/**
 * Sales Price Type enumeration Represents the type of sales price in SalesPrice
 * entity
 */
public enum SalesPriceType {
	CUSTOMER("Customer"), CUSTOMER_PRICE_GROUP("Customer Price Group"), ALL_CUSTOMERS("All Customers");

	private final String displayName;

	SalesPriceType(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}

	/**
	 * Convert string value to enum (case-insensitive)
	 */
	public static SalesPriceType fromString(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalized = value.trim();
		for (SalesPriceType type : values()) {
			if (type.name().equalsIgnoreCase(normalized) || type.displayName.equalsIgnoreCase(normalized)) {
				return type;
			}
		}
		return null;
	}
}
