package com.digithink.zsretail.store.holink.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.SupplyInvoiceCopyDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.core.model.PurchaseInvoiceLine;
import com.digithink.zsretail.core.model.Vendor;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.model.enumeration.InvoiceLineGroupingMode;
import com.digithink.zsretail.core.model.enumeration.RecordOrigin;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.PurchaseInvoiceHeaderRepository;
import com.digithink.zsretail.core.repository.PurchaseInvoiceLineRepository;
import com.digithink.zsretail.core.repository.VendorRepository;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.holink.model.ReceivedDelivery;
import com.digithink.zsretail.store.holink.model.ReceivedDeliveryLine;
import com.digithink.zsretail.store.holink.repository.ReceivedDeliveryRepository;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 7B, part 3: an invoice of the head office arrives at the store as a purchase invoice, once by
 * its number (purchase_invoice_header / purchase_invoice_line, origin HEAD_OFFICE, consult-only: the purchase invoice
 * API has no edit or delete, and no purchase_header is linked, so the store can never invoice it again). Vendor
 * HEAD_OFFICE, created at the first invoice. Totals copied, never recomputed. Each line names this store's head office
 * item (origin HEAD_OFFICE; the tax stamp: the store's TAX_STAMP item); an item not here: a line without item, its code
 * and name in the description. The supply price becomes the last costs of each head office item (lastDirectCost,
 * lastDirectNetCost, by a bulk update); the store's own items are never touched. The BLs of the invoice get its number
 * and, on their lines, its costs (CMP): the reception writes the weighted average cost (costPrice) with the stock, at
 * once for a BL already received ({@link DeliveryReceptionService#applyInvoicedCosts}), else when it is received. A BL
 * saved after its invoice takes them from the purchase invoice ({@link #stampFromInvoice}).
 * <p>
 * Invoices from the ERP, step (c): {@link #saveFromErpInvoice} writes the purchase invoice of a received ERP invoice and
 * no cost (the reception writes it, with the stock); {@link #attachItems} gives its lines the items that arrive later.
 */
@Component
@ConditionalOnInstallation(Installation.SUPPLY_FROM_HEAD_OFFICE)
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
		Map<String, Map<String, BlCost>> costsByBl = new LinkedHashMap<>();
		for (SupplyInvoiceCopyDTO.Line copyLine : copy.getLines()) {
			Optional<Item> item = itemOf(copyLine.getItemCode());
			if (!item.isPresent()) {
				missing.add(copyLine.getItemCode());
			}
			lines.save(line(saved, copyLine, item.orElse(null)));
			Double net = netUnitCost(copyLine.getQuantity() == null ? null : BigDecimal.valueOf(copyLine.getQuantity()),
					copyLine.getLineTotal(), copyLine.getUnitPrice());
			if (item.isPresent() && item.get().getOrigin() == RecordOrigin.HEAD_OFFICE && copyLine.getUnitPrice() != null) {
				// the last costs, informative (the last invoice), by a bulk update: never a save of the item
				items.updateLastCosts(item.get().getId(), copyLine.getUnitPrice(), net, WRITER);
			}
			if (copyLine.getDeliveryNumber() != null && copyLine.getItemCode() != null && net != null) {
				costsByBl.computeIfAbsent(copyLine.getDeliveryNumber(), number -> new LinkedHashMap<>())
						.computeIfAbsent(copyLine.getItemCode(), code -> new BlCost())
						.add(copyLine.getQuantity() == null ? BigDecimal.ZERO : BigDecimal.valueOf(copyLine.getQuantity()),
								copyLine.getLineTotal(), copyLine.getUnitPrice(), net);
			}
		}
		for (String number : deliveryNumbers(copy)) {
			deliveries.findByNumber(number).ifPresent(delivery -> {
				delivery.setInvoiceNumber(copy.getInvoiceNumber());
				stamp(delivery, costsByBl.getOrDefault(number, new HashMap<>()));
				deliveries.save(delivery);
			});
		}
		log.info("Head office link: supply invoice {} received as a purchase invoice ({} lines, total {})",
				saved.getInvoiceNumber(), copy.getLines().size(), copy.getTotalAmount());
		return new Outcome(true, new ArrayList<>(missing));
	}

	// ─── CMP: the costs of the BLs an invoice of the head office covers ───

	/** One item of one BL on an invoice: its quantities, amounts and last prices summed over its invoice lines. */
	static final class BlCost {
		private BigDecimal quantity = BigDecimal.ZERO;
		private BigDecimal amount = BigDecimal.ZERO;
		private Double unitPrice;
		private Double lastNet;

		void add(BigDecimal lineQuantity, Double lineTotal, Double lineUnitPrice, double net) {
			quantity = quantity.add(lineQuantity);
			amount = amount.add(BigDecimal.valueOf(lineTotal != null ? lineTotal : net * lineQuantity.doubleValue()));
			unitPrice = lineUnitPrice != null ? lineUnitPrice : net;
			lastNet = net;
		}

		/** The net unit cost of the item on that BL: its amounts / its quantities (the last line's when no quantity). */
		double net() {
			return quantity.signum() > 0 ? amount.divide(quantity, COST_SCALE, RoundingMode.HALF_UP).doubleValue() : lastNet;
		}
	}

	static final int COST_SCALE = 5;

	/** The BLs an invoice covers: its BL numbers, and those its lines name. */
	public Set<String> deliveryNumbers(SupplyInvoiceCopyDTO copy) {
		Set<String> numbers = new LinkedHashSet<>(copy.getDeliveryNumbers());
		for (SupplyInvoiceCopyDTO.Line line : copy.getLines()) {
			if (line.getDeliveryNumber() != null) {
				numbers.add(line.getDeliveryNumber());
			}
		}
		return numbers;
	}

	/**
	 * The net unit cost of an invoice line (before VAT): its line total / its quantity, else its unit price; null without
	 * either.
	 */
	private static Double netUnitCost(BigDecimal quantity, Double lineTotal, Double unitPrice) {
		if (quantity != null && quantity.signum() > 0 && lineTotal != null) {
			return BigDecimal.valueOf(lineTotal).divide(quantity, COST_SCALE, RoundingMode.HALF_UP).doubleValue();
		}
		return unitPrice;
	}

	/**
	 * The invoice's costs on the lines of a BL (by item code), each line once: unit price, net unit cost, and amount =
	 * net x quantity sent (an item invoiced at 0: amount 0, no cost, as a free line). The reception applies them with the
	 * stock ({@link DeliveryReceptionService#applyInvoicedCosts} for a BL already received). A line that already has a
	 * cost keeps it.
	 */
	private static void stamp(ReceivedDelivery delivery, Map<String, BlCost> costsByCode) {
		for (ReceivedDeliveryLine line : delivery.getLines()) {
			BlCost cost = line.isItemLine() ? costsByCode.get(line.getItemCode()) : null;
			if (cost == null || line.getUnitCost() != null || Boolean.TRUE.equals(line.getCostApplied())) {
				continue;
			}
			double net = cost.net();
			BigDecimal sent = line.getQuantitySent() == null ? BigDecimal.ZERO : line.getQuantitySent();
			line.setUnitPrice(cost.unitPrice);
			line.setUnitCost(net);
			line.setLineAmount(BigDecimal.valueOf(net).multiply(sent).setScale(3, RoundingMode.HALF_UP).doubleValue());
		}
	}

	/**
	 * CMP: a BL saved after the invoice that covers it (its record came late) takes its costs and the invoice number
	 * from the purchase invoice lines already here, matched by the BL number at the start of their description and the
	 * item code (lines written by {@link #save}: "&lt;BL&gt; - &lt;code&gt; &lt;name&gt;"). Called by the copies down
	 * in the BL's transaction, after it is saved TO_RECEIVE: its costs then go in at its reception. True when a cost was
	 * found.
	 */
	@Transactional(rollbackFor = Exception.class)
	public boolean stampFromInvoice(String deliveryNumber) {
		Optional<ReceivedDelivery> found = deliveryNumber == null ? Optional.empty() : deliveries.findByNumber(deliveryNumber);
		if (!found.isPresent()) {
			return false;
		}
		ReceivedDelivery delivery = found.get();
		String prefix = delivery.getNumber() + " - ";
		Map<String, BlCost> costsByCode = new LinkedHashMap<>();
		String invoiceNumber = null;
		for (PurchaseInvoiceLine line : lines.findByLineDescriptionStartingWith(prefix)) {
			PurchaseInvoiceHeader header = line.getPurchaseInvoice();
			if (header == null || header.getOrigin() != RecordOrigin.HEAD_OFFICE || line.getLineDescription() == null) {
				continue;
			}
			String rest = line.getLineDescription().substring(prefix.length());
			String code = line.getItem() != null && rest.startsWith(line.getItem().getItemCode())
					? line.getItem().getItemCode() : rest.split(" ", 2)[0];
			Double net = netUnitCost(line.getQuantity(), line.getSubtotal(), line.getUnitPrice());
			if (net == null) {
				continue;
			}
			costsByCode.computeIfAbsent(code, c -> new BlCost())
					.add(line.getQuantity() == null ? BigDecimal.ZERO : line.getQuantity(), line.getSubtotal(),
							line.getUnitPrice(), net);
			invoiceNumber = header.getInvoiceNumber();
		}
		if (costsByCode.isEmpty()) {
			return false;
		}
		if (delivery.getInvoiceNumber() == null) {
			delivery.setInvoiceNumber(invoiceNumber);
		}
		stamp(delivery, costsByCode);
		deliveries.save(delivery);
		log.info("Head office link: BL {} saved after its invoice {}: costs of {} items taken from it",
				delivery.getNumber(), invoiceNumber, costsByCode.size());
		return true;
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
