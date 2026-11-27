package com.digithink.zsretail.core.convention.enumeration;

/**
 * 3.6.0, conventions: the stored state of a convention (convention.status, as text). "Expired" and "not started" are
 * read from its dates, never stored. A convention is suspended, never deleted once it has a card.
 */
public enum ConventionStatus {
	ACTIVE,
	SUSPENDED
}
