package com.digithink.zsretail.erp.api;

import java.util.Set;

/**
 * Item variants (ZEN), step 9: the ERP customers whose supply invoices are the head office's to read. A connector whose
 * invoices page holds every posted sales invoice of the company (ZEN's SalesInvoice) reads only those of these
 * customers. The head office gives it (its stores' ERP customer numbers, headoffice.service.HoErpInvoiceCustomers); the
 * store assignment rules stay the head office's (HoErpInvoiceService).
 */
public interface ErpInvoiceCustomers {

	/** The ERP customer number of every store that has one (active or not), trimmed, each once; empty when none. */
	Set<String> storeCustomerNumbers();
}
