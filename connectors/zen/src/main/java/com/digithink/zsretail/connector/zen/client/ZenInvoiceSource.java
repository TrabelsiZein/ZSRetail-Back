package com.digithink.zsretail.connector.zen.client;

import java.util.Collection;
import java.util.List;

import com.digithink.zsretail.connector.zen.dto.ZenInvoiceRow;

/**
 * Item variants (ZEN), step 9: the reads of ZEN's posted sales invoices (page salesInvoices of ZEN's franchise API, GET
 * only), as ZenInvoices uses them: the REST client, or a fake in the tests.
 */
public interface ZenInvoiceSource {

	/**
	 * The invoices of these customers whose number starts with yearPrefix (FVE26) and comes after afterNumber (null: from
	 * the first one of the year), by number, their lines expanded: one page, at most invoices.page-size (cancelled ones
	 * included). No customer: none, no GET.
	 */
	List<ZenInvoiceRow> readInvoicesAfter(String yearPrefix, String afterNumber, Collection<String> customerNumbers);

	/** The invoices of these numbers, with their lines, whatever their customer (a few numbers per GET). */
	List<ZenInvoiceRow> readInvoicesByNumbers(List<String> numbers);

	/** The lowest invoice number of these customers starting with numberPrefix, or null when there is none. */
	String readFirstInvoiceNumber(String numberPrefix, Collection<String> customerNumbers);

	/** The address of the invoices page, for the communications log (no query, never a password). */
	String invoicesPageUrl();
}
