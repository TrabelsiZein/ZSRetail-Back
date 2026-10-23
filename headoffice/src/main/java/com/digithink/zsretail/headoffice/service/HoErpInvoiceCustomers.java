package com.digithink.zsretail.headoffice.service;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.erp.api.ErpInvoiceCustomers;
import com.digithink.zsretail.headoffice.repository.StoreRepository;

/**
 * Item variants (ZEN), step 9: the ERP customers of the stores, for a connector that reads only their invoices (ZEN).
 * Every store with an ERP customer number, active or not, whatever it owns: the connector only tells the stores'
 * invoices from the ERP's other sales; which store receives one is decided when it is assigned (HoErpInvoiceService:
 * an inactive store, or one not supplied by the head office, waits with its reason and is assigned later). An invoice
 * left out at the read is never read again (the read goes on after the highest number saved), hence no other filter.
 */
@Component
@ConditionalOnInstallation(Installation.HEAD_OFFICE_ERP_SUPPLY)
@Transactional(readOnly = true)
public class HoErpInvoiceCustomers implements ErpInvoiceCustomers {

	private final StoreRepository stores;

	public HoErpInvoiceCustomers(StoreRepository stores) {
		this.stores = stores;
	}

	/** Trimmed, the blank ones left out, sorted (the same filter at every run). */
	@Override
	public Set<String> storeCustomerNumbers() {
		Set<String> numbers = new TreeSet<>();
		for (String number : stores.findErpCustomerNumbers()) {
			String trimmed = number == null ? "" : number.trim();
			if (!trimmed.isEmpty()) {
				numbers.add(trimmed);
			}
		}
		return numbers;
	}
}
