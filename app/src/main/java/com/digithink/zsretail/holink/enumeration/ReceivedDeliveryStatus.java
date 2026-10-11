package com.digithink.zsretail.holink.enumeration;

/** Step 7A: a BL at the store (hol_delivery.status). See docs/modules/head-office.md, "BLs at the store". */
public enum ReceivedDeliveryStatus {

	/** Received from the head office by the copies down; the goods are expected. */
	TO_RECEIVE,

	/** The store confirmed the quantities it received; its stock went up (a line whose item is missing waits). */
	RECEIVED
}
