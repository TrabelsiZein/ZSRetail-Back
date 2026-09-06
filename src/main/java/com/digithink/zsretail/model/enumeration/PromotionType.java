package com.digithink.zsretail.model.enumeration;

/**
 * High-level promotion type — drives UI flow and POS calculation logic.
 *
 * SIMPLE_DISCOUNT   — applies a % or fixed discount to a matching item/family/subfamily,
 *                     always (no quantity threshold required).
 * QUANTITY_PROMOTION — triggers only when minimumQuantity is reached; benefit can be
 *                     a % discount, fixed discount, or free items (Buy N Get M Free).
 * CART_DISCOUNT     — applies a % or fixed discount to the whole cart total;
 *                     optionally triggered when minimumAmount is reached.
 */
public enum PromotionType {
	SIMPLE_DISCOUNT("Simple Discount"),
	QUANTITY_PROMOTION("Quantity Promotion"),
	CART_DISCOUNT("Cart Discount");

	private final String displayName;

	PromotionType(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public static PromotionType fromString(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalized = value.trim();
		for (PromotionType type : values()) {
			if (type.name().equalsIgnoreCase(normalized) || type.displayName.equalsIgnoreCase(normalized)) {
				return type;
			}
		}
		return null;
	}
}
