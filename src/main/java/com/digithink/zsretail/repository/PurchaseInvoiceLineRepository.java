package com.digithink.zsretail.repository;

import java.util.List;

import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.PurchaseInvoiceLine;

public interface PurchaseInvoiceLineRepository extends _BaseRepository<PurchaseInvoiceLine, Long> {

	List<PurchaseInvoiceLine> findByPurchaseInvoice(PurchaseInvoiceHeader purchaseInvoice);
}
