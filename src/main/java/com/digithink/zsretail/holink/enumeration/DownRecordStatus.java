package com.digithink.zsretail.holink.enumeration;

/** State at the store of a record received from the head office (task 3.5). */
public enum DownRecordStatus {

	/** Saved as received (an ITEM_GROUP promotion may miss some items: see the row's info). */
	APPLIED,

	/** Not applied: a record it targets (item, family, sub-family, benefit item) is not in this store yet. Retried at every cycle. */
	WAITING,

	/** Not applied for another reason (e.g. its code is used by a local record). Retried at every cycle. */
	ERROR
}
