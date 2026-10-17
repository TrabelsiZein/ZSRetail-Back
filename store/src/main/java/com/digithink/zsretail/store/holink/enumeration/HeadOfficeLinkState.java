package com.digithink.zsretail.store.holink.enumeration;

/** State of the store's link to its head office (task 1.4). See docs/modules/head-office.md, "Head office link". */
public enum HeadOfficeLinkState {

	/** No heartbeat yet since the start. */
	PENDING,

	/** The head office answered 200. */
	ONLINE,

	/** Connection error or timeout: the head office is not reachable. */
	OFFLINE,
	/**
	 * 3.1.0, the pull of copies down only: the head office took the call but did not answer within the read timeout
	 * (headoffice.copies-down.read-timeout-seconds). It is reachable, so not OFFLINE. The heartbeat never gives it.
	 */
	TIMEOUT,

	/** The head office is reachable but refuses the call: 401 (store code or key) or 402 (head office license). */
	REFUSED,

	/** Any other answer: another HTTP status, or a 200 whose body cannot be read. */
	ERROR,

	/** DEFAULT_LOCATION is empty in the general setup: no call is made. */
	NOT_CONFIGURED
}
