package com.digithink.zsretail.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.dto.PurchaseInvoiceDetailsDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.PurchaseHeader;
import com.digithink.zsretail.model.PurchaseInvoiceHeader;
import com.digithink.zsretail.model.PurchaseInvoiceLine;
import com.digithink.zsretail.model.PurchaseLine;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.model.enumeration.InvoiceLineGroupingMode;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.PurchaseHeaderRepository;
import com.digithink.zsretail.repository.PurchaseInvoiceHeaderRepository;
import com.digithink.zsretail.repository.PurchaseInvoiceLineRepository;
import com.digithink.zsretail.repository.PurchaseLineRepository;
import com.digithink.zsretail.repository.VendorRepository;
import com.digithink.zsretail.repository._BaseRepository;
import com.digithink.zsretail.service.InvoiceService.InvoiceSnapshotData;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class PurchaseInvoiceService extends _BaseService<PurchaseInvoiceHeader, Long> {

	@Autowired
	private PurchaseInvoiceHeaderRepository purchaseInvoiceHeaderRepository;

	@Autowired
	private PurchaseInvoiceLineRepository purchaseInvoiceLineRepository;

	@Autowired
	private PurchaseHeaderRepository purchaseHeaderRepository;

	@Autowired
	private PurchaseLineRepository purchaseLineRepository;

	@Autowired
	private VendorRepository vendorRepository;

	@Override
	protected _BaseRepository<PurchaseInvoiceHeader, Long> getRepository() {
		return purchaseInvoiceHeaderRepository;
	}

	/**
	 * Find completed, non-invoiced purchases for a vendor in a date range.
	 */
	public List<PurchaseHeader> findEligiblePurchases(Long vendorId, LocalDate from, LocalDate to) {
		Vendor vendor = vendorRepository.findById(vendorId)
				.orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + vendorId));

		LocalDateTime fromDateTime = from != null ? from.atStartOfDay() : LocalDate.MIN.atStartOfDay();
		LocalDateTime toDateTime = to != null ? to.atTime(LocalTime.MAX) : LocalDate.MAX.atTime(LocalTime.MAX);

		List<PurchaseHeader> purchases = purchaseHeaderRepository
				.findByVendorAndPurchaseDateBetweenAndStatus(vendor, fromDateTime, toDateTime,
						TransactionStatus.COMPLETED);

		return purchases.stream()
				.filter(p -> p.getPurchaseInvoice() == null && !Boolean.TRUE.equals(p.getInvoiced()))
				.sorted(Comparator.comparing(PurchaseHeader::getPurchaseDate))
				.collect(Collectors.toList());
	}

	/**
	 * Create purchase invoice with optional vendor snapshot data.
	 */
	@Transactional(rollbackFor = Exception.class)
	public PurchaseInvoiceHeader createPurchaseInvoice(Long vendorId, List<Long> purchaseIds, LocalDate invoiceDate,
			String notes, InvoiceLineGroupingMode lineGroupingMode, UserAccount createdByUser,
			InvoiceSnapshotData snapshot) throws Exception {

		if (purchaseIds == null || purchaseIds.isEmpty()) {
			throw new IllegalArgumentException("At least one purchase must be selected to create a purchase invoice");
		}

		Vendor vendor = vendorRepository.findById(vendorId)
				.orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + vendorId));

		List<PurchaseHeader> purchases = purchaseHeaderRepository.findAllById(purchaseIds);
		if (purchases.size() != purchaseIds.size()) {
			throw new IllegalArgumentException("Some purchases were not found");
		}

		for (PurchaseHeader p : purchases) {
			if (p.getVendor() == null || !p.getVendor().getId().equals(vendor.getId())) {
				throw new IllegalArgumentException("All purchases must belong to the same vendor");
			}
			if (p.getStatus() != TransactionStatus.COMPLETED) {
				throw new IllegalArgumentException("All purchases must be completed to be invoiced");
			}
			if (p.getPurchaseInvoice() != null || Boolean.TRUE.equals(p.getInvoiced())) {
				throw new IllegalArgumentException("Purchase " + p.getPurchaseNumber() + " is already invoiced");
			}
		}

		InvoiceLineGroupingMode effectiveMode = lineGroupingMode != null ? lineGroupingMode
				: InvoiceLineGroupingMode.BY_ITEM;

		String invoiceNumber = generateNextPurchaseInvoiceNumber(
				invoiceDate != null ? invoiceDate : LocalDate.now());

		PurchaseInvoiceHeader invoice = new PurchaseInvoiceHeader();
		invoice.setInvoiceNumber(invoiceNumber);
		invoice.setInvoiceDate(invoiceDate != null ? invoiceDate : LocalDate.now());
		invoice.setVendor(vendor);
		invoice.setCreatedByUser(createdByUser);
		invoice.setLineGroupingMode(effectiveMode);
		invoice.setNotes(notes);

		// Apply snapshot data (fall back to vendor FK data)
		if (snapshot != null) {
			invoice.setSnapshotVendorName(notBlank(snapshot.getName()) ? snapshot.getName() : vendor.getName());
			invoice.setSnapshotVendorAddress(notBlank(snapshot.getAddress()) ? snapshot.getAddress() : vendor.getAddress());
			invoice.setSnapshotVendorPhone(notBlank(snapshot.getPhone()) ? snapshot.getPhone() : vendor.getPhone());
			invoice.setSnapshotVendorTaxRegNo(notBlank(snapshot.getTaxRegNo()) ? snapshot.getTaxRegNo() : vendor.getTaxRegistrationNo());
		} else {
			invoice.setSnapshotVendorName(vendor.getName());
			invoice.setSnapshotVendorAddress(vendor.getAddress());
			invoice.setSnapshotVendorPhone(vendor.getPhone());
			invoice.setSnapshotVendorTaxRegNo(vendor.getTaxRegistrationNo());
		}

		invoice = save(invoice);

		List<PurchaseLine> allLines = new ArrayList<>();
		for (PurchaseHeader p : purchases) {
			List<PurchaseLine> lines = purchaseLineRepository.findByPurchaseHeader(p);
			allLines.addAll(lines);
		}

		List<PurchaseInvoiceLine> invoiceLines = buildPurchaseInvoiceLines(invoice, allLines, effectiveMode);

		double subtotal = invoiceLines.stream()
				.mapToDouble(l -> l.getSubtotal() != null ? l.getSubtotal() : 0.0).sum();
		double taxAmount = invoiceLines.stream()
				.mapToDouble(l -> l.getTaxAmount() != null ? l.getTaxAmount() : 0.0).sum();
		double total = invoiceLines.stream()
				.mapToDouble(l -> l.getTotalAmount() != null ? l.getTotalAmount() : 0.0).sum();

		invoice.setSubtotal(subtotal);
		invoice.setTaxAmount(taxAmount);
		invoice.setTotalAmount(total);

		invoice = save(invoice);
		for (PurchaseInvoiceLine line : invoiceLines) {
			purchaseInvoiceLineRepository.save(line);
		}

		for (PurchaseHeader p : purchases) {
			p.setPurchaseInvoice(invoice);
			p.setInvoiced(true);
			purchaseHeaderRepository.save(p);
		}

		return invoice;
	}

	/** Backward-compatible overload without snapshot data. */
	@Transactional(rollbackFor = Exception.class)
	public PurchaseInvoiceHeader createPurchaseInvoice(Long vendorId, List<Long> purchaseIds, LocalDate invoiceDate,
			String notes, InvoiceLineGroupingMode lineGroupingMode, UserAccount createdByUser) throws Exception {
		return createPurchaseInvoice(vendorId, purchaseIds, invoiceDate, notes, lineGroupingMode, createdByUser, null);
	}

	private List<PurchaseInvoiceLine> buildPurchaseInvoiceLines(PurchaseInvoiceHeader invoice, List<PurchaseLine> lines,
			InvoiceLineGroupingMode mode) {
		List<PurchaseInvoiceLine> result = new ArrayList<>();
		switch (mode) {
		case BY_ITEM:
			result.addAll(buildLinesGroupedByItem(invoice, lines));
			break;
		case BY_FAMILY:
			result.addAll(buildLinesGroupedByFamily(invoice, lines));
			break;
		case BY_SUBFAMILY:
			result.addAll(buildLinesGroupedBySubFamily(invoice, lines));
			break;
		case NO_GROUPING:
		default:
			result.addAll(buildLinesWithoutGrouping(invoice, lines));
			break;
		}
		return result;
	}

	private static class PurchaseAggregatedAmounts {
		int quantity = 0;
		double subtotal = 0.0;
		double taxAmount = 0.0;
		double totalAmount = 0.0;
		Integer vatPercent = null;
		double unitPriceSum = 0.0;
		int unitPriceCount = 0;

		void add(PurchaseLine line) {
			if (line.getQuantity() != null)
				quantity += line.getQuantity();
			if (line.getLineTotal() != null)
				subtotal += line.getLineTotal();
			double lineTotal = line.getLineTotal() != null ? line.getLineTotal() : 0.0;
			double lineTotalInc = line.getLineTotalIncludingVat() != null ? line.getLineTotalIncludingVat() : lineTotal;
			totalAmount += lineTotalInc;
			taxAmount += (lineTotalInc - lineTotal);
			if (line.getVatPercent() != null)
				vatPercent = line.getVatPercent();
			if (line.getUnitPrice() != null) {
				unitPriceSum += line.getUnitPrice();
				unitPriceCount++;
			}
		}

		double averageUnitPrice() {
			return unitPriceCount > 0 ? unitPriceSum / unitPriceCount : 0.0;
		}

		double averageUnitPriceIncludingVat() {
			if (unitPriceCount <= 0) return 0.0;
			double avgHT = unitPriceSum / unitPriceCount;
			if (vatPercent != null && vatPercent > 0) {
				return avgHT * (1.0 + vatPercent / 100.0);
			}
			return avgHT;
		}
	}

	private List<PurchaseInvoiceLine> buildLinesGroupedByItem(PurchaseInvoiceHeader invoice, List<PurchaseLine> lines) {
		Map<Item, PurchaseAggregatedAmounts> byItem = new HashMap<>();
		for (PurchaseLine pl : lines) {
			Item item = pl.getItem();
			if (item == null) continue;
			PurchaseAggregatedAmounts agg = byItem.computeIfAbsent(item, k -> new PurchaseAggregatedAmounts());
			agg.add(pl);
		}
		List<PurchaseInvoiceLine> result = new ArrayList<>();
		for (Map.Entry<Item, PurchaseAggregatedAmounts> entry : byItem.entrySet()) {
			PurchaseInvoiceLine il = new PurchaseInvoiceLine();
			il.setPurchaseInvoice(invoice);
			il.setItem(entry.getKey());
			PurchaseAggregatedAmounts agg = entry.getValue();
			il.setQuantity(agg.quantity);
			il.setUnitPrice(agg.averageUnitPrice());
			il.setUnitPriceIncludingVat(agg.averageUnitPriceIncludingVat());
			il.setSubtotal(agg.subtotal);
			il.setTaxAmount(agg.taxAmount);
			il.setTotalAmount(agg.totalAmount);
			il.setLineTotalIncludingVat(agg.totalAmount);
			il.setVatPercent(agg.vatPercent);
			result.add(il);
		}
		return result;
	}

	private List<PurchaseInvoiceLine> buildLinesGroupedByFamily(PurchaseInvoiceHeader invoice,
			List<PurchaseLine> lines) {
		Map<ItemFamily, PurchaseAggregatedAmounts> byFamily = new HashMap<>();
		for (PurchaseLine pl : lines) {
			Item item = pl.getItem();
			if (item == null) continue;
			ItemFamily family = item.getItemFamily();
			PurchaseAggregatedAmounts agg = byFamily.computeIfAbsent(family, k -> new PurchaseAggregatedAmounts());
			agg.add(pl);
		}
		List<PurchaseInvoiceLine> result = new ArrayList<>();
		for (Map.Entry<ItemFamily, PurchaseAggregatedAmounts> entry : byFamily.entrySet()) {
			PurchaseInvoiceLine il = new PurchaseInvoiceLine();
			il.setPurchaseInvoice(invoice);
			il.setItemFamily(entry.getKey());
			il.setLineDescription(entry.getKey() != null ? entry.getKey().getName() : "Non classé");
			PurchaseAggregatedAmounts agg = entry.getValue();
			il.setQuantity(agg.quantity);
			il.setUnitPrice(agg.quantity > 0 ? agg.subtotal / agg.quantity : 0.0);
			il.setUnitPriceIncludingVat(agg.quantity > 0 ? agg.totalAmount / agg.quantity : 0.0);
			il.setSubtotal(agg.subtotal);
			il.setTaxAmount(agg.taxAmount);
			il.setTotalAmount(agg.totalAmount);
			il.setLineTotalIncludingVat(agg.totalAmount);
			il.setVatPercent(agg.vatPercent);
			result.add(il);
		}
		return result;
	}

	private List<PurchaseInvoiceLine> buildLinesGroupedBySubFamily(PurchaseInvoiceHeader invoice,
			List<PurchaseLine> lines) {
		Map<ItemSubFamily, PurchaseAggregatedAmounts> bySub = new HashMap<>();
		for (PurchaseLine pl : lines) {
			Item item = pl.getItem();
			if (item == null) continue;
			ItemSubFamily sub = item.getItemSubFamily();
			PurchaseAggregatedAmounts agg = bySub.computeIfAbsent(sub, k -> new PurchaseAggregatedAmounts());
			agg.add(pl);
		}
		List<PurchaseInvoiceLine> result = new ArrayList<>();
		for (Map.Entry<ItemSubFamily, PurchaseAggregatedAmounts> entry : bySub.entrySet()) {
			PurchaseInvoiceLine il = new PurchaseInvoiceLine();
			il.setPurchaseInvoice(invoice);
			il.setItemSubFamily(entry.getKey());
			il.setLineDescription(entry.getKey() != null ? entry.getKey().getName() : "Non classé");
			PurchaseAggregatedAmounts agg = entry.getValue();
			il.setQuantity(agg.quantity);
			il.setUnitPrice(agg.quantity > 0 ? agg.subtotal / agg.quantity : 0.0);
			il.setUnitPriceIncludingVat(agg.quantity > 0 ? agg.totalAmount / agg.quantity : 0.0);
			il.setSubtotal(agg.subtotal);
			il.setTaxAmount(agg.taxAmount);
			il.setTotalAmount(agg.totalAmount);
			il.setLineTotalIncludingVat(agg.totalAmount);
			il.setVatPercent(agg.vatPercent);
			result.add(il);
		}
		return result;
	}

	private List<PurchaseInvoiceLine> buildLinesWithoutGrouping(PurchaseInvoiceHeader invoice,
			List<PurchaseLine> lines) {
		List<PurchaseInvoiceLine> result = new ArrayList<>();
		for (PurchaseLine pl : lines) {
			PurchaseInvoiceLine il = new PurchaseInvoiceLine();
			il.setPurchaseInvoice(invoice);
			il.setItem(pl.getItem());
			il.setQuantity(pl.getQuantity());
			il.setUnitPrice(pl.getUnitPrice());
			double lineTotal = pl.getLineTotal() != null ? pl.getLineTotal() : 0.0;
			double lineTotalInc = pl.getLineTotalIncludingVat() != null ? pl.getLineTotalIncludingVat() : lineTotal;
			il.setSubtotal(lineTotal);
			il.setTotalAmount(lineTotalInc);
			il.setLineTotalIncludingVat(lineTotalInc);
			il.setTaxAmount(lineTotalInc - lineTotal);
			il.setVatPercent(pl.getVatPercent());
			// unitPriceIncludingVat = unitPrice * (1 + vat/100)
			if (pl.getUnitPrice() != null && pl.getVatPercent() != null) {
				il.setUnitPriceIncludingVat(pl.getUnitPrice() * (1.0 + pl.getVatPercent() / 100.0));
			} else if (pl.getUnitPrice() != null) {
				il.setUnitPriceIncludingVat(pl.getUnitPrice());
			}
			result.add(il);
		}
		return result;
	}

	private String generateNextPurchaseInvoiceNumber(LocalDate invoiceDate) {
		int year = invoiceDate.getYear();
		String prefix = "PINV-" + year + "-";
		List<PurchaseInvoiceHeader> sameYear = purchaseInvoiceHeaderRepository.findAll().stream()
				.filter(i -> i.getInvoiceDate() != null && i.getInvoiceDate().getYear() == year)
				.collect(Collectors.toList());
		int maxSeq = sameYear.stream().map(PurchaseInvoiceHeader::getInvoiceNumber)
				.filter(n -> n != null && n.startsWith(prefix))
				.map(n -> n.substring(prefix.length()))
				.filter(s -> s.matches("\\d+"))
				.mapToInt(Integer::parseInt)
				.max()
				.orElse(0);
		return prefix + String.format("%06d", maxSeq + 1);
	}

	public Page<PurchaseInvoiceHeader> listPurchaseInvoices(LocalDate from, LocalDate to, Long vendorId,
			String invoiceNumber, int page, int size) {
		Pageable pageable = PageRequest.of(page, size);
		LocalDate fromDate = from != null ? from : LocalDate.MIN;
		LocalDate toDate = to != null ? to : LocalDate.MAX;

		Page<PurchaseInvoiceHeader> basePage;
		if (vendorId != null) {
			Vendor vendor = vendorRepository.findById(vendorId)
					.orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + vendorId));
			basePage = purchaseInvoiceHeaderRepository.findByVendorAndInvoiceDateBetween(vendor, fromDate, toDate,
					pageable);
		} else {
			basePage = purchaseInvoiceHeaderRepository.findByInvoiceDateBetween(fromDate, toDate, pageable);
		}

		if (invoiceNumber == null || invoiceNumber.trim().isEmpty()) {
			return basePage;
		}
		List<PurchaseInvoiceHeader> filtered = basePage.getContent().stream()
				.filter(i -> i.getInvoiceNumber() != null
						&& i.getInvoiceNumber().toLowerCase().contains(invoiceNumber.toLowerCase()))
				.collect(Collectors.toList());
		return new PageImpl<>(filtered, pageable, filtered.size());
	}

	public Map<String, Object> getPurchaseInvoiceDetails(Long id) {
		PurchaseInvoiceHeader invoice = purchaseInvoiceHeaderRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Purchase invoice not found: " + id));

		List<PurchaseInvoiceLine> lines = purchaseInvoiceLineRepository.findByPurchaseInvoice(invoice);

		List<PurchaseHeader> purchases = purchaseHeaderRepository
				.findByPurchaseInvoiceOrderByPurchaseDateAsc(invoice);

		PurchaseInvoiceDetailsDTO.PurchaseInvoiceHeaderDetail headerDto = toHeaderDetail(invoice);
		List<PurchaseInvoiceDetailsDTO.PurchaseInvoiceLineDetail> lineDtos = lines.stream()
				.map(this::toLineDetail)
				.collect(Collectors.toList());
		List<PurchaseInvoiceDetailsDTO.PurchaseSummary> purchaseDtos = purchases.stream()
				.map(this::toPurchaseSummary)
				.collect(Collectors.toList());

		Map<String, Object> result = new HashMap<>();
		result.put("invoice", headerDto);
		result.put("lines", lineDtos);
		result.put("purchases", purchaseDtos);
		return result;
	}

	private PurchaseInvoiceDetailsDTO.PurchaseInvoiceHeaderDetail toHeaderDetail(PurchaseInvoiceHeader h) {
		PurchaseInvoiceDetailsDTO.VendorSummary vendorSummary = null;
		if (h.getVendor() != null) {
			Vendor v = h.getVendor();
			vendorSummary = new PurchaseInvoiceDetailsDTO.VendorSummary(
					v.getId(), v.getName(), v.getVendorCode(),
					v.getAddress(), v.getPhone(), v.getTaxRegistrationNo());
		}
		PurchaseInvoiceDetailsDTO.UserSummary userSummary = null;
		if (h.getCreatedByUser() != null) {
			UserAccount u = h.getCreatedByUser();
			userSummary = new PurchaseInvoiceDetailsDTO.UserSummary(u.getId(), u.getUsername(), u.getFullName());
		}
		return new PurchaseInvoiceDetailsDTO.PurchaseInvoiceHeaderDetail(
				h.getId(), h.getInvoiceNumber(), h.getInvoiceDate(), vendorSummary, userSummary,
				h.getLineGroupingMode(), h.getSubtotal(), h.getTaxAmount(), h.getDiscountAmount(), h.getTotalAmount(),
				h.getNotes(),
				h.getSnapshotVendorName(), h.getSnapshotVendorAddress(),
				h.getSnapshotVendorPhone(), h.getSnapshotVendorTaxRegNo());
	}

	private PurchaseInvoiceDetailsDTO.PurchaseInvoiceLineDetail toLineDetail(PurchaseInvoiceLine line) {
		PurchaseInvoiceDetailsDTO.ItemSummary itemSummary = null;
		if (line.getItem() != null) {
			Item i = line.getItem();
			itemSummary = new PurchaseInvoiceDetailsDTO.ItemSummary(i.getId(), i.getName(), i.getItemCode());
		}
		PurchaseInvoiceDetailsDTO.ItemFamilySummary familySummary = null;
		if (line.getItemFamily() != null) {
			ItemFamily f = line.getItemFamily();
			familySummary = new PurchaseInvoiceDetailsDTO.ItemFamilySummary(f.getId(), f.getName());
		}
		PurchaseInvoiceDetailsDTO.ItemSubFamilySummary subSummary = null;
		if (line.getItemSubFamily() != null) {
			ItemSubFamily s = line.getItemSubFamily();
			subSummary = new PurchaseInvoiceDetailsDTO.ItemSubFamilySummary(s.getId(), s.getName());
		}
		return new PurchaseInvoiceDetailsDTO.PurchaseInvoiceLineDetail(
				line.getId(), itemSummary, familySummary, subSummary, line.getLineDescription(),
				line.getQuantity(), line.getUnitPrice(), line.getUnitPriceIncludingVat(),
				line.getSubtotal(), line.getTaxAmount(), line.getTotalAmount(), line.getLineTotalIncludingVat(),
				line.getVatPercent());
	}

	private PurchaseInvoiceDetailsDTO.PurchaseSummary toPurchaseSummary(PurchaseHeader p) {
		return new PurchaseInvoiceDetailsDTO.PurchaseSummary(
				p.getId(), p.getPurchaseNumber(), p.getPurchaseDate(), p.getTotalAmount());
	}

	private boolean notBlank(String s) {
		return s != null && !s.trim().isEmpty();
	}
}
