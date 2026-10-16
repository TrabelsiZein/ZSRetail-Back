package com.digithink.zsretail.connector.zen.client;

import java.time.OffsetDateTime;
import java.util.List;

import com.digithink.zsretail.connector.zen.dto.ZenBarcodeRow;
import com.digithink.zsretail.connector.zen.dto.ZenCategoryRow;
import com.digithink.zsretail.connector.zen.dto.ZenStockPointRow;

/** The reads of ZEN's API (GET only), as the catalogue engine uses them: the REST client, or a fake in the tests. */
public interface ZenSource {

	/** Every row of the tree of the item categories. */
	List<ZenCategoryRow> readCategories();

	/** The rows of one location (a point de stock); since null: every row, else those modified at or after since. */
	List<ZenStockPointRow> readStockPoints(String locationCode, OffsetDateTime since);

	/**
	 * Step 12a: one page of barcodes whose position (lastModifiedDateTime in epoch milliseconds, ZenMapper.position) is
	 * above this one (0: every row), by lastModifiedDateTime; a full page ends with every row of its last millisecond.
	 */
	List<ZenBarcodeRow> readBarcodesAfter(long position);

	/** Every barcode of these items and variants (a few per call). */
	List<ZenBarcodeRow> readBarcodesOfItems(List<String> itemCodes);

	/** The address of a page, for the communications log (no query, never a password). */
	String pageUrl(String page);
}
