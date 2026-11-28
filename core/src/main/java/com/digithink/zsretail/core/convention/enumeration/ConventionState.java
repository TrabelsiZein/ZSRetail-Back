package com.digithink.zsretail.core.convention.enumeration;

/**
 * 3.6.0, conventions step 1, part B3: what a convention is on a given day, as the list and the page show it, worked out
 * from its stored status and its dates (Convention.stateOn), never stored. SUSPENDED wins over the dates.
 */
public enum ConventionState {
	/** ACTIVE and today within its dates. */
	ACTIVE,
	SUSPENDED,
	/** ACTIVE, its end date before today. */
	EXPIRED,
	/** ACTIVE, its start date after today. */
	UPCOMING
}
