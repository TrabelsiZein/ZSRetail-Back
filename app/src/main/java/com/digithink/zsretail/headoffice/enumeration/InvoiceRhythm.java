package com.digithink.zsretail.headoffice.enumeration;

/** Step 7B: when a store's received BLs are invoiced (ho_store.invoice_rhythm; null = PER_BL). */
public enum InvoiceRhythm {

	/** One invoice per BL, created automatically when the store's confirmation arrives. */
	PER_BL,

	/** Several received BLs in one invoice, created by hand at the head office. */
	GROUPED
}
