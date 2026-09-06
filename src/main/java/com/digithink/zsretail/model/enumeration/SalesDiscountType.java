package com.digithink.zsretail.model.enumeration;

/**
 * Sales Discount Type enumeration Represents the type of discount in
 * SalesDiscount entity
 */
public enum SalesDiscountType {
	ITEM("Item"), ITEM_DISC_GROUP("Item Disc. Group");

	private final String displayName;

	SalesDiscountType(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}

	/**
	 * Convert string value to enum (case-insensitive)
	 */
	public static SalesDiscountType fromString(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalized = value.trim();
		for (SalesDiscountType type : values()) {
			if (type.name().equalsIgnoreCase(normalized) || type.displayName.equalsIgnoreCase(normalized)) {
				return type;
			}
		}
		return null;
	}
}
