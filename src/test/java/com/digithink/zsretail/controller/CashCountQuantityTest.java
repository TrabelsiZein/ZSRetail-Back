package com.digithink.zsretail.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 2.2.1, step 5: the cash counts of the session closing (read from a map, out of reach of the Jackson guard) stay whole:
 * 1.5 is refused naming the field, never read as 1 as until 2.2.0; 2 and 2.0 are 2.
 */
class CashCountQuantityTest {

	@Test
	@DisplayName("Cash count: 2 and 2.0 are 2 as in 2.2.0; 1.5 is refused naming the field (400 on close)")
	void wholeOnly() {
		assertEquals(Integer.valueOf(2), CashierSessionAPI.cashCountQuantity(2));
		assertEquals(Integer.valueOf(2), CashierSessionAPI.cashCountQuantity(2.0));
		assertEquals(Integer.valueOf(0), CashierSessionAPI.cashCountQuantity(0L));
		IllegalStateException refused = assertThrows(IllegalStateException.class,
				() -> CashierSessionAPI.cashCountQuantity(1.5));
		assertEquals("cashCountLines.quantity: 1.5 is not a whole number, and decimal quantities are not supported here yet.",
				refused.getMessage());
	}
}
