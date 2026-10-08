package com.digithink.zsretail.headoffice.enumeration;

/**
 * Invoices from the ERP: the result of the last search of an invoice's store by its customer (ho_erp_invoice
 * .mapping_status); null while the invoice is held (no search).
 */
public enum ErpInvoiceMapping {

	/** The store whose ERP customer number is the invoice's customer: store_id set. */
	ASSIGNED,

	/** The invoice names no customer. */
	NO_CUSTOMER,

	/** No store has this ERP customer number. */
	NO_STORE,

	/** The store of this customer is inactive. */
	STORE_INACTIVE,

	/** The store of this customer reported that its goods do not come from the head office (ownership.supply). */
	STORE_NOT_SUPPLIED
}
