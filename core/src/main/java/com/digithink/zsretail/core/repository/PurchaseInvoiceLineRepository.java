package com.digithink.zsretail.core.repository;

import java.util.List;

import com.digithink.zsretail.core.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.core.model.PurchaseInvoiceLine;

public interface PurchaseInvoiceLineRepository extends _BaseRepository<PurchaseInvoiceLine, Long> {

	List<PurchaseInvoiceLine> findByPurchaseInvoice(PurchaseInvoiceHeader purchaseInvoice);
}
