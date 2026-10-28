package com.digithink.zsretail.core.erp.engine;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 3.1.1 (item images): what the images part of the catalogue job reads and writes at the head office: the NAV item
 * numbers it has, and the URL images of their variants (item_image, source URL). The JPA implementation
 * ({@link JpaHeadOfficeImages}), or maps in the tests.
 */
public interface HeadOfficeImages {

	/** Every NAV item number of the head office's items, once each, in order. */
	List<String> navItemNos();

	/**
	 * Gives every variant of each NAV item number these URL images, in order (the first is the main one; an empty list
	 * removes them): a variant whose URL images are already these is left as it is, any other has its URL rows replaced;
	 * its FILE rows are never touched. A number the head office has no item of is counted, nothing else. With write
	 * false: compared and counted only (a dry run). The variants written are sent again to the stores.
	 */
	Applied apply(Map<String, List<String>> urlsByNavItemNo, boolean write);

	/** What {@link #apply} did. */
	class Applied {
		/** Variants whose URL images were (or, in a dry run, would be) replaced. */
		public int itemsChanged;
		/** Variants that already had these images. */
		public int itemsEqual;
		/** Variants whose URL images were removed (the ERP item has none any more); counted in itemsChanged too. */
		public int itemsCleared;
		/** NAV item numbers answered that the head office has no item of. */
		public final Set<String> unknown = new TreeSet<>();

		public void add(Applied other) {
			itemsChanged += other.itemsChanged;
			itemsEqual += other.itemsEqual;
			itemsCleared += other.itemsCleared;
			unknown.addAll(other.unknown);
		}
	}
}
