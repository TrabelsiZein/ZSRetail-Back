package com.digithink.zsretail.support;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.PurchaseInvoiceLine;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.repository.PurchaseInvoiceHeaderRepository;
import com.digithink.zsretail.repository.PurchaseInvoiceLineRepository;
import com.digithink.zsretail.repository.VendorRepository;

/**
 * Test support (invoices from the ERP, step c): a store's purchase_invoice_header, purchase_invoice_line and vendor in
 * memory, as SupplyInvoiceWriter uses them.
 */
public final class InMemoryPurchaseInvoices {

	public final Map<Long, PurchaseInvoiceHeader> headers = new LinkedHashMap<>();
	public final List<PurchaseInvoiceLine> lines = new ArrayList<>();
	public final Map<Long, Vendor> vendors = new LinkedHashMap<>();
	private long nextId;

	public InMemoryPurchaseInvoices(long firstId) {
		this.nextId = firstId;
	}

	public PurchaseInvoiceHeader byNumber(String number) {
		return headers.values().stream().filter(h -> h.getInvoiceNumber().equals(number)).findFirst().orElse(null);
	}

	public List<PurchaseInvoiceLine> linesOf(PurchaseInvoiceHeader header) {
		return lines.stream().filter(l -> l.getPurchaseInvoice() == header).collect(Collectors.toList());
	}

	public PurchaseInvoiceHeaderRepository headerRepository() {
		return proxy(PurchaseInvoiceHeaderRepository.class, (method, args) -> {
			switch (method) {
				case "findByInvoiceNumber":
					return headers.values().stream().filter(h -> h.getInvoiceNumber().equals(args[0])).findFirst();
				case "save": {
					PurchaseInvoiceHeader header = (PurchaseInvoiceHeader) args[0];
					if (header.getId() == null) {
						header.setId(nextId++);
					}
					headers.put(header.getId(), header);
					return header;
				}
				default:
					return UNHANDLED;
			}
		});
	}

	public PurchaseInvoiceLineRepository lineRepository() {
		return proxy(PurchaseInvoiceLineRepository.class, (method, args) -> {
			switch (method) {
				case "save": {
					PurchaseInvoiceLine line = (PurchaseInvoiceLine) args[0];
					if (line.getId() == null) {
						line.setId(nextId++);
						lines.add(line);
					}
					return line;
				}
				case "findByPurchaseInvoice":
					return linesOf((PurchaseInvoiceHeader) args[0]);
				default:
					return UNHANDLED;
			}
		});
	}

	public VendorRepository vendorRepository() {
		return proxy(VendorRepository.class, (method, args) -> {
			switch (method) {
				case "findByVendorCode":
					return vendors.values().stream().filter(v -> args[0].equals(v.getVendorCode())).findFirst();
				case "findById":
					return Optional.ofNullable(vendors.get(args[0]));
				case "save": {
					Vendor vendor = (Vendor) args[0];
					if (vendor.getId() == null) {
						vendor.setId(nextId++);
					}
					vendors.put(vendor.getId(), vendor);
					return vendor;
				}
				default:
					return UNHANDLED;
			}
		});
	}
}
