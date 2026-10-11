package com.digithink.zsretail.model.enumeration;

/**
 * Defines what the promotion targets (scope of application)
 */
public enum PromotionScope {
	ITEM("Item"),
	ITEM_GROUP("Item Group"),
	ITEM_FAMILY("Item Family"),
	ITEM_SUBFAMILY("Item SubFamily"),
	/** Every item, no target. Least specific item-level scope: used only when no other scope matched. */
	ALL_ITEMS("All Items"),
	CART("Cart");

	private final String displayName;

	PromotionScope(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public static PromotionScope fromString(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalized = value.trim();
		for (PromotionScope scope : values()) {
			if (scope.name().equalsIgnoreCase(normalized) || scope.displayName.equalsIgnoreCase(normalized)) {
				return scope;
			}
		}
		return null;
	}
}
