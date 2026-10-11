package com.digithink.zsretail.headoffice.enumeration;

/** Invoices from the ERP: where an invoice of the ERP stands at the head office (ho_erp_invoice.status). */
public enum ErpInvoiceStatus {

	/** Read from the ERP and saved; no store yet (held, or its customer matches no store). */
	READ,

	/** Given to its store (store_id set); step (c) sends it with the copies down. */
	SENT,

	/** The store confirmed the quantities it received. */
	RECEIVED
}
