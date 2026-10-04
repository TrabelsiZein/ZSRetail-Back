package com.digithink.zsretail.holink.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSupply;
import com.digithink.zsretail.headoffice.dto.SupplyInvoiceCopyDTO;
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

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 7B, part 3: an invoice of the head office arrives at the store as a purchase invoice, once by
 * its number (purchase_invoice_header / purchase_invoice_line, origin HEAD_OFFICE, consult-only: the purchase invoice
 * API has no edit or delete, and no purchase_header is linked, so the store can never invoice it again). Vendor
 * HEAD_OFFICE, created at the first invoice. Totals copied, never recomputed. Each line names this store's head office
 * item (origin HEAD_OFFICE; the tax stamp: the store's TAX_STAMP item); an item not here: a line without item, its code
 * and name in the description. The supply price becomes the cost of each head office item (lastDirectCost,
 * lastDirectNetCost and costPrice); the store's own items are never touched. The BLs of the invoice get its number.
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
		return vendors.findByVendorCode(VENDOR_CODE).orElseGet(() -> {
			Vendor vendor = new Vendor();
			vendor.setVendorCode(VENDOR_CODE);
			vendor.setName(copy.getSellerName() != null && !copy.getSellerName().trim().isEmpty() ? copy.getSellerName()
					: "Head office");
			vendor.setPhone("N/A");
			vendor.setAddress(copy.getSellerAddress());
			vendor.setTaxRegistrationNo(copy.getSellerTaxNumber());
			vendor.setActive(true);
			vendor.setCreatedBy(WRITER);
			vendor.setUpdatedBy(WRITER);
			return vendors.save(vendor);
		});
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
		line.setQuantity(copy.getQuantity() == null ? 0 : copy.getQuantity());
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
