package com.digithink.zsretail.erp.api;

/**
 * Code cleanup step 5: where a connector starts reading the ERP's supply invoices when the head office has none yet: the
 * General Setup "Read ERP invoices after number" (empty: every invoice of the page). The application gives it (its head
 * office tables, HeadOfficeCatalogue); the setting code lets a connector check the value before it is saved.
 */
public interface ErpInvoiceStart {

	/** The General Setup code of "Read ERP invoices after number". */
	String INVOICES_READ_AFTER = "ERP_INVOICES_READ_AFTER";

	/** The General Setup value, trimmed; null when empty. */
	String invoicesReadAfter();
}
