package com.digithink.zsretail.headoffice.service;

import java.time.LocalDate;

/**
 * 3.1.1: the stores' orders an ERP invoice delivers, as the invoice import ({@link HoErpInvoiceService}) tells them:
 * implemented by {@link HoStoreOrderService}, absent on a head office that takes no store order (then nothing is
 * marked). See docs/modules/store-orders.md, "Delivered".
 */
public interface StoreOrderDeliveries {

	/**
	 * Inside the caller's transaction: every POSTED order of this ERP order number, of the invoice's customer, becomes
	 * DELIVERED with the invoice's number and posting date, and goes down to its store. Answers how many changed (0: none
	 * posted under that number, or not the invoice's customer).
	 */
	int deliveredBy(String navOrderNo, String invoiceNo, LocalDate postingDate, String customerNo);
}
