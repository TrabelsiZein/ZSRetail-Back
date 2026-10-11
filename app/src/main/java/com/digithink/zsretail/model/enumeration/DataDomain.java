package com.digithink.zsretail.model.enumeration;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Kinds of data that have exactly one owner (head office design 2.2), with the property key that
 * sets the owner and the owners allowed for the domain. Not used by the application yet.
 */
public enum DataDomain {
	CATALOGUE("ownership.catalogue", DataOwner.LOCAL, DataOwner.HEAD_OFFICE, DataOwner.ERP),
	CUSTOMERS("ownership.customers", DataOwner.LOCAL, DataOwner.HEAD_OFFICE, DataOwner.ERP),
	PROMOTIONS("ownership.promotions", DataOwner.LOCAL, DataOwner.HEAD_OFFICE),
	LOYALTY("ownership.loyalty", DataOwner.LOCAL, DataOwner.HEAD_OFFICE),
	SUPPLY("ownership.supply", DataOwner.LOCAL, DataOwner.HEAD_OFFICE, DataOwner.ERP);

	private final String propertyKey;
	private final Set<DataOwner> allowedOwners;

	DataDomain(String propertyKey, DataOwner first, DataOwner... rest) {
		this.propertyKey = propertyKey;
		this.allowedOwners = Collections.unmodifiableSet(EnumSet.of(first, rest));
	}

	public String getPropertyKey() {
		return propertyKey;
	}

	public Set<DataOwner> getAllowedOwners() {
		return allowedOwners;
	}

	public boolean allows(DataOwner owner) {
		return allowedOwners.contains(owner);
	}
}
