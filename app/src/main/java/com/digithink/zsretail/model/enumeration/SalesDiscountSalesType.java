package com.digithink.zsretail.model.enumeration;

/**
 * Sales Discount Sales Type enumeration
 * Represents the sales type for discounts in SalesDiscount entity
 */
public enum SalesDiscountSalesType {
	CUSTOMER("Customer"),
	CUSTOMER_DISC_GROUP("Customer Disc. Group"),
	ALL_CUSTOMERS("All Customers");

	private final String displayName;

	SalesDiscountSalesType(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}

	/**
	 * Convert string value to enum (case-insensitive)
	 */
	public static SalesDiscountSalesType fromString(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalized = value.trim();
		for (SalesDiscountSalesType type : values()) {
			if (type.name().equalsIgnoreCase(normalized) || 
				type.displayName.equalsIgnoreCase(normalized)) {
				return type;
			}
		}
		return null;
	}
}

