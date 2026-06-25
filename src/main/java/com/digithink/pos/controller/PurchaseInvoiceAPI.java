package com.digithink.pos.controller;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.pos.config.ApplicationModeService;
import com.digithink.pos.dto.EligiblePurchaseDTO;
import com.digithink.pos.dto.PurchaseInvoiceListDTO;
import com.digithink.pos.model.PurchaseHeader;
import com.digithink.pos.model.PurchaseInvoiceHeader;
import com.digithink.pos.model.Vendor;
import com.digithink.pos.model.enumeration.InvoiceLineGroupingMode;
import com.digithink.pos.security.CurrentUserProvider;
import com.digithink.pos.service.InvoiceService.InvoiceSnapshotData;
import com.digithink.pos.service.PurchaseInvoiceService;

import lombok.Data;
import lombok.extern.log4j.Log4j2;

/**
 * REST API for purchase invoices (supplier invoices). Standalone mode only.
 */
@RestController
@RequestMapping("admin/purchase-invoices")
@Log4j2
public class PurchaseInvoiceAPI {

	@Autowired
	private PurchaseInvoiceService purchaseInvoiceService;

	@Autowired
	private ApplicationModeService applicationModeService;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	private void ensureStandalone() {
		if (!applicationModeService.isStandalone()) {
			throw new org.springframework.web.server.ResponseStatusException(HttpStatus.FORBIDDEN,
					"Purchase invoices are only available in standalone mode.");
		}
	}

	@Data
	public static class CreatePurchaseInvoiceRequest {
		private Long vendorId;
		private String invoiceDate;
		private String notes;
		private List<Long> purchaseIds;
		private InvoiceLineGroupingMode lineGroupingMode;
		// Editable snapshot of vendor info for this invoice
		private String snapshotVendorName;
		private String snapshotVendorAddress;
		private String snapshotVendorPhone;
		private String snapshotVendorTaxRegNo;
	}

	/**
	 * List purchase invoices with filters. Standalone only.
	 */
	@GetMapping
	public ResponseEntity<?> listPurchaseInvoices(
			@RequestParam(required = false) String from,
			@RequestParam(required = false) String to,
			@RequestParam(required = false) Long vendorId,
			@RequestParam(required = false) String invoiceNumber,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		try {
			ensureStandalone();

			LocalDate fromDate = (from != null && !from.trim().isEmpty()) ? LocalDate.parse(from) : null;
			LocalDate toDate = (to != null && !to.trim().isEmpty()) ? LocalDate.parse(to) : null;

			Page<PurchaseInvoiceHeader> invoicePage = purchaseInvoiceService.listPurchaseInvoices(
					fromDate, toDate, vendorId, invoiceNumber, page, size);

			List<PurchaseInvoiceListDTO> content = invoicePage.getContent().stream()
					.map(PurchaseInvoiceAPI::toListDTO)
					.collect(Collectors.toList());

			Map<String, Object> response = new HashMap<>();
			response.put("content", content);
			response.put("totalElements", invoicePage.getTotalElements());
			response.put("totalPages", invoicePage.getTotalPages());
			response.put("number", invoicePage.getNumber());
			response.put("size", invoicePage.getSize());
			response.put("numberOfElements", invoicePage.getNumberOfElements());
			response.put("first", invoicePage.isFirst());
			response.put("last", invoicePage.isLast());

			return ResponseEntity.ok(response);
		} catch (org.springframework.web.server.ResponseStatusException e) {
			throw e;
		} catch (Exception e) {
			String msg = getDetailedMessage(e);
			log.error("PurchaseInvoiceAPI::listPurchaseInvoices:error: " + msg, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(msg));
		}
	}

	/**
	 * Get eligible purchases (completed, non-invoiced) for a vendor and date range. Standalone only.
	 */
	@GetMapping("/eligible-purchases")
	public ResponseEntity<?> getEligiblePurchases(
			@RequestParam Long vendorId,
			@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo) {
		try {
			ensureStandalone();

			LocalDate from = (dateFrom != null && !dateFrom.trim().isEmpty()) ? LocalDate.parse(dateFrom) : null;
			LocalDate to = (dateTo != null && !dateTo.trim().isEmpty()) ? LocalDate.parse(dateTo) : null;

			List<PurchaseHeader> purchases = purchaseInvoiceService.findEligiblePurchases(vendorId, from, to);
			List<EligiblePurchaseDTO> dtos = purchases.stream()
					.map(p -> new EligiblePurchaseDTO(p.getId(), p.getPurchaseNumber(), p.getPurchaseDate(),
							p.getTotalAmount()))
					.collect(Collectors.toList());
			return ResponseEntity.ok(dtos);
		} catch (org.springframework.web.server.ResponseStatusException e) {
			throw e;
		} catch (IllegalArgumentException e) {
			log.error("PurchaseInvoiceAPI::getEligiblePurchases: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String msg = getDetailedMessage(e);
			log.error("PurchaseInvoiceAPI::getEligiblePurchases:error: " + msg, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(msg));
		}
	}

	/**
	 * Create purchase invoice from selected purchases. Standalone only.
	 */
	@PostMapping
	public ResponseEntity<?> createPurchaseInvoice(@RequestBody CreatePurchaseInvoiceRequest request) {
		try {
			ensureStandalone();

			if (request.getVendorId() == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("Vendor is mandatory"));
			}
			if (request.getPurchaseIds() == null || request.getPurchaseIds().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("At least one purchase must be selected"));
			}

			LocalDate invoiceDate = (request.getInvoiceDate() != null && !request.getInvoiceDate().trim().isEmpty())
					? LocalDate.parse(request.getInvoiceDate())
					: LocalDate.now();

			InvoiceSnapshotData snapshot = new InvoiceSnapshotData();
			snapshot.setName(request.getSnapshotVendorName());
			snapshot.setAddress(request.getSnapshotVendorAddress());
			snapshot.setPhone(request.getSnapshotVendorPhone());
			snapshot.setTaxRegNo(request.getSnapshotVendorTaxRegNo());

			PurchaseInvoiceHeader created = purchaseInvoiceService.createPurchaseInvoice(
					request.getVendorId(),
					request.getPurchaseIds(),
					invoiceDate,
					request.getNotes(),
					request.getLineGroupingMode(),
					currentUserProvider.getCurrentUser(),
					snapshot);

			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (org.springframework.web.server.ResponseStatusException e) {
			throw e;
		} catch (IllegalArgumentException e) {
			log.error("PurchaseInvoiceAPI::createPurchaseInvoice: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String msg = getDetailedMessage(e);
			log.error("PurchaseInvoiceAPI::createPurchaseInvoice:error: " + msg, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(msg));
		}
	}

	/**
	 * Get purchase invoice details for viewing/printing. Standalone only.
	 */
	@GetMapping("/{id}/details")
	public ResponseEntity<?> getPurchaseInvoiceDetails(@PathVariable Long id) {
		try {
			ensureStandalone();

			Map<String, Object> details = purchaseInvoiceService.getPurchaseInvoiceDetails(id);
			return ResponseEntity.ok(details);
		} catch (org.springframework.web.server.ResponseStatusException e) {
			throw e;
		} catch (IllegalArgumentException e) {
			log.error("PurchaseInvoiceAPI::getPurchaseInvoiceDetails: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String msg = getDetailedMessage(e);
			log.error("PurchaseInvoiceAPI::getPurchaseInvoiceDetails:error: " + msg, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(msg));
		}
	}

	private static PurchaseInvoiceListDTO toListDTO(PurchaseInvoiceHeader h) {
		PurchaseInvoiceListDTO.VendorSummary vendorSummary = null;
		Vendor v = h.getVendor();
		if (v != null) {
			vendorSummary = new PurchaseInvoiceListDTO.VendorSummary(v.getId(), v.getName(), v.getVendorCode());
		}
		return new PurchaseInvoiceListDTO(
				h.getId(),
				h.getInvoiceNumber(),
				h.getInvoiceDate(),
				vendorSummary,
				h.getSubtotal(),
				h.getTaxAmount(),
				h.getDiscountAmount(),
				h.getTotalAmount(),
				h.getNotes(),
				h.getLineGroupingMode());
	}

	private String getDetailedMessage(Throwable e) {
		Throwable cause = e.getCause();
		while (cause != null && cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause != null ? cause.getLocalizedMessage() : e.getLocalizedMessage();
	}

	private Object createErrorResponse(String message) {
		return message;
	}
}
