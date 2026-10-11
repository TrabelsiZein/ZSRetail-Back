package com.digithink.zsretail.model.enumeration;

/**
 * Defines how invoice lines are built when aggregating multiple tickets.
 */
public enum InvoiceLineGroupingMode {

	/**
	 * One line per distinct item (product/service), quantities and amounts summed.
	 */
	BY_ITEM,

	/**
	 * One line per item family, using totals of all items in that family.
	 */
	BY_FAMILY,

	/**
	 * One line per item sub-family, using totals of all items in that sub-family.
	 */
	BY_SUBFAMILY,

	/**
	 * No aggregation: one invoice line per original sales line.
	 */
	NO_GROUPING
}

