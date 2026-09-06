package com.digithink.zsretail.controller;

import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.dto.ChangePaymentMethodRequestDTO;
import com.digithink.zsretail.dto.PrepareInvoiceRequestDTO;
import com.digithink.zsretail.dto.ProcessSaleRequestDTO;
import com.digithink.zsretail.dto.SplitBillRequestDTO;
import com.digithink.zsretail.model.Payment;
import com.digithink.zsretail.model.SalesHeader;
import com.digithink.zsretail.model.SalesLine;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.repository.GeneralSetupRepository;
import com.digithink.zsretail.repository.PaymentRepository;
import com.digithink.zsretail.repository.SalesLineRepository;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.SalesHeaderService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("sales-header")
@Log4j2
public class SalesHeaderAPI extends _BaseController<SalesHeader, Long, SalesHeaderService> {

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@Autowired
	private SalesLineRepository salesLineRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	private boolean getLoyaltyConfigFlag(String code) {
		return generalSetupRepository.findByCode(code)
				.map(s -> Boolean.parseBoolean(s.getValeur()))
				.orElse(true);
	}

	/**
	 * Change the payment method of a single payment on a completed ticket, without
	 * changing amounts. Admin only; allowed while the ticket's session is not yet
	 * synchronized with NAV. See {@link SalesHeaderService#changePaymentMethod}.
	 */
	@PostMapping("/{id}/change-payment-method")
	public ResponseEntity<?> changePaymentMethod(@PathVariable Long id,
			@RequestBody ChangePaymentMethodRequestDTO request) {
		try {
			log.info("SalesHeaderAPI::changePaymentMethod: " + id);
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			Payment updated = service.changePaymentMethod(id, request, currentUser);
			Map<String, Object> response = new HashMap<>();
			response.put("paymentId", updated.getId());
			response.put("paymentMethodId",
					updated.getPaymentMethod() != null ? updated.getPaymentMethod().getId() : null);
			response.put("paymentMethodName",
					updated.getPaymentMethod() != null ? updated.getPaymentMethod().getName() : null);
			response.put("totalAmount", updated.getTotalAmount());
			return ResponseEntity.ok(response);
		} catch (IllegalArgumentException e) {
			log.error("SalesHeaderAPI::changePaymentMethod: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (IllegalStateException e) {
			log.error("SalesHeaderAPI::changePaymentMethod (conflict): " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("SalesHeaderAPI::changePaymentMethod:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Process complete sale (header + lines + payment + ticket)
	 */
	@PostMapping("/process-sale")
	public ResponseEntity<?> processSale(@RequestBody ProcessSaleRequestDTO request) {
		try {
			log.info("SalesHeaderAPI::processSale");
			UserAccount currentUser = currentUserProvider.getCurrentUser();

			// Validate request
			if (request.getLines() == null || request.getLines().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Sale must have at least one item"));
			}

			if (request.getPayments() == null || request.getPayments().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("At least one payment method is required"));
			}

			// Validate each payment entry
			for (ProcessSaleRequestDTO.PaymentDTO payment : request.getPayments()) {
				if (payment.getPaymentMethodId() == null) {
					return ResponseEntity.badRequest()
							.body(createErrorResponse("Payment method is required for all payments"));
				}
				if (payment.getAmount() == null || payment.getAmount() <= 0) {
					return ResponseEntity.badRequest()
							.body(createErrorResponse("Payment amount must be greater than 0"));
				}
			}

			SalesHeader salesHeader = service.processCompleteSale(request, currentUser);

			// Fetch full sale data including lines and payments for receipt printing
			java.util.List<SalesLine> salesLines = salesLineRepository.findBySalesHeader(salesHeader);
			java.util.List<Payment> payments = paymentRepository.findBySalesHeader(salesHeader);

			// Create response map with all sale data
			Map<String, Object> saleResponse = new HashMap<>();
			saleResponse.put("id", salesHeader.getId());
			saleResponse.put("salesNumber", salesHeader.getSalesNumber());
			saleResponse.put("salesDate", salesHeader.getSalesDate());
			saleResponse.put("subtotal", salesHeader.getSubtotal());
			saleResponse.put("taxAmount", salesHeader.getTaxAmount());
			saleResponse.put("discountAmount", salesHeader.getDiscountAmount());
			saleResponse.put("discountPercentage", salesHeader.getDiscountPercentage());
			saleResponse.put("totalAmount", salesHeader.getTotalAmount());
			saleResponse.put("paidAmount", salesHeader.getPaidAmount());
			saleResponse.put("changeAmount", salesHeader.getChangeAmount());
			saleResponse.put("notes", salesHeader.getNotes());
			saleResponse.put("status", salesHeader.getStatus());
			saleResponse.put("createdByUser", salesHeader.getCreatedByUser());
			saleResponse.put("cashierSession", salesHeader.getCashierSession());
			saleResponse.put("customer", salesHeader.getCustomer());
			saleResponse.put("salesLines", salesLines);
			saleResponse.put("paymentHeaders", payments);
			saleResponse.put("loyaltyMember", salesHeader.getLoyaltyMember());
			saleResponse.put("loyaltyPointsEarned", salesHeader.getLoyaltyPointsEarned());
			saleResponse.put("loyaltyPointsRedeemed", salesHeader.getLoyaltyPointsRedeemed());
			saleResponse.put("loyaltyDeductionAmount", salesHeader.getLoyaltyDeductionAmount());
			saleResponse.put("showLoyaltyBalance", getLoyaltyConfigFlag("TICKET_SHOW_LOYALTY_BALANCE"));
			saleResponse.put("showLoyaltyEarned", getLoyaltyConfigFlag("TICKET_SHOW_LOYALTY_EARNED"));

			return ResponseEntity.ok(saleResponse);

		} catch (IllegalArgumentException e) {
			log.error("SalesHeaderAPI::processSale:validation error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (com.digithink.zsretail.exception.InsufficientStockException e) {
			log.warn("SalesHeaderAPI::processSale:insufficient stock: itemId={}", e.getItemId());
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (IllegalStateException e) {
			log.error("SalesHeaderAPI::processSale:state error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("SalesHeaderAPI::processSale:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Save a pending sale (without payments)
	 */
	@PostMapping("/save-pending")
	public ResponseEntity<?> savePendingSale(@RequestBody ProcessSaleRequestDTO request) {
		try {
			log.info("SalesHeaderAPI::savePendingSale");
			UserAccount currentUser = currentUserProvider.getCurrentUser();

			// Validate request
			if (request.getLines() == null || request.getLines().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Sale must have at least one item"));
			}

			SalesHeader salesHeader = service.savePendingSale(request, currentUser);

			// Fetch sales lines for response
			java.util.List<SalesLine> salesLines = salesLineRepository.findBySalesHeader(salesHeader);

			// Create response map
			Map<String, Object> saleResponse = new HashMap<>();
			saleResponse.put("id", salesHeader.getId());
			saleResponse.put("salesNumber", salesHeader.getSalesNumber());
			saleResponse.put("salesDate", salesHeader.getSalesDate());
			saleResponse.put("subtotal", salesHeader.getSubtotal());
			saleResponse.put("taxAmount", salesHeader.getTaxAmount());
			saleResponse.put("discountAmount", salesHeader.getDiscountAmount());
			saleResponse.put("discountPercentage", salesHeader.getDiscountPercentage());
			saleResponse.put("totalAmount", salesHeader.getTotalAmount());
			saleResponse.put("status", salesHeader.getStatus());
			saleResponse.put("notes", salesHeader.getNotes());
			saleResponse.put("createdByUser", salesHeader.getCreatedByUser());
			saleResponse.put("cashierSession", salesHeader.getCashierSession());
			saleResponse.put("customer", salesHeader.getCustomer());
			saleResponse.put("salesLines", salesLines);

			return ResponseEntity.ok(saleResponse);

		} catch (IllegalArgumentException e) {
			log.error("SalesHeaderAPI::savePendingSale:validation error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (IllegalStateException e) {
			log.error("SalesHeaderAPI::savePendingSale:state error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("SalesHeaderAPI::savePendingSale:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get pending sales for current session
	 */
	@GetMapping("/pending-sales")
	public ResponseEntity<?> getPendingSales() {
		try {
			log.info("SalesHeaderAPI::getPendingSales");
			UserAccount currentUser = currentUserProvider.getCurrentUser();

			// Get current session - if no session, return empty list (session might be
			// closed)
			java.util.Optional<com.digithink.zsretail.model.CashierSession> currentSessionOpt = service
					.getCurrentCashierSession(currentUser);

			if (!currentSessionOpt.isPresent()) {
				// No open session - return empty list (this is normal after closing a session)
				log.info("SalesHeaderAPI::getPendingSales: No open session found, returning empty list");
				return ResponseEntity.ok(new java.util.ArrayList<>());
			}

			com.digithink.zsretail.model.CashierSession currentSession = currentSessionOpt.get();

			// Get pending sales
			java.util.List<SalesHeader> pendingSales = service.getPendingSalesForSession(currentSession);

			// Sort by sales date descending (newest first)
			pendingSales.sort((s1, s2) -> {
				if (s1.getSalesDate() == null && s2.getSalesDate() == null)
					return 0;
				if (s1.getSalesDate() == null)
					return 1;
				if (s2.getSalesDate() == null)
					return -1;
				return s2.getSalesDate().compareTo(s1.getSalesDate()); // Descending order
			});

			// Fetch sales lines for each pending sale
			java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
			for (SalesHeader sale : pendingSales) {
				java.util.List<SalesLine> salesLines = salesLineRepository.findBySalesHeader(sale);

				Map<String, Object> saleMap = new HashMap<>();
				saleMap.put("id", sale.getId());
				saleMap.put("salesNumber", sale.getSalesNumber());
				saleMap.put("salesDate", sale.getSalesDate());
				saleMap.put("subtotal", sale.getSubtotal());
				saleMap.put("taxAmount", sale.getTaxAmount());
				saleMap.put("discountAmount", sale.getDiscountAmount());
				saleMap.put("totalAmount", sale.getTotalAmount());
				saleMap.put("status", sale.getStatus());
				saleMap.put("notes", sale.getNotes());
				saleMap.put("createdByUser", sale.getCreatedByUser());
				saleMap.put("customer", sale.getCustomer());
				saleMap.put("salesLines", salesLines);

				result.add(saleMap);
			}

			return ResponseEntity.ok(result);
		} catch (Exception e) {
			log.error("SalesHeaderAPI::getPendingSales:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get full table ticket data for current session (table management mode).
	 * Returns all PENDING tickets that have a tableNumber set, with their sales
	 * lines. Used by the table grid to restore cart when clicking an occupied
	 * table.
	 */
	@GetMapping("/table-tickets")
	public ResponseEntity<?> getTableTickets() {
		try {
			log.info("SalesHeaderAPI::getTableTickets");
			UserAccount currentUser = currentUserProvider.getCurrentUser();

			java.util.Optional<com.digithink.zsretail.model.CashierSession> currentSessionOpt = service
					.getCurrentCashierSession(currentUser);
			if (!currentSessionOpt.isPresent()) {
				return ResponseEntity.ok(new java.util.ArrayList<>());
			}

			java.util.List<SalesHeader> tableTickets = service.getTableTicketsForSession(currentSessionOpt.get());

			java.util.List<Map<String, Object>> result = new java.util.ArrayList<>();
			for (SalesHeader sale : tableTickets) {
				java.util.List<SalesLine> salesLines = salesLineRepository.findBySalesHeader(sale);

				Map<String, Object> saleMap = new HashMap<>();
				saleMap.put("id", sale.getId());
				saleMap.put("salesNumber", sale.getSalesNumber());
				saleMap.put("salesDate", sale.getSalesDate());
				saleMap.put("tableNumber", sale.getTableNumber());
				saleMap.put("subtotal", sale.getSubtotal());
				saleMap.put("taxAmount", sale.getTaxAmount());
				saleMap.put("discountAmount", sale.getDiscountAmount());
				saleMap.put("discountPercentage", sale.getDiscountPercentage());
				saleMap.put("totalAmount", sale.getTotalAmount());
				saleMap.put("customer", sale.getCustomer());
				saleMap.put("salesLines", salesLines);
				result.add(saleMap);
			}
			return ResponseEntity.ok(result);
		} catch (Exception e) {
			log.error("SalesHeaderAPI::getTableTickets:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get table status for current session (table management mode). Returns a map
	 * of tableNumber -> { salesHeaderId, totalAmount, salesNumber } for all
	 * occupied tables (pending tickets that have a tableNumber set).
	 */
	@GetMapping("/table-status")
	public ResponseEntity<?> getTableStatus() {
		try {
			log.info("SalesHeaderAPI::getTableStatus");
			UserAccount currentUser = currentUserProvider.getCurrentUser();

			java.util.Optional<com.digithink.zsretail.model.CashierSession> currentSessionOpt = service
					.getCurrentCashierSession(currentUser);
			if (!currentSessionOpt.isPresent()) {
				return ResponseEntity.ok(new HashMap<>());
			}

			java.util.List<SalesHeader> tableTickets = service.getTableTicketsForSession(currentSessionOpt.get());

			Map<Integer, Map<String, Object>> tableMap = new HashMap<>();
			for (SalesHeader sale : tableTickets) {
				Map<String, Object> info = new HashMap<>();
				info.put("salesHeaderId", sale.getId());
				info.put("totalAmount", sale.getTotalAmount());
				info.put("salesNumber", sale.getSalesNumber());
				tableMap.put(sale.getTableNumber(), info);
			}
			return ResponseEntity.ok(tableMap);
		} catch (Exception e) {
			log.error("SalesHeaderAPI::getTableStatus:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Update a pending sale's lines and totals (used in table management when
	 * returning to the table grid).
	 */
	@PostMapping("/update-pending/{id}")
	public ResponseEntity<?> updatePendingSale(@PathVariable Long id, @RequestBody ProcessSaleRequestDTO request) {
		try {
			log.info("SalesHeaderAPI::updatePendingSale: " + id);
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			SalesHeader updatedSale = service.updatePendingSale(id, request, currentUser);
			return ResponseEntity.ok(updatedSale);
		} catch (IllegalStateException e) {
			log.warn("SalesHeaderAPI::updatePendingSale:conflict: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("SalesHeaderAPI::updatePendingSale:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Complete a pending sale (add payments)
	 */
	@PostMapping("/complete-pending/{id}")
	public ResponseEntity<?> completePendingSale(@PathVariable Long id, @RequestBody ProcessSaleRequestDTO request) {
		try {
			log.info("SalesHeaderAPI::completePendingSale: " + id);
			UserAccount currentUser = currentUserProvider.getCurrentUser();

			// Validate request
			if (request.getPayments() == null || request.getPayments().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("At least one payment method is required"));
			}

			// Validate each payment entry
			for (ProcessSaleRequestDTO.PaymentDTO payment : request.getPayments()) {
				if (payment.getPaymentMethodId() == null) {
					return ResponseEntity.badRequest()
							.body(createErrorResponse("Payment method is required for all payments"));
				}
				if (payment.getAmount() == null || payment.getAmount() <= 0) {
					return ResponseEntity.badRequest()
							.body(createErrorResponse("Payment amount must be greater than 0"));
				}
			}

			SalesHeader salesHeader = service.completePendingSale(id, request, currentUser);

			// Fetch full sale data including lines and payments for receipt printing
			java.util.List<SalesLine> salesLines = salesLineRepository.findBySalesHeader(salesHeader);
			java.util.List<Payment> payments = paymentRepository.findBySalesHeader(salesHeader);

			// Create response map with all sale data
			Map<String, Object> saleResponse = new HashMap<>();
			saleResponse.put("id", salesHeader.getId());
			saleResponse.put("salesNumber", salesHeader.getSalesNumber());
			saleResponse.put("salesDate", salesHeader.getSalesDate());
			saleResponse.put("subtotal", salesHeader.getSubtotal());
			saleResponse.put("taxAmount", salesHeader.getTaxAmount());
			saleResponse.put("discountAmount", salesHeader.getDiscountAmount());
			saleResponse.put("discountPercentage", salesHeader.getDiscountPercentage());
			saleResponse.put("totalAmount", salesHeader.getTotalAmount());
			saleResponse.put("paidAmount", salesHeader.getPaidAmount());
			saleResponse.put("changeAmount", salesHeader.getChangeAmount());
			saleResponse.put("notes", salesHeader.getNotes());
			saleResponse.put("status", salesHeader.getStatus());
			saleResponse.put("createdByUser", salesHeader.getCreatedByUser());
			saleResponse.put("cashierSession", salesHeader.getCashierSession());
			saleResponse.put("customer", salesHeader.getCustomer());
			saleResponse.put("salesLines", salesLines);
			saleResponse.put("paymentHeaders", payments);

			return ResponseEntity.ok(saleResponse);

		} catch (IllegalArgumentException e) {
			log.error("SalesHeaderAPI::completePendingSale:validation error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (com.digithink.zsretail.exception.InsufficientStockException e) {
			log.warn("SalesHeaderAPI::completePendingSale:insufficient stock: itemId={}", e.getItemId());
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (IllegalStateException e) {
			log.error("SalesHeaderAPI::completePendingSale:state error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("SalesHeaderAPI::completePendingSale:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Cancel/Delete a pending sale
	 */
	@PostMapping("/cancel-pending/{id}")
	public ResponseEntity<?> cancelPendingSale(@PathVariable Long id) {
		try {
			log.info("SalesHeaderAPI::cancelPendingSale: " + id);
			UserAccount currentUser = currentUserProvider.getCurrentUser();

			service.cancelPendingSale(id, currentUser);

			Map<String, Object> response = new HashMap<>();
			response.put("message", "Pending ticket cancelled successfully");
			return ResponseEntity.ok(response);

		} catch (IllegalArgumentException e) {
			log.error("SalesHeaderAPI::cancelPendingSale:validation error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (IllegalStateException e) {
			log.error("SalesHeaderAPI::cancelPendingSale:state error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("SalesHeaderAPI::cancelPendingSale:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Split-bill: pay for a subset of lines on a pending table ticket. Creates a
	 * new completed sale for the selected lines, removes them from the original.
	 * Returns the new completed SalesHeader for receipt printing.
	 */
	@PostMapping("/split-and-pay/{id}")
	public ResponseEntity<?> splitAndPay(@PathVariable Long id, @RequestBody SplitBillRequestDTO request) {
		try {
			log.info("SalesHeaderAPI::splitAndPay: originalId={}", id);
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			SalesHeader splitSale = service.splitAndPay(id, request, currentUser);
			// Return minimal response for receipt printing
			Map<String, Object> response = new HashMap<>();
			response.put("id", splitSale.getId());
			response.put("salesNumber", splitSale.getSalesNumber());
			response.put("totalAmount", splitSale.getTotalAmount());
			response.put("paidAmount", splitSale.getPaidAmount());
			response.put("changeAmount", splitSale.getChangeAmount());
			response.put("completedDate", splitSale.getCompletedDate());
			response.put("customer", splitSale.getCustomer());
			java.util.List<SalesLine> lines = salesLineRepository.findBySalesHeader(splitSale);
			response.put("salesLines", lines);
			return ResponseEntity.ok(response);
		} catch (IllegalStateException e) {
			log.warn("SalesHeaderAPI::splitAndPay:conflict: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (IllegalArgumentException e) {
			log.warn("SalesHeaderAPI::splitAndPay:badRequest: " + e.getMessage());
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("SalesHeaderAPI::splitAndPay:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Transfer a pending table ticket to a different table number.
	 */
	@org.springframework.web.bind.annotation.PatchMapping("/{id}/table-number")
	public ResponseEntity<?> transferTable(@PathVariable Long id, @RequestBody Map<String, Integer> body) {
		try {
			Integer targetTableNumber = body.get("tableNumber");
			if (targetTableNumber == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("tableNumber is required"));
			}
			log.info("SalesHeaderAPI::transferTable: ticket {} → table {}", id, targetTableNumber);
			service.transferTable(id, targetTableNumber);
			Map<String, Object> response = new HashMap<>();
			response.put("message", "Table transferred successfully");
			return ResponseEntity.ok(response);
		} catch (IllegalStateException e) {
			log.warn("SalesHeaderAPI::transferTable:conflict: " + e.getMessage());
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("SalesHeaderAPI::transferTable:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get tickets/sales with filters (for admin)
	 */
	@GetMapping("/history")
	public ResponseEntity<?> getTicketsHistory(
			@org.springframework.web.bind.annotation.RequestParam(required = false) String dateFrom,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String dateTo,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String status,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String syncStatus,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String paymentMethodId,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String search,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String minPrice,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String maxPrice,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String familyId,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String subFamilyId,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String cashierId,
			@org.springframework.web.bind.annotation.RequestParam(required = false) String sessionNumber,
			@org.springframework.web.bind.annotation.RequestParam(defaultValue = "0") int page,
			@org.springframework.web.bind.annotation.RequestParam(defaultValue = "10") int size) {
		try {
			org.springframework.data.domain.Page<SalesHeader> ticketPage = service.getTicketsHistory(
					dateFrom, dateTo, status, syncStatus, paymentMethodId, search,
					minPrice, maxPrice, familyId, subFamilyId, cashierId, sessionNumber, page, size);

			java.util.List<Map<String, Object>> content = new java.util.ArrayList<>();
			for (SalesHeader ticket : ticketPage.getContent()) {
				java.util.List<SalesLine> salesLines = salesLineRepository.findBySalesHeader(ticket);
				java.util.List<Payment> payments = paymentRepository.findBySalesHeader(ticket);

				Map<String, Object> ticketMap = new HashMap<>();
				ticketMap.put("id", ticket.getId());
				ticketMap.put("salesNumber", ticket.getSalesNumber());
				ticketMap.put("salesDate", ticket.getSalesDate());
				ticketMap.put("subtotal", ticket.getSubtotal());
				ticketMap.put("taxAmount", ticket.getTaxAmount());
				ticketMap.put("discountAmount", ticket.getDiscountAmount());
				ticketMap.put("discountPercentage", ticket.getDiscountPercentage());
				ticketMap.put("totalAmount", ticket.getTotalAmount());
				ticketMap.put("paidAmount", ticket.getPaidAmount());
				ticketMap.put("changeAmount", ticket.getChangeAmount());
				ticketMap.put("status", ticket.getStatus());
				ticketMap.put("notes", ticket.getNotes());
				ticketMap.put("createdByUser", ticket.getCreatedByUser());
				ticketMap.put("cashierSession", ticket.getCashierSession());
				ticketMap.put("customer", ticket.getCustomer());
				ticketMap.put("salesLinesCount", salesLines.size());
				ticketMap.put("paymentsCount", payments.size());
				ticketMap.put("synchronizationStatus", ticket.getSynchronizationStatus());
				ticketMap.put("erpNo", ticket.getErpNo());
				ticketMap.put("invoiced", ticket.getInvoiced());
				ticketMap.put("fiscalRegistration", ticket.getFiscalRegistration());

				content.add(ticketMap);
			}

			Map<String, Object> response = new HashMap<>();
			response.put("content", content);
			response.put("totalElements", ticketPage.getTotalElements());
			response.put("totalPages", ticketPage.getTotalPages());
			response.put("number", ticketPage.getNumber());
			response.put("size", ticketPage.getSize());

			return ResponseEntity.ok(response);
		} catch (Exception e) {
			log.error("SalesHeaderAPI::getTicketsHistory:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Prepare invoice for a completed ticket (set invoiced=true and fiscal
	 * registration). Sync to ERP happens via existing sync tasks when ERP is
	 * available.
	 */
	@PostMapping("/{id}/prepare-invoice")
	public ResponseEntity<?> prepareInvoice(@PathVariable Long id, @RequestBody PrepareInvoiceRequestDTO request) {
		try {
			log.info("SalesHeaderAPI::prepareInvoice: " + id);
			if (request == null || request.getFiscalRegistration() == null
					|| request.getFiscalRegistration().trim().isEmpty()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Fiscal Registration is mandatory"));
			}
			SalesHeader updated = service.prepareInvoice(id, request.getFiscalRegistration().trim(),
					request.getInvoiceCustomerName() != null ? request.getInvoiceCustomerName().trim() : null);
			Map<String, Object> response = new HashMap<>();
			response.put("id", updated.getId());
			response.put("invoiced", updated.getInvoiced());
			response.put("fiscalRegistration", updated.getFiscalRegistration());
			response.put("invoiceCustomerName", updated.getInvoiceCustomerName());
			response.put("synchronizationStatus", updated.getSynchronizationStatus());
			return ResponseEntity.ok(response);
		} catch (IllegalArgumentException e) {
			log.error("SalesHeaderAPI::prepareInvoice: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("SalesHeaderAPI::prepareInvoice:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get ticket details by ID (for admin)
	 */
	@GetMapping("/{id}/details")
	public ResponseEntity<?> getTicketDetails(@PathVariable Long id) {
		try {
			log.info("SalesHeaderAPI::getTicketDetails: " + id);

			java.util.Optional<SalesHeader> ticketOpt = service.findById(id);
			if (!ticketOpt.isPresent()) {
				return ResponseEntity.notFound().build();
			}

			SalesHeader ticket = ticketOpt.get();
			java.util.List<SalesLine> salesLines = salesLineRepository.findBySalesHeader(ticket);
			java.util.List<Payment> payments = paymentRepository.findBySalesHeader(ticket);

			Map<String, Object> ticketMap = new HashMap<>();
			ticketMap.put("id", ticket.getId());
			ticketMap.put("salesNumber", ticket.getSalesNumber());
			ticketMap.put("salesDate", ticket.getSalesDate());
			ticketMap.put("subtotal", ticket.getSubtotal());
			ticketMap.put("taxAmount", ticket.getTaxAmount());
			ticketMap.put("discountAmount", ticket.getDiscountAmount());
			ticketMap.put("discountPercentage", ticket.getDiscountPercentage());
			ticketMap.put("totalAmount", ticket.getTotalAmount());
			ticketMap.put("paidAmount", ticket.getPaidAmount());
			ticketMap.put("changeAmount", ticket.getChangeAmount());
			ticketMap.put("status", ticket.getStatus());
			ticketMap.put("notes", ticket.getNotes());
			ticketMap.put("completedDate", ticket.getCompletedDate());
			ticketMap.put("createdByUser", ticket.getCreatedByUser());
			ticketMap.put("cashierSession", ticket.getCashierSession());
			ticketMap.put("customer", ticket.getCustomer());
			ticketMap.put("synchronizationStatus", ticket.getSynchronizationStatus());
			ticketMap.put("erpNo", ticket.getErpNo());
			ticketMap.put("invoiced", ticket.getInvoiced());
			ticketMap.put("fiscalRegistration", ticket.getFiscalRegistration());
			ticketMap.put("salesLines", salesLines);
			ticketMap.put("payments", payments);
			ticketMap.put("loyaltyDeductionAmount", ticket.getLoyaltyDeductionAmount());
			ticketMap.put("loyaltyPointsRedeemed", ticket.getLoyaltyPointsRedeemed());
			ticketMap.put("loyaltyPointsEarned", ticket.getLoyaltyPointsEarned());
			ticketMap.put("loyaltyMember", ticket.getLoyaltyMember());
			ticketMap.put("showLoyaltyBalance", getLoyaltyConfigFlag("TICKET_SHOW_LOYALTY_BALANCE"));
			ticketMap.put("showLoyaltyEarned", getLoyaltyConfigFlag("TICKET_SHOW_LOYALTY_EARNED"));

			return ResponseEntity.ok(ticketMap);
		} catch (Exception e) {
			log.error("SalesHeaderAPI::getTicketDetails:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}
