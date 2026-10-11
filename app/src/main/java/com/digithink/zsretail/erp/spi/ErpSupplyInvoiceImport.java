package com.digithink.zsretail.erp.spi;

/**
 * Invoices from the ERP: what the job IMPORT_SUPPLY_INVOICES runs (ErpSyncJobRunner). Implemented on a head office with
 * headoffice.supply.source=ERP only (HoErpInvoiceService): it reads the new invoices of the ERP, saves them, and gives
 * each one its store. Absent everywhere else: the runner then answers with a warning.
 */
public interface ErpSupplyInvoiceImport {

	/** One run: read and save the new invoices, then give the invoices without a store their store. */
	void importSupplyInvoices();
}
