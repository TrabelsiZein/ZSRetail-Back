package com.digithink.zsretail.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.dto.ProcessReturnRequestDTO;
import com.digithink.zsretail.model.ReturnHeader;
import com.digithink.zsretail.model.ReturnLine;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.repository.ReturnHeaderRepository;
import com.digithink.zsretail.repository.ReturnLineRepository;
import com.digithink.zsretail.repository.SalesHeaderRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.ReturnHeaderService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("return-header")
@Log4j2
public class ReturnHeaderAPI extends _BaseController<ReturnHeader, Long, ReturnHeaderService> {

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Autowired
	private ReturnLineRepository returnLineRepository;

	@Autowired
	private ReturnHeaderRepository returnHeaderRepository;

	/**
	 * List all return headers as summary maps to avoid Jackson circular reference
	 * when serializing entity graph (ReturnHeader -> SalesHeader -> ...).
	 */
	@Override
	@GetMapping
	public ResponseEntity<?> getAll() {
		try {
			log.info("ReturnHeaderAPI::getAll");
			List<ReturnHeader> headers = returnHeaderRepository.findAll();
			List<Map<String, Object>> list = new ArrayList<>();
			for (ReturnHeader h : headers) {
				list.add(toListMap(h));
			}
			return ResponseEntity.ok(list);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("ReturnHeaderAPI::getAll:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	private Map<String, Object> toListMap(ReturnHeader h) {
		Map<String, Object> map = new HashMap<>();
		map.put("id", h.getId());
		map.put("returnNumber", h.getReturnNumber());
		map.put("returnDate", h.getReturnDate());
		map.put("returnType", h.getReturnType());
		map.put("totalReturnAmount", h.getTotalReturnAmount());
		map.put("notes", h.getNotes());
		map.put("status", h.getStatus());
		map.put("synchronizationStatus", h.getSynchronizationStatus());
		map.put("erpNo", h.getErpNo());
		map.put("discountPercentage", h.getDiscountPercentage());
		if (h.getOriginalSalesHeader() != null) {
			SalesHeader sh = h.getOriginalSalesHeader();
			Map<String, Object> sales = new HashMap<>();
			sales.put("id", sh.getId());
			sales.put("salesNumber", sh.getSalesNumber());
			sales.put("salesDate", sh.getSalesDate());
			sales.put("totalAmount", sh.getTotalAmount());
			map.put("originalSalesHeader", sales);
		}
		if (h.getReturnVoucher() != null) {
			Map<String, Object> v = new HashMap<>();
			v.put("id", h.getReturnVoucher().getId());
			v.put("voucherNumber", h.getReturnVoucher().getVoucherNumber());
			v.put("voucherDate", h.getReturnVoucher().getVoucherDate());
			v.put("voucherAmount", h.getReturnVoucher().getVoucherAmount());
			v.put("expiryDate", h.getReturnVoucher().getExpiryDate());
			v.put("status", h.getReturnVoucher().getStatus());
			v.put("usedAmount", h.getReturnVoucher().getUsedAmount());
			v.put("notes", h.getReturnVoucher().getNotes());
			if (h.getReturnVoucher().getCustomer() != null) {
				v.put("customerId", h.getReturnVoucher().getCustomer().getId());
				v.put("customerName", h.getReturnVoucher().getCustomer().getName());
			}
			map.put("returnVoucher", v);
		}
		return map;
	}

	/**
	 * Paginated, filtered list for the Returns History admin page.
	 */
	@GetMapping("/history")
	public ResponseEntity<?> getHistory(
			@RequestParam(required = false) String search,
			@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo,
			@RequestParam(required = false) String returnType,
			@RequestParam(required = false) String voucherStatus,
			@RequestParam(required = false) String syncStatus,
			@RequestParam(required = false) String sessionNumber,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		try {
			// Parse dates (supports yyyy-MM-dd'T'HH:mm and yyyy-MM-dd)
			java.time.LocalDateTime from = null;
			if (dateFrom != null && !dateFrom.trim().isEmpty()) {
				try { from = java.time.LocalDateTime.parse(dateFrom.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")); }
				catch (Exception e1) { try { from = java.time.LocalDate.parse(dateFrom.trim()).atStartOfDay(); } catch (Exception e2) {} }
			}
			java.time.LocalDateTime to = null;
			if (dateTo != null && !dateTo.trim().isEmpty()) {
				try { to = java.time.LocalDateTime.parse(dateTo.trim(), java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")); }
				catch (Exception e1) { try { to = java.time.LocalDate.parse(dateTo.trim()).atTime(23, 59, 59); } catch (Exception e2) {} }
			}

			final java.time.LocalDateTime finalFrom = from;
			final java.time.LocalDateTime finalTo = to;
			final String finalSearch = search;
			final String finalReturnType = returnType;
			final String finalVoucherStatus = voucherStatus;
			final String finalSyncStatus = syncStatus;
			final String finalSessionNumber = sessionNumber != null && !sessionNumber.trim().isEmpty() ? sessionNumber.trim() : null;

			org.springframework.data.jpa.domain.Specification<ReturnHeader> spec = (root, query, cb) -> {
				List<javax.persistence.criteria.Predicate> predicates = new ArrayList<>();

				// Pre-create LEFT JOINs (reused for both filtering and search)
				javax.persistence.criteria.Join<?, ?> shJoin = root.join("originalSalesHeader", javax.persistence.criteria.JoinType.LEFT);
				javax.persistence.criteria.Join<?, ?> vJoin  = root.join("returnVoucher",         javax.persistence.criteria.JoinType.LEFT);

				if (finalFrom != null) predicates.add(cb.greaterThanOrEqualTo(root.get("returnDate"), finalFrom));
				if (finalTo   != null) predicates.add(cb.lessThanOrEqualTo(root.get("returnDate"), finalTo));

				if (finalReturnType != null && !finalReturnType.trim().isEmpty())
					predicates.add(cb.equal(root.get("returnType"), finalReturnType));

				if (finalSyncStatus != null && !finalSyncStatus.equalsIgnoreCase("all") && !finalSyncStatus.trim().isEmpty()) {
					try {
						predicates.add(cb.equal(root.get("synchronizationStatus"),
								com.digithink.zsretail.model.enumeration.SynchronizationStatus.valueOf(finalSyncStatus.toUpperCase())));
					} catch (Exception ignored) {}
				}

				if (finalVoucherStatus != null && !finalVoucherStatus.trim().isEmpty()) {
					predicates.add(cb.isNotNull(vJoin.get("id")));
					predicates.add(cb.equal(vJoin.get("status"), finalVoucherStatus));
				}

				if (finalSearch != null && !finalSearch.trim().isEmpty()) {
					query.distinct(true);
					String p = "%" + finalSearch.trim().toLowerCase() + "%";
					predicates.add(cb.or(
							cb.like(cb.lower(root.get("returnNumber")), p),
							cb.like(cb.lower(shJoin.get("salesNumber")), p),
							cb.like(cb.lower(vJoin.get("voucherNumber")), p)));
				}

				if (finalSessionNumber != null) {
					predicates.add(cb.equal(root.get("cashierSession").get("sessionNumber"), finalSessionNumber));
				}

				return cb.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
			};

			org.springframework.data.domain.Page<ReturnHeader> resultPage = returnHeaderRepository.findAll(
					spec, org.springframework.data.domain.PageRequest.of(page, size,
							org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "returnDate")));

			List<Map<String, Object>> content = new ArrayList<>();
			for (ReturnHeader h : resultPage.getContent()) content.add(toListMap(h));

			Map<String, Object> response = new HashMap<>();
			response.put("content", content);
			response.put("totalElements", resultPage.getTotalElements());
			response.put("totalPages", resultPage.getTotalPages());
			response.put("number", resultPage.getNumber());
			response.put("size", resultPage.getSize());
			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("ReturnHeaderAPI::getHistory:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get ticket details by ticket number (for return processing)
	 */
	@GetMapping("/ticket-details")
	public ResponseEntity<?> getTicketDetails(@RequestParam String ticketNumber) {
		try {
			log.info("ReturnHeaderAPI::getTicketDetails: ticketNumber=" + ticketNumber);

			SalesHeader salesHeader = salesHeaderRepository.findBySalesNumber(ticketNumber)
					.orElseThrow(() -> new IllegalArgumentException("Ticket not found: " + ticketNumber));

			// Check if ticket can be returned
			boolean canReturn = service.canReturnTicket(salesHeader);
			if (!canReturn) {
				return ResponseEntity.badRequest().body(createErrorResponse("This ticket is too old to be returned"));
			}

			// Get sales lines
			java.util.List<com.digithink.zsretail.model.SalesLine> salesLines = salesLineRepository
					.findBySalesHeader(salesHeader);

			// Get all return headers for this sales header (there can be multiple returns
			// for the same ticket)
			java.util.List<com.digithink.zsretail.model.ReturnHeader> returnHeaders = returnHeaderRepository
					.findAllByOriginalSalesHeader(salesHeader);

			// Calculate returned quantities for each sales line
			// Map: salesLineId -> total returned quantity
			Map<Long, Integer> returnedQuantities = new HashMap<>();

			for (com.digithink.zsretail.model.ReturnHeader returnHeader : returnHeaders) {
				java.util.List<com.digithink.zsretail.model.ReturnLine> returnLines = returnLineRepository
						.findByReturnHeader(returnHeader);
				for (com.digithink.zsretail.model.ReturnLine returnLine : returnLines) {
					Long originalSalesLineId = returnLine.getOriginalSalesLine().getId();
					int returnedQty = returnLine.getQuantity();
					returnedQuantities.put(originalSalesLineId,
							returnedQuantities.getOrDefault(originalSalesLineId, 0) + returnedQty);
				}
			}

			// Build sales lines with remaining quantities (only include lines with
			// remaining > 0)
			java.util.List<Map<String, Object>> salesLinesWithRemaining = new java.util.ArrayList<>();

			for (com.digithink.zsretail.model.SalesLine salesLine : salesLines) {
				int originalQuantity = salesLine.getQuantity();
				int returnedQuantity = returnedQuantities.getOrDefault(salesLine.getId(), 0);
				int remainingQuantity = originalQuantity - returnedQuantity;

				// Only include lines that still have remaining quantity to return
				if (remainingQuantity > 0) {
					Map<String, Object> lineData = new HashMap<>();
					lineData.put("id", salesLine.getId());
					if (salesLine.getItem() != null) {
						Map<String, Object> itemData = new HashMap<>();
						itemData.put("id", salesLine.getItem().getId());
						itemData.put("name", salesLine.getItem().getName());
						itemData.put("itemCode", salesLine.getItem().getItemCode());
						lineData.put("item", itemData);
					} else {
						lineData.put("item", null);
					}
					lineData.put("quantity", originalQuantity); // Original purchased quantity
					lineData.put("unitPrice", salesLine.getUnitPrice());
					lineData.put("lineTotal", salesLine.getLineTotal());
					lineData.put("discountPercentage", salesLine.getDiscountPercentage());
					lineData.put("discountAmount", salesLine.getDiscountAmount());
					lineData.put("vatAmount", salesLine.getVatAmount());
					lineData.put("vatPercent", salesLine.getVatPercent());
					lineData.put("unitPriceIncludingVat", salesLine.getUnitPriceIncludingVat());
					lineData.put("lineTotalIncludingVat", salesLine.getLineTotalIncludingVat());
					lineData.put("returnedQuantity", returnedQuantity); // Already returned
					lineData.put("remainingQuantity", remainingQuantity); // Can still be returned
					lineData.put("discountSource", salesLine.getDiscountSource());
					if (salesLine.getPromotion() != null) {
						Map<String, Object> promoData = new HashMap<>();
						promoData.put("id", salesLine.getPromotion().getId());
						promoData.put("code", salesLine.getPromotion().getCode());
						promoData.put("name", salesLine.getPromotion().getName());
						lineData.put("promotion", promoData);
					}

					salesLinesWithRemaining.add(lineData);
				}
			}

			// Create response (ticket as summary map to avoid Jackson circular reference)
			Map<String, Object> ticketSummary = new HashMap<>();
			ticketSummary.put("id", salesHeader.getId());
			ticketSummary.put("salesNumber", salesHeader.getSalesNumber());
			ticketSummary.put("salesDate", salesHeader.getSalesDate());
			ticketSummary.put("totalAmount", salesHeader.getTotalAmount());
			ticketSummary.put("discountAmount", salesHeader.getDiscountAmount());
			ticketSummary.put("discountPercentage", salesHeader.getDiscountPercentage());
			ticketSummary.put("discountSource", salesHeader.getDiscountSource());
			ticketSummary.put("status", salesHeader.getStatus());
			if (salesHeader.getCustomer() != null) {
				ticketSummary.put("customerId", salesHeader.getCustomer().getId());
				ticketSummary.put("customerName", salesHeader.getCustomer().getName());
				ticketSummary.put("customerCode", salesHeader.getCustomer().getCustomerCode());
			}
			Map<String, Object> response = new HashMap<>();
			response.put("ticket", ticketSummary);
			response.put("salesLines", salesLinesWithRemaining);
			response.put("canReturn", canReturn);
			response.put("isSimpleReturnEnabled", service.isSimpleReturnEnabled());
			response.put("blockReturnForPromotion", service.isBlockReturnForPromotion());

			return ResponseEntity.ok(response);

		} catch (IllegalArgumentException e) {
			log.error("ReturnHeaderAPI::getTicketDetails:validation error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("ReturnHeaderAPI::getTicketDetails:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Process return
	 */
	@PostMapping("/process-return")
	public ResponseEntity<?> processReturn(@RequestBody ProcessReturnRequestDTO request) {
		try {
			log.info("ReturnHeaderAPI::processReturn");
			UserAccount currentUser = currentUserProvider.getCurrentUser();

			// Validate request
			if (request.getTicketNumber() == null || request.getTicketNumber().trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Ticket number is required"));
			}

			if (request.getReturnType() == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("Return type is required"));
			}

			if (request.getReturnLines() == null || request.getReturnLines().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("At least one return line is required"));
			}

			// Validate return type
			if (request.getReturnType() == com.digithink.zsretail.model.enumeration.ReturnType.SIMPLE_RETURN
					&& !service.isSimpleReturnEnabled()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Simple return is not enabled"));
			}

			ReturnHeader returnHeader = service.processReturn(request, currentUser);

			// Fetch full return data including lines
			java.util.List<ReturnLine> returnLines = returnLineRepository.findByReturnHeader(returnHeader);

			// Create response map with all return data (avoiding circular references)
			Map<String, Object> returnResponse = new HashMap<>();
			returnResponse.put("id", returnHeader.getId());
			returnResponse.put("returnNumber", returnHeader.getReturnNumber());
			returnResponse.put("returnDate", returnHeader.getReturnDate());
			returnResponse.put("returnType", returnHeader.getReturnType());
			returnResponse.put("totalReturnAmount", returnHeader.getTotalReturnAmount());
			returnResponse.put("notes", returnHeader.getNotes());
			returnResponse.put("status", returnHeader.getStatus());
			returnResponse.put("discountPercentage", returnHeader.getDiscountPercentage());

			// Add original sales header info (avoiding circular references)
			if (returnHeader.getOriginalSalesHeader() != null) {
				Map<String, Object> originalSalesHeaderInfo = new HashMap<>();
				originalSalesHeaderInfo.put("id", returnHeader.getOriginalSalesHeader().getId());
				originalSalesHeaderInfo.put("salesNumber", returnHeader.getOriginalSalesHeader().getSalesNumber());
				originalSalesHeaderInfo.put("salesDate", returnHeader.getOriginalSalesHeader().getSalesDate());
				originalSalesHeaderInfo.put("totalAmount", returnHeader.getOriginalSalesHeader().getTotalAmount());
				returnResponse.put("originalSalesHeader", originalSalesHeaderInfo);
			}

			// Convert return lines to maps with item details (avoiding circular references)
			java.util.List<Map<String, Object>> returnLinesData = new java.util.ArrayList<>();
			for (ReturnLine line : returnLines) {
				Map<String, Object> lineData = new HashMap<>();
				lineData.put("id", line.getId());
				lineData.put("quantity", line.getQuantity());
				lineData.put("unitPrice", line.getUnitPrice());
				lineData.put("unitPriceIncludingVat", line.getUnitPriceIncludingVat());
				lineData.put("lineTotal", line.getLineTotal());
				lineData.put("lineTotalIncludingVat", line.getLineTotalIncludingVat());
				lineData.put("notes", line.getNotes());
				lineData.put("synched", line.getSynched());

				// Add item details
				if (line.getItem() != null) {
					Map<String, Object> itemData = new HashMap<>();
					itemData.put("id", line.getItem().getId());
					itemData.put("name", line.getItem().getName());
					itemData.put("itemCode", line.getItem().getItemCode());
					lineData.put("item", itemData);
				}

				returnLinesData.add(lineData);
			}
			returnResponse.put("returnLines", returnLinesData);

			// Add return voucher info if exists (avoiding circular references)
			if (returnHeader.getReturnVoucher() != null) {
				Map<String, Object> voucherInfo = new HashMap<>();
				voucherInfo.put("id", returnHeader.getReturnVoucher().getId());
				voucherInfo.put("voucherNumber", returnHeader.getReturnVoucher().getVoucherNumber());
				voucherInfo.put("voucherDate", returnHeader.getReturnVoucher().getVoucherDate());
				voucherInfo.put("voucherAmount", returnHeader.getReturnVoucher().getVoucherAmount());
				voucherInfo.put("expiryDate", returnHeader.getReturnVoucher().getExpiryDate());
				voucherInfo.put("status", returnHeader.getReturnVoucher().getStatus());
				voucherInfo.put("usedAmount", returnHeader.getReturnVoucher().getUsedAmount());
				voucherInfo.put("notes", returnHeader.getReturnVoucher().getNotes());
				if (returnHeader.getReturnVoucher().getCustomer() != null) {
					voucherInfo.put("customerId", returnHeader.getReturnVoucher().getCustomer().getId());
					voucherInfo.put("customerName", returnHeader.getReturnVoucher().getCustomer().getName());
				}
				returnResponse.put("returnVoucher", voucherInfo);
			}

			return ResponseEntity.ok(returnResponse);

		} catch (IllegalArgumentException e) {
			log.error("ReturnHeaderAPI::processReturn:validation error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (IllegalStateException e) {
			log.error("ReturnHeaderAPI::processReturn:state error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("ReturnHeaderAPI::processReturn:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Get return details by ID
	 */
	@GetMapping("/{id}/details")
	public ResponseEntity<?> getReturnDetails(@PathVariable Long id) {
		try {
			log.info("ReturnHeaderAPI::getReturnDetails: id=" + id);

			ReturnHeader returnHeader = service.getReturnDetails(id);

			// Get return lines
			java.util.List<ReturnLine> returnLines = returnLineRepository.findByReturnHeader(returnHeader);

			// Create response (avoiding circular references)
			Map<String, Object> response = new HashMap<>();

			// Build return header info without circular references
			Map<String, Object> returnHeaderInfo = new HashMap<>();
			returnHeaderInfo.put("id", returnHeader.getId());
			returnHeaderInfo.put("returnNumber", returnHeader.getReturnNumber());
			returnHeaderInfo.put("returnDate", returnHeader.getReturnDate());
			returnHeaderInfo.put("returnType", returnHeader.getReturnType());
			returnHeaderInfo.put("totalReturnAmount", returnHeader.getTotalReturnAmount());
			returnHeaderInfo.put("notes", returnHeader.getNotes());
			returnHeaderInfo.put("status", returnHeader.getStatus());
			returnHeaderInfo.put("synchronizationStatus", returnHeader.getSynchronizationStatus());
			returnHeaderInfo.put("erpNo", returnHeader.getErpNo());
			returnHeaderInfo.put("discountPercentage", returnHeader.getDiscountPercentage());

			if (returnHeader.getOriginalSalesHeader() != null) {
				Map<String, Object> originalSalesHeaderInfo = new HashMap<>();
				originalSalesHeaderInfo.put("id", returnHeader.getOriginalSalesHeader().getId());
				originalSalesHeaderInfo.put("salesNumber", returnHeader.getOriginalSalesHeader().getSalesNumber());
				originalSalesHeaderInfo.put("salesDate", returnHeader.getOriginalSalesHeader().getSalesDate());
				originalSalesHeaderInfo.put("totalAmount", returnHeader.getOriginalSalesHeader().getTotalAmount());
				returnHeaderInfo.put("originalSalesHeader", originalSalesHeaderInfo);
			}

			if (returnHeader.getReturnVoucher() != null) {
				Map<String, Object> voucherInfo = new HashMap<>();
				voucherInfo.put("id", returnHeader.getReturnVoucher().getId());
				voucherInfo.put("voucherNumber", returnHeader.getReturnVoucher().getVoucherNumber());
				voucherInfo.put("voucherDate", returnHeader.getReturnVoucher().getVoucherDate());
				voucherInfo.put("voucherAmount", returnHeader.getReturnVoucher().getVoucherAmount());
				voucherInfo.put("expiryDate", returnHeader.getReturnVoucher().getExpiryDate());
				voucherInfo.put("status", returnHeader.getReturnVoucher().getStatus());
				voucherInfo.put("usedAmount", returnHeader.getReturnVoucher().getUsedAmount());
				returnHeaderInfo.put("returnVoucher", voucherInfo);
			}

			// Convert return lines to maps with item details (avoiding circular references)
			java.util.List<Map<String, Object>> returnLinesData = new java.util.ArrayList<>();
			for (ReturnLine line : returnLines) {
				Map<String, Object> lineData = new HashMap<>();
				lineData.put("id", line.getId());
				lineData.put("quantity", line.getQuantity());
				lineData.put("unitPrice", line.getUnitPrice());
				lineData.put("unitPriceIncludingVat", line.getUnitPriceIncludingVat());
				lineData.put("lineTotal", line.getLineTotal());
				lineData.put("lineTotalIncludingVat", line.getLineTotalIncludingVat());
				lineData.put("notes", line.getNotes());
				lineData.put("synched", line.getSynched());

				// Add item details
				if (line.getItem() != null) {
					Map<String, Object> itemData = new HashMap<>();
					itemData.put("id", line.getItem().getId());
					itemData.put("name", line.getItem().getName());
					itemData.put("itemCode", line.getItem().getItemCode());
					lineData.put("item", itemData);
				}

				returnLinesData.add(lineData);
			}

			response.put("returnHeader", returnHeaderInfo);
			response.put("returnLines", returnLinesData);

			return ResponseEntity.ok(response);

		} catch (IllegalArgumentException e) {
			log.error("ReturnHeaderAPI::getReturnDetails:validation error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("ReturnHeaderAPI::getReturnDetails:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}
}
