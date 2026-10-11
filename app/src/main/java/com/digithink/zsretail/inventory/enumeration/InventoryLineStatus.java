package com.digithink.zsretail.inventory.enumeration;

/**
 * A line of an inventory count (inventory_count_line.status). Only OK lines are applied at the validation; the others
 * are kept and shown, and never block the OK lines. See docs/modules/inventory-count.md.
 */
public enum InventoryLineStatus {

	/** An item that carries a stock (a product, a pack or no type) with a whole quantity of 0 or more. */
	OK,

	/** The code is neither a barcode nor an item code here. */
	NOT_FOUND,

	/** An item without a stock (SERVICE or DISCOUNT), same rule as the stock sent to the head office. */
	NOT_COUNTED,

	/** At least one row of this item has no quantity, or one that is not a whole number of 0 or more. */
	BAD_QUANTITY
}
