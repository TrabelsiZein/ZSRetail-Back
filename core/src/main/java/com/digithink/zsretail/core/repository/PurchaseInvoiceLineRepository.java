package com.digithink.zsretail.core.repository;

import java.util.List;

import com.digithink.zsretail.core.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.core.model.PurchaseInvoiceLine;

public interface PurchaseInvoiceLineRepository extends _BaseRepository<PurchaseInvoiceLine, Long> {

	List<PurchaseInvoiceLine> findByPurchaseInvoice(PurchaseInvoiceHeader purchaseInvoice);

	/**
	 * CMP: the lines whose description starts with this text (a store: the lines of a head office invoice that name a BL,
	 * "&lt;BL&gt; - &lt;code&gt; &lt;name&gt;", read when that BL arrives after its invoice; rare).
	 */
	List<PurchaseInvoiceLine> findByLineDescriptionStartingWith(String prefix);
}
