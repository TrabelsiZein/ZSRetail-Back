package com.digithink.zsretail.erp.navpospages.client;

import java.util.List;

import com.digithink.zsretail.erp.navpospages.dto.NavPosBarcodeRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosCategoryRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosInvoiceRow;
import com.digithink.zsretail.erp.navpospages.dto.NavPosStockRow;

/** The reads of the "POS pages" (GET only), as the changes engine uses them: the REST client, or a fake in the tests. */
public interface NavPosPagesSource {

	/** Every row of the categories page. */
	List<NavPosCategoryRow> readCategories();

	/** Every row of the items page for one stock point (its Location_Code). */
	List<NavPosStockRow> readItems(String locationCode);

	/** One page of barcodes with an Entry_No above entryNo, by Entry_No. */
	List<NavPosBarcodeRow> readBarcodesAfter(long entryNo);

	/** Every barcode of these items (a few item numbers per call). */
	List<NavPosBarcodeRow> readBarcodesOfItems(List<String> itemNos);

	/**
	 * Invoices from the ERP, step (a): the invoices whose number starts with yearPrefix (FVV26) and comes after
	 * afterNumber (null: from the first one of the year), by number, their lines expanded; at most invoices.max-per-run.
	 */
	List<NavPosInvoiceRow> readInvoicesAfter(String yearPrefix, String afterNumber);

	/** 2.2.1: the invoices of these numbers, with their lines (GET only, a few numbers per call). */
	List<NavPosInvoiceRow> readInvoicesByNumbers(List<String> numbers);

	/** Release 2.2: the lowest invoice number of the page starting with numberPrefix, or null when it has none. */
	String readFirstInvoiceNumber(String numberPrefix);

	/** The address of a page, for the communications log (no query, never a password). */
	String pageUrl(String page);
}
