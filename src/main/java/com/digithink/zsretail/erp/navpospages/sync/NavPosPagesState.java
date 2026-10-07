package com.digithink.zsretail.erp.navpospages.sync;

import java.util.List;

/**
 * ERP catalogue, step 6: the few values the changes engine keeps between runs (the barcode cursor, the runs done and the
 * new rows still to save, the items that need their barcodes). Keys and values are short texts. The table of the
 * connector ({@link JdbcNavPosPagesState}), or a map in the tests.
 */
public interface NavPosPagesState {

	/** The value, or null. */
	String get(String key);

	void put(String key, String value);

	void remove(String key);

	/** The keys that start with this prefix. */
	List<String> keysStartingWith(String prefix);
}
