package com.digithink.zsretail.headoffice.enumeration;

/**
 * 3.1.1: a store's order at the head office (ho_store_order.status). SENT: received, waiting for the ERP; POSTED: in the
 * ERP (nav_order_no); ERROR: the posting failed (error_message), retried by the job in ERP mode; REJECTED: refused by
 * the head office (error_message), never posted. The store sees SENT, POSTED and ERROR (REJECTED goes down as ERROR with
 * its message). See docs/modules/store-orders.md.
 */
public enum HoStoreOrderStatus {
	SENT,
	POSTED,
	ERROR,
	REJECTED
}
