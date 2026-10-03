package com.digithink.zsretail.holink.enumeration;

/** State of the store's link to its head office (task 1.4). See docs/modules/head-office.md, "Head office link". */
public enum HeadOfficeLinkState {

	/** No heartbeat yet since the start. */
	PENDING,

	/** The head office answered 200. */
	ONLINE,

	/** Connection error or timeout: the head office is not reachable. */
	OFFLINE,

	/** The head office is reachable but refuses the call: 401 (store code or key) or 402 (head office license). */
	REFUSED,

	/** Any other answer: another HTTP status, or a 200 whose body cannot be read. */
	ERROR,

	/** DEFAULT_LOCATION is empty in the general setup: no call is made. */
	NOT_CONFIGURED
}
