package com.digithink.zsretail.headoffice.enumeration;

/**
 * Status of a store on the head office Stores page (task 1.5), computed on each read with the head office clock;
 * never stored. See docs/modules/head-office.md.
 */
public enum StoreStatus {

	/** The store is deactivated (active = false), whatever its last contact. */
	INACTIVE,

	/** Active, no heartbeat yet. */
	NEVER,

	/** Active, last heartbeat no older than headoffice.offline-after-seconds. */
	ONLINE,

	/** Active, last heartbeat older than headoffice.offline-after-seconds. */
	OFFLINE
}
