package com.digithink.zsretail.holink.service;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSupply;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.repository.VendorRepository;

/**
 * Head office plan, step 7B: on a store whose goods come from the head office, the vendor HEAD_OFFICE (created by
 * {@link SupplyInvoiceWriter} for the head office's invoices) is consult-only, its code is kept, and it takes no
 * purchase or purchase invoice made by hand. Looked up by VendorAPI, PurchaseHeaderAPI and PurchaseInvoiceAPI with an
 * ObjectProvider: absent on every other installation, where vendors and purchases answer as before. Controllers only:
 * SupplyInvoiceWriter keeps saving the head office's invoices on that vendor.
 */
@Component
@ConditionalOnHeadOfficeSupply
public class SupplyVendorGuard {

	static final String CODE_KEPT = "The vendor code " + SupplyInvoiceWriter.VENDOR_CODE
			+ " is kept for the head office's invoices.";
	static final String CONSULT_ONLY = "The head office vendor is consult-only: it holds the invoices of the head office.";
	static final String NO_PURCHASE = "No purchase by hand on the head office vendor: it holds only the invoices of the head office.";

	private final VendorRepository vendors;

	public SupplyVendorGuard(VendorRepository vendors) {
		this.vendors = vendors;
	}

	/** Creating: the refusal text, or null. */
	public String create(Vendor input) {
		return input != null && SupplyInvoiceWriter.isHeadOfficeVendor(input) ? CODE_KEPT : null;
	}

	/** Updating vendor id with this body: the refusal text, or null. */
	public String update(Long id, Vendor input) {
		String stored = delete(id);
		if (stored != null) {
			return stored;
		}
		return create(input);
	}

	/** Deleting (or changing) vendor id: the refusal text, or null. */
	public String delete(Long id) {
		return id != null && vendors.findById(id).filter(SupplyInvoiceWriter::isHeadOfficeVendor).isPresent()
				? CONSULT_ONLY
				: null;
	}

	/** A purchase (or purchase invoice) made by hand on vendor id: the refusal text, or null. */
	public String purchase(Long vendorId) {
		return delete(vendorId) != null ? NO_PURCHASE : null;
	}
}
