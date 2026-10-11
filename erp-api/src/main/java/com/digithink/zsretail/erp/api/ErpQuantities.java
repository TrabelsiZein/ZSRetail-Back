package com.digithink.zsretail.erp.api;

import java.math.BigDecimal;

/**
 * Code cleanup step 5: what a connector needs of the quantities of the application: their decimals (DECIMAL(18,3)
 * columns). The application's Quantities takes them from here, so the rule is written once.
 */
public final class ErpQuantities {

	/** Decimals of a quantity column (DECIMAL(18,3)). */
	public static final int SCALE = 3;

	private ErpQuantities() {
	}

	/** The decimals a quantity really has: 2.000 has 0, 0.25 has 2; 0 for null. */
	public static int decimals(BigDecimal quantity) {
		return quantity == null ? 0 : Math.max(0, quantity.stripTrailingZeros().scale());
	}
}
