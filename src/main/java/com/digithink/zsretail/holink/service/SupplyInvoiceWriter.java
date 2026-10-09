package com.digithink.zsretail.holink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSupply;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceCopyDTO;
import com.digithink.zsretail.holink.model.ReceivedDelivery;
import com.digithink.zsretail.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.holink.repository.ReceivedDeliveryRepository;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.PurchaseInvoiceLine;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.InvoiceLineGroupingMode;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.PurchaseInvoiceHeaderRepository;
import com.digithink.zsretail.repository.PurchaseInvoiceLineRepository;
import com.digithink.zsretail.repository.VendorRepository;
import com.digithink.zsretail.utils.Quantities;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 7B, part 3: an invoice of the head office arrives at the store as a purchase invoice, once by
 * its number (purchase_invoice_header / purchase_invoice_line, origin HEAD_OFFICE, consult-only: the purchase invoice
 * API has no edit or delete, and no purchase_header is linked, so the store can never invoice it again). Vendor
 * HEAD_OFFICE, created at the first invoice. Totals copied, never recomputed. Each line names this store's head office
 * item (origin HEAD_OFFICE; the tax stamp: the store's TAX_STAMP item); an item not here: a line without item, its code
 * and name in the description. The supply price becomes the cost of each head office item (lastDirectCost,
 * lastDirectNetCost and costPrice); the store's own items are never touched. The BLs of the invoice get its number.
 * <p>
 * Invoices from the ERP, step (c): {@link #saveFromErpInvoice} writes the purchase invoice of a received ERP invoice and
 * no cost (the reception writes it, with the stock); {@link #attachItems} gives its lines the items that arrive later.
 */
@Component
@ConditionalOnHeadOfficeSupply
@Log4j2
public class SupplyInvoiceWriter {

	public static final String VENDOR_CODE = "HEAD_OFFICE";
	static final String WRITER = "HEAD_OFFICE";

	private final PurchaseInvoiceHeaderRepository headers;
	private final PurchaseInvoiceLineRepository lines;
	private final VendorRepository vendors;
	private final ItemRepository items;
	private final ReceivedDeliveryRepository deliveries;

	public SupplyInvoiceWriter(PurchaseInvoiceHeaderRepository headers, PurchaseInvoiceLineRepository lines,
			VendorRepository vendors, ItemRepository items, ReceivedDeliveryRepository deliveries) {
		this.headers = headers;
		this.lines = lines;
		this.vendors = vendors;
		this.items = items;
		this.deliveries = deliveries;
	}

	/** Written or not (already here), and the item codes not in this store. */
	public static final class Outcome {
		private final boolean written;
		private final List<String> missingItems;

		Outcome(boolean written, List<String> missingItems) {
			this.written = written;
			this.missingItems = missingItems;
		}

		public boolean isWritten() {
			return written;
		}

		public List<String> getMissingItems() {
			return missingItems;
		}
	}

	/** Saves the invoice once by its number; called inside the handler's transaction. */
	@Transactional(rollbackFor = Exception.class)
	public Outcome save(SupplyInvoiceCopyDTO copy) {
		if (copy.getInvoiceNumber() == null || copy.getInvoiceNumber().trim().isEmpty()) {
			throw new IllegalArgumentException("invoice without a number");
		}
		if (headers.findByInvoiceNumber(copy.getInvoiceNumber()).isPresent()) {
			return new Outcome(false, new ArrayList<>());
		}
		Vendor vendor = vendor(copy);
		PurchaseInvoiceHeader header = new PurchaseInvoiceHeader();
		header.setInvoiceNumber(copy.getInvoiceNumber());
		header.setInvoiceDate(copy.getInvoiceDate() == null ? LocalDate.now() : LocalDate.parse(copy.getInvoiceDate()));
		header.setVendor(vendor);
		header.setLineGroupingMode(InvoiceLineGroupingMode.NO_GROUPING);
		header.setSubtotal(copy.getSubtotal());
		header.setTaxAmount(copy.getTaxAmount());
		header.setTotalAmount(copy.getTotalAmount());
		header.setNotes(notes(copy));
		header.setSnapshotVendorName(copy.getSellerName() != null ? copy.getSellerName() : vendor.getName());
		header.setSnapshotVendorAddress(copy.getSellerAddress());
		header.setSnapshotVendorPhone(vendor.getPhone());
		header.setSnapshotVendorTaxRegNo(copy.getSellerTaxNumber());
		header.setOrigin(RecordOrigin.HEAD_OFFICE);
		header.setCreatedBy(WRITER);
		header.setUpdatedBy(WRITER);
		PurchaseInvoiceHeader saved = headers.save(header);
		Set<String> missing = new LinkedHashSet<>();
		for (SupplyInvoiceCopyDTO.Line copyLine : copy.getLines()) {
			Optional<Item> item = itemOf(copyLine.getItemCode());
			if (!item.isPresent()) {
				missing.add(copyLine.getItemCode());
			}
			lines.save(line(saved, copyLine, item.orElse(null)));
			if (item.isPresent() && item.get().getOrigin() == RecordOrigin.HEAD_OFFICE && copyLine.getUnitPrice() != null) {
				Item cost = item.get();
				cost.setLastDirectCost(copyLine.getUnitPrice());
				cost.setLastDirectNetCost(copyLine.getUnitPrice());
				cost.setCostPrice(copyLine.getUnitPrice());
				cost.setUpdatedBy(WRITER);
				items.save(cost);
			}
		}
		for (String number : copy.getDeliveryNumbers()) {
			deliveries.findByNumber(number).ifPresent(delivery -> {
				delivery.setInvoiceNumber(copy.getInvoiceNumber());
				deliveries.save(delivery);
			});
		}
		log.info("Head office link: supply invoice {} received as a purchase invoice ({} lines, total {})",
				saved.getInvoiceNumber(), copy.getLines().size(), copy.getTotalAmount());
		return new Outcome(true, new ArrayList<>(missing));
	}

	/** The vendor HEAD_OFFICE, created with the seller's name and tax number at the first invoice. */
	private Vendor vendor(SupplyInvoiceCopyDTO copy) {
		return vendor(copy.getSellerName(), copy.getSellerAddress(), copy.getSellerTaxNumber());
	}

	/** The vendor HEAD_OFFICE as it is (its name kept), or created with this seller at the first invoice. */
	private Vendor vendor(String sellerName, String sellerAddress, String sellerTaxNumber) {
		return vendors.findByVendorCode(VENDOR_CODE).orElseGet(() -> {
			Vendor vendor = new Vendor();
			vendor.setVendorCode(VENDOR_CODE);
			vendor.setName(sellerName != null && !sellerName.trim().isEmpty() ? sellerName.trim() : "Head office");
			vendor.setPhone("N/A");
			vendor.setAddress(sellerAddress);
			vendor.setTaxRegistrationNo(sellerTaxNumber);
			vendor.setActive(true);
			vendor.setCreatedBy(WRITER);
			vendor.setUpdatedBy(WRITER);
			return vendors.save(vendor);
		});
	}

	// ─── Invoices from the ERP, step (c) ─────────────────────────

	/**
	 * The purchase invoice of an ERP invoice the store has just received, in the reception's transaction, once by its
	 * number; writes no cost (the reception does). Vendor HEAD_OFFICE (created with the seller's name when absent, kept as
	 * it is otherwise), origin HEAD_OFFICE, consult-only like the head office's invoices. The ERP's number and date, its
	 * three totals copied. One line per invoice line, as invoiced (never the quantities received): an ITEM line with this
	 * store's head office item, or without item when it is not here (code and name in the description: the item is
	 * attached later, {@link #attachItems}); quantity invoiced, unit price = the unit cost (the net price paid), the item's
	 * VAT when known, line totals from the line amount. An OTHER line: no item, quantity 1, its amount.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Outcome saveFromErpInvoice(ReceivedDelivery invoice) {
		if (headers.findByInvoiceNumber(invoice.getNumber()).isPresent()) {
			return new Outcome(false, new ArrayList<>());
		}
		Vendor vendor = vendor(invoice.getSellerName(), null, null);
		PurchaseInvoiceHeader header = new PurchaseInvoiceHeader();
		header.setInvoiceNumber(invoice.getNumber());
		header.setInvoiceDate(invoice.getDocumentDate() == null ? LocalDate.now() : invoice.getDocumentDate());
		header.setVendor(vendor);
		header.setLineGroupingMode(InvoiceLineGroupingMode.NO_GROUPING);
		header.setSubtotal(invoice.getTotalExclVat());
		header.setTaxAmount(invoice.getTotalVat());
		header.setTotalAmount(invoice.getTotalInclVat());
		String seller = invoice.getSellerName() != null ? invoice.getSellerName() : vendor.getName();
		header.setNotes("ERP invoice " + invoice.getNumber() + " - " + seller);
		header.setSnapshotVendorName(seller);
		header.setSnapshotVendorAddress(vendor.getAddress());
		header.setSnapshotVendorPhone(vendor.getPhone());
		header.setSnapshotVendorTaxRegNo(vendor.getTaxRegistrationNo());
		header.setOrigin(RecordOrigin.HEAD_OFFICE);
		header.setCreatedBy(WRITER);
		header.setUpdatedBy(WRITER);
		PurchaseInvoiceHeader saved = headers.save(header);
		Set<String> missing = new LinkedHashSet<>();
		for (ReceivedDeliveryLine source : invoice.getLines()) {
			Item item = source.isItemLine() && source.getItemId() != null ? items.findById(source.getItemId()).orElse(null)
					: null;
			if (source.isItemLine() && item == null) {
				missing.add(source.getItemCode());
			}
			lines.save(erpLine(saved, source, item));
		}
		log.info("Head office link: ERP invoice {} received as a purchase invoice ({} lines, total {})",
				saved.getInvoiceNumber(), invoice.getLines().size(), invoice.getTotalInclVat());
		return new Outcome(true, new ArrayList<>(missing));
	}

	/**
	 * The lines of the purchase invoice of this received ERP invoice that have no item get the item their received line
	 * has now (the catalogue brought it); matched by the code at the start of the description. Nothing when there is no
	 * purchase invoice yet.
	 */
	@Transactional(rollbackFor = Exception.class)
	public int attachItems(ReceivedDelivery invoice) {
		Optional<PurchaseInvoiceHeader> header = headers.findByInvoiceNumber(invoice.getNumber());
		if (!header.isPresent()) {
			return 0;
		}
		Map<String, Long> itemIds = new HashMap<>();
		for (ReceivedDeliveryLine line : invoice.getLines()) {
			if (line.isItemLine() && line.getItemId() != null) {
				itemIds.put(line.getItemCode(), line.getItemId());
			}
		}
		int attached = 0;
		for (PurchaseInvoiceLine line : lines.findByPurchaseInvoice(header.get())) {
			if (line.getItem() != null || line.getLineDescription() == null) {
				continue;
			}
			String code = line.getLineDescription().split(" ", 2)[0];
			Long itemId = itemIds.get(code);
			Item item = itemId == null ? null : items.findById(itemId).orElse(null);
			if (item != null) {
				line.setItem(item);
				line.setUpdatedBy(WRITER);
				lines.save(line);
				attached++;
			}
		}
		return attached;
	}

	private static PurchaseInvoiceLine erpLine(PurchaseInvoiceHeader header, ReceivedDeliveryLine source, Item item) {
		PurchaseInvoiceLine line = new PurchaseInvoiceLine();
		line.setPurchaseInvoice(header);
		line.setItem(item);
		double amount = source.getLineAmount() == null ? 0 : source.getLineAmount();
		if (source.isItemLine()) {
			line.setLineDescription(source.getItemCode() + (source.getItemName() == null ? "" : " " + source.getItemName()));
			line.setQuantity(source.getQuantitySent() == null ? BigDecimal.ZERO : source.getQuantitySent()); // 2.2.1: as invoiced, 1.5 stays 1.5
			line.setUnitPrice(source.getUnitCost() == null ? 0.0 : source.getUnitCost());
		} else {
			line.setLineDescription(source.getItemName());
			line.setQuantity(BigDecimal.ONE);
			line.setUnitPrice(amount);
		}
		Integer vat = item == null ? null : item.getDefaultVAT();
		line.setVatPercent(vat);
		line.setSubtotal(amount);
		if (vat != null) {
			double rate = 1 + vat / 100.0;
			line.setTaxAmount(round3(amount * vat / 100.0));
			line.setTotalAmount(round3(amount * rate));
			line.setLineTotalIncludingVat(round3(amount * rate));
			line.setUnitPriceIncludingVat(round3(line.getUnitPrice() * rate));
		} else {
			line.setTotalAmount(amount);
			line.setUnitPriceIncludingVat(line.getUnitPrice());
		}
		line.setCreatedBy(WRITER);
		line.setUpdatedBy(WRITER);
		return line;
	}

	private static double round3(double value) {
		return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
	}

	/** The tax stamp: the store's TAX_STAMP item; otherwise this store's head office item with that code. */
	private Optional<Item> itemOf(String itemCode) {
		if (itemCode == null) {
			return Optional.empty();
		}
		if (CatalogueKind.TAX_STAMP_CODE.equals(itemCode)) {
			return items.findByItemCode(itemCode);
		}
		return items.findByItemCode(itemCode).filter(item -> item.getOrigin() == RecordOrigin.HEAD_OFFICE);
	}

	private static PurchaseInvoiceLine line(PurchaseInvoiceHeader header, SupplyInvoiceCopyDTO.Line copy, Item item) {
		PurchaseInvoiceLine line = new PurchaseInvoiceLine();
		line.setPurchaseInvoice(header);
		line.setItem(item);
		String description = (copy.getDeliveryNumber() == null ? "" : copy.getDeliveryNumber() + " - ")
				+ copy.getItemCode() + (copy.getItemName() == null ? "" : " " + copy.getItemName());
		line.setLineDescription(description);
		line.setQuantity(copy.getQuantity() == null ? BigDecimal.ZERO : Quantities.of(copy.getQuantity()));
		line.setUnitPrice(copy.getUnitPrice());
		int quantity = copy.getQuantity() == null ? 0 : copy.getQuantity();
		line.setUnitPriceIncludingVat(quantity > 0 && copy.getLineTotalIncludingVat() != null
				? copy.getLineTotalIncludingVat() / quantity
				: copy.getUnitPrice());
		line.setSubtotal(copy.getLineTotal());
		line.setTaxAmount(copy.getVatAmount());
		line.setTotalAmount(copy.getLineTotalIncludingVat());
		line.setLineTotalIncludingVat(copy.getLineTotalIncludingVat());
		line.setVatPercent(copy.getVatPercent());
		line.setCreatedBy(WRITER);
		line.setUpdatedBy(WRITER);
		return line;
	}

	private static String notes(SupplyInvoiceCopyDTO copy) {
		StringBuilder notes = new StringBuilder("Head office invoice");
		if (!copy.getDeliveryNumbers().isEmpty()) {
			notes.append(" - BL ").append(String.join(", ", copy.getDeliveryNumbers()));
		}
		if (copy.getNote() != null && !copy.getNote().trim().isEmpty()) {
			notes.append(" - ").append(copy.getNote().trim());
		}
		return notes.toString();
	}

	/** True when this is the head office vendor (its rows are consult-only). */
	public static boolean isHeadOfficeVendor(Vendor vendor) {
		return vendor != null && VENDOR_CODE.equalsIgnoreCase(vendor.getVendorCode());
	}
}
