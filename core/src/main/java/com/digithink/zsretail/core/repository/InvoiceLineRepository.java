package com.digithink.zsretail.core.repository;

import java.util.List;

import com.digithink.zsretail.core.model.InvoiceHeader;
import com.digithink.zsretail.core.model.InvoiceLine;

public interface InvoiceLineRepository extends _BaseRepository<InvoiceLine, Long> {

	List<InvoiceLine> findByInvoice(InvoiceHeader invoice);
}

