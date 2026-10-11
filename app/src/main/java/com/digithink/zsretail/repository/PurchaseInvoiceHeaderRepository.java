package com.digithink.zsretail.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.Vendor;

public interface PurchaseInvoiceHeaderRepository extends _BaseRepository<PurchaseInvoiceHeader, Long> {

	Page<PurchaseInvoiceHeader> findByVendorAndInvoiceDateBetween(Vendor vendor, LocalDate from, LocalDate to,
			Pageable pageable);

	Page<PurchaseInvoiceHeader> findByInvoiceDateBetween(LocalDate from, LocalDate to, Pageable pageable);

	Page<PurchaseInvoiceHeader> findByVendor(Vendor vendor, Pageable pageable);

	/** Step 7B: an invoice received from the head office is saved once by its number. */
	Optional<PurchaseInvoiceHeader> findByInvoiceNumber(String invoiceNumber);
}
