package com.digithink.zsretail.core.erp.engine;

import java.util.List;

/**
 * ERP catalogue, step 6: the few values the changes engine keeps between runs (the barcode cursor, the runs done and the
 * new rows still to save, the items that need their barcodes). Keys and values are short texts. The table
 * navpospages_state ({@link JdbcCatalogueState}), or a map in the tests.
 * Code cleanup step 3: was NavPosPagesState of the navpospages connector, now shared by the catalogue engine.
 */
public interface CatalogueState {

	/** The value, or null. */
	String get(String key);

	void put(String key, String value);

	void remove(String key);

	/** The keys that start with this prefix. */
	List<String> keysStartingWith(String prefix);
}
