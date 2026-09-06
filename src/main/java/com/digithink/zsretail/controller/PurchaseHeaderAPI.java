package com.digithink.zsretail.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.ProcessPurchaseRequestDTO;
import com.digithink.zsretail.dto.SetPurchasePaidRequestDTO;
import com.digithink.zsretail.dto.VendorBalanceSummaryDTO;
import com.digithink.zsretail.model.PurchaseHeader;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.PurchaseHeaderService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("purchase-header")
@Log4j2
public class PurchaseHeaderAPI extends _BaseController<PurchaseHeader, Long, PurchaseHeaderService> {

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@Autowired
	private ApplicationModeService applicationModeService;

	/**
	 * Vendor balance / AP summary: per vendor total purchased, total paid, unpaid. Optional date range. Standalone only.
	 */
	@GetMapping("/vendor-balance")
	public ResponseEntity<?> getVendorBalance(
			@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Vendor balance report is only available in standalone mode."));
		}
		try {
			java.util.List<VendorBalanceSummaryDTO> list = service.getVendorBalanceSummary(dateFrom, dateTo);
			return ResponseEntity.ok(list);
		} catch (Exception e) {
			log.error("PurchaseHeaderAPI::getVendorBalance:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Paginated purchase history with optional filters. Standalone only.
	 */
	@GetMapping("/history")
	public ResponseEntity<?> getHistory(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size,
			@RequestParam(required = false) String search,
			@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Long vendorId) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Purchase history is only available in standalone mode."));
		}
		try {
			log.info("PurchaseHeaderAPI::getHistory: page={}, size={}", page, size);
			Page<PurchaseHeader> pageResult = service.getHistory(page, size, search, dateFrom, dateTo, status, vendorId);

			Map<String, Object> response = new HashMap<>();
			response.put("content", pageResult.getContent());
			response.put("totalElements", pageResult.getTotalElements());
			response.put("totalPages", pageResult.getTotalPages());
			response.put("number", pageResult.getNumber());
			response.put("size", pageResult.getSize());
			response.put("numberOfElements", pageResult.getNumberOfElements());
			response.put("first", pageResult.isFirst());
			response.put("last", pageResult.isLast());

			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("PurchaseHeaderAPI::getHistory:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get purchase details with lines. Standalone only.
	 */
	@GetMapping("/{id}/details")
	public ResponseEntity<?> getDetails(@PathVariable Long id) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Purchase details are only available in standalone mode."));
		}
		try {
			log.info("PurchaseHeaderAPI::getDetails: id=" + id);
			PurchaseHeader header = service.getDetails(id);
			return ResponseEntity.ok(header);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.notFound().build();
		} catch (Exception e) {
			log.error("PurchaseHeaderAPI::getDetails:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Create a purchase (process-purchase). Standalone only.
	 */
	@PostMapping("/process-purchase")
	public ResponseEntity<?> processPurchase(@RequestBody ProcessPurchaseRequestDTO request) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Purchases are only available in standalone mode. In ERP mode use the ERP for purchasing."));
		}
		try {
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			log.info("PurchaseHeaderAPI::processPurchase: vendorId=" + request.getVendorId());

			PurchaseHeader created = service.processPurchase(request, currentUser);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (IllegalArgumentException e) {
			log.warn("PurchaseHeaderAPI::processPurchase:validation: " + e.getMessage());
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("PurchaseHeaderAPI::processPurchase:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Set paid amount and/or date on a purchase. Standalone only.
	 * Body: { "paidAmount": number or null, "paidDate": "yyyy-MM-ddTHH:mm:ss" or null }. Use null paidAmount to clear.
	 */
	@PatchMapping("/{id}/set-paid")
	public ResponseEntity<?> setPaid(@PathVariable Long id, @RequestBody SetPurchasePaidRequestDTO request) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Purchase paid status is only available in standalone mode."));
		}
		try {
			Double paidAmount = request.getPaidAmount();
			LocalDateTime paidDate = null;
			if (request.getPaidDate() != null && !request.getPaidDate().trim().isEmpty()) {
				try {
					paidDate = LocalDateTime.parse(request.getPaidDate().trim(),
							DateTimeFormatter.ISO_LOCAL_DATE_TIME);
				} catch (DateTimeParseException e) {
					paidDate = LocalDate.parse(request.getPaidDate().trim(),
							DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay();
				}
			}
			if (paidAmount != null && paidDate == null) {
				paidDate = LocalDateTime.now();
			}
			PurchaseHeader updated = service.setPaidStatus(id, paidAmount, paidDate);
			return ResponseEntity.ok(updated);
		} catch (IllegalArgumentException e) {
			return ResponseEntity.notFound().build();
		} catch (Exception e) {
			log.error("PurchaseHeaderAPI::setPaid:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}
