package com.digithink.zsretail.holink.enumeration;

/**
 * A line of a received document (hol_delivery_line.line_type; null = ITEM, every BL line): an item (stock, and cost on an
 * ERP invoice), or another line of an ERP invoice with an amount and no item (no quantity, no stock, no cost).
 */
public enum ReceivedLineType {
	ITEM, OTHER
}
