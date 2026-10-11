package com.digithink.zsretail.core.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.core.model.Customer;
import com.digithink.zsretail.core.model.InvoiceHeader;

public interface InvoiceHeaderRepository extends _BaseRepository<InvoiceHeader, Long> {

	Page<InvoiceHeader> findByCustomerAndInvoiceDateBetween(Customer customer, LocalDate from, LocalDate to,
			Pageable pageable);

	Page<InvoiceHeader> findByInvoiceDateBetween(LocalDate from, LocalDate to, Pageable pageable);

	Page<InvoiceHeader> findByCustomer(Customer customer, Pageable pageable);

	List<InvoiceHeader> findByInvoiceNumberContainingIgnoreCase(String invoiceNumberPart);
}

