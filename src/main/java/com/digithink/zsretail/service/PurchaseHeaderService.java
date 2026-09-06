package com.digithink.zsretail.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.stream.Collectors;

import javax.persistence.criteria.Predicate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.dto.ProcessPurchaseRequestDTO;
import com.digithink.zsretail.dto.VendorBalanceSummaryDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.PurchaseHeader;
import com.digithink.zsretail.model.PurchaseLine;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.Vendor;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.PurchaseHeaderRepository;
import com.digithink.zsretail.repository.PurchaseLineRepository;
import com.digithink.zsretail.repository.VendorRepository;
import com.digithink.zsretail.repository._BaseRepository;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class PurchaseHeaderService extends _BaseService<PurchaseHeader, Long> {

	@Autowired
	private PurchaseHeaderRepository purchaseHeaderRepository;

	@Autowired
	private PurchaseLineRepository purchaseLineRepository;

	@Autowired
	private VendorRepository vendorRepository;

	@Autowired
	private ItemRepository itemRepository;

	@Autowired
	private StockService stockService;

	@Autowired
	private StockMovementService stockMovementService;

	@Override
	protected _BaseRepository<PurchaseHeader, Long> getRepository() {
		return purchaseHeaderRepository;
	}

	/**
	 * Create a purchase from the request (standalone only).
	 */
	@Transactional
	public PurchaseHeader processPurchase(ProcessPurchaseRequestDTO request, UserAccount currentUser) {
		if (request.getVendorId() == null) {
			throw new IllegalArgumentException("Vendor is required.");
		}
		Vendor vendor = vendorRepository.findById(request.getVendorId())
				.orElseThrow(() -> new IllegalArgumentException("Vendor not found: " + request.getVendorId()));

		if (request.getLines() == null || request.getLines().isEmpty()) {
			throw new IllegalArgumentException("At least one line is required.");
		}

		String purchaseNumber = generatePurchaseNumber();

		PurchaseHeader header = new PurchaseHeader();
		header.setPurchaseNumber(purchaseNumber);
		header.setPurchaseDate(LocalDateTime.now());
		header.setVendor(vendor);
		header.setCreatedByUser(currentUser);
		header.setStatus(TransactionStatus.COMPLETED);
		header.setNotes(request.getNotes());
		header.setVendorBlNumber(request.getVendorBlNumber());

		double subtotal = 0;
		double totalVat = 0;
		for (ProcessPurchaseRequestDTO.PurchaseLineDTO lineDto : request.getLines()) {
			if (lineDto.getItemId() == null || lineDto.getQuantity() == null || lineDto.getQuantity() <= 0
					|| lineDto.getUnitPrice() == null || lineDto.getUnitPrice() < 0) {
				continue;
			}
			Item itemCheck = itemRepository.findById(lineDto.getItemId())
					.orElseThrow(() -> new IllegalArgumentException("Item not found: " + lineDto.getItemId()));
			double discPct = lineDto.getDiscountPercent() != null && lineDto.getDiscountPercent() > 0
					? lineDto.getDiscountPercent() : 0.0;
			double netUnitPrice = lineDto.getUnitPrice() * (1.0 - discPct / 100.0);
			double lineNetTotal = lineDto.getQuantity() * netUnitPrice;
			int vatPct = lineDto.getVatPercent() != null ? lineDto.getVatPercent()
					: (itemCheck.getDefaultVAT() != null ? itemCheck.getDefaultVAT() : 0);
			double vatAmount = lineNetTotal * vatPct / 100.0;
			subtotal += lineNetTotal;
			totalVat += vatAmount;
		}

		header.setSubtotal(subtotal);
		header.setTaxAmount(totalVat);
		header.setDiscountAmount(null);
		header.setTotalAmount(subtotal + totalVat);

		header = purchaseHeaderRepository.save(header);

		for (ProcessPurchaseRequestDTO.PurchaseLineDTO lineDto : request.getLines()) {
			if (lineDto.getItemId() == null || lineDto.getQuantity() == null || lineDto.getQuantity() <= 0
					|| lineDto.getUnitPrice() == null || lineDto.getUnitPrice() < 0) {
				continue;
			}
			Item item = itemRepository.findById(lineDto.getItemId()).orElse(null);
			if (item == null) continue;

			double discPct = lineDto.getDiscountPercent() != null && lineDto.getDiscountPercent() > 0
					? lineDto.getDiscountPercent() : 0.0;
			double netUnitPrice = lineDto.getUnitPrice() * (1.0 - discPct / 100.0);
			double lineNetTotal = lineDto.getQuantity() * netUnitPrice;
			int vatPct = lineDto.getVatPercent() != null ? lineDto.getVatPercent()
					: (item.getDefaultVAT() != null ? item.getDefaultVAT() : 0);
			double vatAmount = lineNetTotal * vatPct / 100.0;
			double lineTotalTtc = lineNetTotal + vatAmount;

			PurchaseLine line = new PurchaseLine();
			line.setPurchaseHeader(header);
			line.setItem(item);
			line.setQuantity(lineDto.getQuantity());
			line.setUnitPrice(lineDto.getUnitPrice());
			line.setDiscountPercent(discPct > 0 ? discPct : null);
			line.setVatPercent(vatPct);
			line.setVatAmount(vatAmount);
			line.setLineTotal(lineNetTotal);
			line.setLineTotalIncludingVat(lineTotalTtc);
			purchaseLineRepository.save(line);

			// Update item last direct costs
			item.setLastDirectCost(lineDto.getUnitPrice());
			item.setLastDirectNetCost(netUnitPrice);
			itemRepository.save(item);

			// Update stock (standalone only; single service, atomic updates, concurrency-safe)
			stockService.incrementForPurchase(item.getId(), lineDto.getQuantity());
			stockMovementService.recordPurchase(
					item.getId(), lineDto.getQuantity(),
					lineDto.getUnitPrice(), vatPct, lineTotalTtc / lineDto.getQuantity(),
					header.getId());
		}

		log.info("Purchase created: {} id={}", purchaseNumber, header.getId());
		return header;
	}

	private String generatePurchaseNumber() {
		LocalDateTime now = LocalDateTime.now();
		String prefix = "PUR-" + now.getYear() + String.format("%02d", now.getMonthValue());
		long count = purchaseHeaderRepository.count() + 1;
		return prefix + "-" + String.format("%06d", count);
	}

	/**
	 * Paginated history with optional filters.
	 */
	public Page<PurchaseHeader> getHistory(int page, int size, String search, String dateFrom, String dateTo,
			String status, Long vendorId) {
		Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "purchaseDate"));

		Specification<PurchaseHeader> spec = (root, query, cb) -> {
			query.distinct(true);
			List<Predicate> predicates = new java.util.ArrayList<>();

			if (search != null && !search.trim().isEmpty()) {
				String pattern = "%" + search.trim().toLowerCase() + "%";
				predicates.add(cb.or(
						cb.like(cb.lower(root.get("purchaseNumber")), pattern),
						cb.like(cb.lower(root.get("vendor").get("name")), pattern),
						cb.like(cb.lower(root.get("vendor").get("vendorCode")), pattern)));
			}

			if (dateFrom != null && !dateFrom.trim().isEmpty()) {
				try {
					LocalDate from = LocalDate.parse(dateFrom.trim());
					predicates.add(cb.greaterThanOrEqualTo(root.get("purchaseDate"), from.atStartOfDay()));
				} catch (Exception ignored) { }
			}

			if (dateTo != null && !dateTo.trim().isEmpty()) {
				try {
					LocalDate to = LocalDate.parse(dateTo.trim());
					predicates.add(cb.lessThanOrEqualTo(root.get("purchaseDate"), to.atTime(LocalTime.MAX)));
				} catch (Exception ignored) { }
			}

			if (status != null && !status.equals("all")) {
				try {
					TransactionStatus s = TransactionStatus.valueOf(status);
					predicates.add(cb.equal(root.get("status"), s));
				} catch (Exception ignored) { }
			}

			if (vendorId != null && vendorId > 0) {
				predicates.add(cb.equal(root.get("vendor").get("id"), vendorId));
			}

			return predicates.isEmpty() ? null : cb.and(predicates.toArray(new Predicate[0]));
		};

		return purchaseHeaderRepository.findAll(spec, pageable);
	}

	/**
	 * Get purchase with lines for details modal.
	 */
	public PurchaseHeader getDetails(Long id) {
		PurchaseHeader header = purchaseHeaderRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Purchase not found: " + id));
		// Lazy load lines
		List<PurchaseLine> lines = purchaseLineRepository.findByPurchaseHeader(header);
		header.setPurchaseLines(lines);
		return header;
	}

	/**
	 * Set paid amount and/or date on a purchase (standalone). Use null paidAmount to clear paid status.
	 */
	@Transactional
	public PurchaseHeader setPaidStatus(Long id, Double paidAmount, LocalDateTime paidDate) {
		PurchaseHeader header = purchaseHeaderRepository.findById(id)
				.orElseThrow(() -> new IllegalArgumentException("Purchase not found: " + id));
		header.setPaidAmount(paidAmount);
		header.setPaidDate(paidDate);
		return purchaseHeaderRepository.save(header);
	}

	/**
	 * Vendor balance / AP summary: per vendor, total purchased, total paid, unpaid. Optional date filter.
	 */
	public List<VendorBalanceSummaryDTO> getVendorBalanceSummary(String dateFrom, String dateTo) {
		LocalDateTime from = null;
		LocalDateTime to = null;
		if (dateFrom != null && !dateFrom.trim().isEmpty()) {
			try {
				from = LocalDate.parse(dateFrom.trim()).atStartOfDay();
			} catch (Exception ignored) { }
		}
		if (dateTo != null && !dateTo.trim().isEmpty()) {
			try {
				to = LocalDate.parse(dateTo.trim()).atTime(LocalTime.MAX);
			} catch (Exception ignored) { }
		}
		List<Object[]> rows = purchaseHeaderRepository.getVendorBalanceSummary(from, to);
		return rows.stream().map(row -> {
			Long vendorId = (Long) row[0];
			String vendorCode = (String) row[1];
			String vendorName = (String) row[2];
			Double totalPurchased = row[3] != null ? ((Number) row[3]).doubleValue() : 0.0;
			Double totalPaid = row[4] != null ? ((Number) row[4]).doubleValue() : 0.0;
			Double unpaid = totalPurchased - totalPaid;
			return new VendorBalanceSummaryDTO(vendorId, vendorCode, vendorName, totalPurchased, totalPaid, unpaid);
		}).collect(Collectors.toList());
	}
}
