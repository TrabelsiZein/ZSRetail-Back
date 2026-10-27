package com.digithink.zsretail.core.dto;

import java.util.ArrayList;
import java.util.Collection;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.1.1 (store orders): what one page of NAV item lines is filtered and sorted on (NavItemService.page). Every field
 * empty: the "NAV items" page of step 11a, by number. See ItemRepository.NAV_ITEMS_WHERE.
 */
@Data
@NoArgsConstructor
public class NavItemFilter {

	public static final String SORT_NUMBER = "navItemNo";
	public static final String SORT_NAME = "name";

	/** NAV item number or name, contains, any case; blank: not filtered. */
	private String search;

	/** The items whose barcode matches the search (read by the caller); their NAV item numbers match too. */
	private Collection<Long> barcodeItemIds = new ArrayList<>();

	/** The active variants only (the default), else every variant. */
	private boolean activeOnly = true;

	private String categoryCode;
	private Long familyId;
	private Long subFamilyId;
	private String sectionCode;

	/** navItemNo (the default) or name; anything else: navItemNo. */
	private String sort;

	/** Descending; ascending by default. */
	private boolean descending;
}
