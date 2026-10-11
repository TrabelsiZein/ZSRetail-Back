package com.digithink.zsretail.service;

import lombok.Data;

/**
 * Snapshot data passed by caller before invoice creation (editable customer or vendor info): the customer invoices
 * (InvoiceService) and the purchase invoices (PurchaseInvoiceService). Code cleanup step 5: was nested in
 * InvoiceService (the store's customer invoices), the purchase invoices are core.
 */
@Data
public class InvoiceSnapshotData {
	private String name;
	private String address;
	private String phone;
	private String taxRegNo;
}
