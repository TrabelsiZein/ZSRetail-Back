package com.digithink.zsretail.repository;

import java.util.List;

import com.digithink.zsretail.model.InvoiceHeader;
import com.digithink.zsretail.model.InvoiceLine;

public interface InvoiceLineRepository extends _BaseRepository<InvoiceLine, Long> {

	List<InvoiceLine> findByInvoice(InvoiceHeader invoice);
}

