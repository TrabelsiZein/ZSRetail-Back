package com.digithink.zsretail.core.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Repository;

import com.digithink.zsretail.core.model.PurchaseHeader;
import com.digithink.zsretail.core.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.core.model.Vendor;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;

@Repository
public interface PurchaseHeaderRepository extends _BaseRepository<PurchaseHeader, Long> {

	Optional<PurchaseHeader> findByPurchaseNumber(String purchaseNumber);

	/**
	 * Find completed purchases for a vendor in a date range (for eligible-purchases).
	 * Service filters by purchaseInvoice == null and !invoiced.
	 */
	List<PurchaseHeader> findByVendorAndPurchaseDateBetweenAndStatus(
			Vendor vendor, LocalDateTime from, LocalDateTime to, TransactionStatus status);

	List<PurchaseHeader> findByPurchaseInvoiceOrderByPurchaseDateAsc(PurchaseInvoiceHeader purchaseInvoice);
}
