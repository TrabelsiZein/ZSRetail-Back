package com.digithink.zsretail.store.holink.enumeration;

/**
 * 3.1.1: the life of an order of the store to its head office (hol_order.status). DRAFT: the store's cart, the only one
 * that changes (one at a time); SENT: the head office answered that it has it; POSTED: in NAV (nav_order_no); ERROR: the
 * head office could not post it (error_message); CANCELLED: a draft given up. SENT, POSTED and ERROR come back from the
 * head office (copies down, domain SUPPLY). See docs/modules/store-orders.md.
 */
public enum StoreOrderStatus {
	DRAFT,
	SENT,
	POSTED,
	ERROR,
	CANCELLED
}
