package com.digithink.zsretail.inventory.enumeration;

/** An inventory count (inventory_count.status). See docs/modules/inventory-count.md. */
public enum InventoryCountStatus {

	/** Imported, not applied: the file may be imported again, the count deleted. */
	DRAFT,

	/** Applied to the stock once; never changed or deleted afterwards. */
	VALIDATED
}
