package com.digithink.zsretail.core.model.enumeration;

public enum ItemType {
	PRODUCT,
	SERVICE,
	PACKAGE,
	DISCOUNT;

	/**
	 * 3.1.2, the margins: a type that by nature carries no stock and no cost (a service, a discount item): its sale and
	 * return lines freeze a unit cost of 0, never "unknown", and the Journal counts it as cost 0. A product and a pack
	 * (its components carry the cost) are costed.
	 */
	public boolean carriesNoCost() {
		return this == SERVICE || this == DISCOUNT;
	}

	/** {@link #carriesNoCost()} of a type that may be null (null: a product). */
	public static boolean noCost(ItemType type) {
		return type != null && type.carriesNoCost();
	}
}
