package com.digithink.zsretail.holink.enumeration;

/**
 * What a document received from the head office is (hol_delivery.document_kind; null = BL, every row before step c):
 * a BL of the head office, or an invoice of the ERP (invoices from the ERP), which brings stock, cost and the purchase
 * invoice in one reception.
 */
public enum ReceivedDocumentKind {
	BL, ERP_INVOICE
}
