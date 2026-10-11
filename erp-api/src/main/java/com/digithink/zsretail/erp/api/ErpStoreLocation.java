package com.digithink.zsretail.erp.api;

import java.util.List;

/**
 * Code cleanup step 5.8d: what a connector reads of the store's location, answered by the application (in core): the
 * General Setup DEFAULT_LOCATION and the responsibility centres of the locations marked default.
 */
public interface ErpStoreLocation {

	/** General Setup DEFAULT_LOCATION, as stored: null when there is none. */
	String defaultLocationCode();

	/**
	 * The responsibility centre of each location marked default, in the order the application reads them (a centre may
	 * be null); empty when no location is marked default.
	 */
	List<String> defaultResponsibilityCenters();
}
