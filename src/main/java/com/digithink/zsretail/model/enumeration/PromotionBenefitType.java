package com.digithink.zsretail.model.enumeration;

/**
 * Defines the type of benefit a promotion provides
 */
public enum PromotionBenefitType {
	PERCENTAGE_DISCOUNT("Percentage Discount"),
	FIXED_DISCOUNT("Fixed Discount"),
	FREE_QUANTITY("Free Quantity");

	private final String displayName;

	PromotionBenefitType(String displayName) {
		this.displayName = displayName;
	}

	public String getDisplayName() {
		return displayName;
	}

	public static PromotionBenefitType fromString(String value) {
		if (value == null || value.trim().isEmpty()) {
			return null;
		}
		String normalized = value.trim();
		for (PromotionBenefitType type : values()) {
			if (type.name().equalsIgnoreCase(normalized) || type.displayName.equalsIgnoreCase(normalized)) {
				return type;
			}
		}
		return null;
	}
}
