package com.digithink.zsretail.erp.navpospages.client;

import java.util.List;

import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;

/** The reads of the "POS pages" (GET only), as the changes engine uses them: the REST client, or a fake in the tests. */
public interface NavPosPagesSource {

	/** Every row of the categories page. */
	List<NavPosCategoryRow> readCategories();

	/** Every row of the items page for the configured location. */
	List<NavPosStockRow> readItems();

	/** One page of barcodes with an Entry_No above entryNo, by Entry_No. */
	List<NavPosBarcodeRow> readBarcodesAfter(long entryNo);

	/** Every barcode of these items (a few item numbers per call). */
	List<NavPosBarcodeRow> readBarcodesOfItems(List<String> itemNos);

	/** The address of a page, for the communications log (no query, never a password). */
	String pageUrl(String page);
}
