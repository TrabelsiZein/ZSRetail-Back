package com.digithink.zsretail.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.digithink.zsretail.model.PurchaseHeader;
import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.model.enumeration.TransactionStatus;

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

	/**
	 * Aggregate by vendor for AP summary: total purchased, total paid. Unpaid = totalPurchased - totalPaid in service.
	 * Optional date filter: dateFrom/dateTo as start of day and end of day.
	 */
	@Query("SELECT p.vendor.id, p.vendor.vendorCode, p.vendor.name, "
			+ "SUM(p.totalAmount), SUM(COALESCE(p.paidAmount, 0)) "
			+ "FROM PurchaseHeader p "
			+ "WHERE (:dateFrom IS NULL OR p.purchaseDate >= :dateFrom) "
			+ "AND (:dateTo IS NULL OR p.purchaseDate <= :dateTo) "
			+ "GROUP BY p.vendor.id, p.vendor.vendorCode, p.vendor.name")
	List<Object[]> getVendorBalanceSummary(@Param("dateFrom") LocalDateTime dateFrom, @Param("dateTo") LocalDateTime dateTo);
}
