package com.digithink.zsretail.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.model.Customer;
import com.digithink.zsretail.model.InvoiceHeader;

public interface InvoiceHeaderRepository extends _BaseRepository<InvoiceHeader, Long> {

	Page<InvoiceHeader> findByCustomerAndInvoiceDateBetween(Customer customer, LocalDate from, LocalDate to,
			Pageable pageable);

	Page<InvoiceHeader> findByInvoiceDateBetween(LocalDate from, LocalDate to, Pageable pageable);

	Page<InvoiceHeader> findByCustomer(Customer customer, Pageable pageable);

	List<InvoiceHeader> findByInvoiceNumberContainingIgnoreCase(String invoiceNumberPart);

	/**
	 * Franchise: returns invoices tagged for the given location code that have not yet
	 * been acknowledged (received) by the franchise client.
	 */
	List<InvoiceHeader> findByFranchiseLocationCodeAndFranchiseReceivedAtIsNull(String franchiseLocationCode);

	/**
	 * Franchise: returns all invoices tagged with the given location code (received and pending).
	 */
	Page<InvoiceHeader> findByFranchiseLocationCodeIsNotNull(Pageable pageable);
}

