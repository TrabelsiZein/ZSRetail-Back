package com.digithink.zsretail.controller;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.exception.CashDiscrepancyException;
import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.CashierSessionService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("cashier-session")
@Log4j2
public class CashierSessionAPI extends _BaseController<CashierSession, Long, CashierSessionService> {

	@Autowired
	private CurrentUserProvider currentUserProvider;

	/**
	 * Get current user's open session
	 */
	@GetMapping("/current")
	public ResponseEntity<?> getCurrentSession() {
		try {
			log.info("CashierSessionAPI::getCurrentSession");
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			java.util.Optional<CashierSession> session = service.getCurrentOpenSession(currentUser);
			
			if (session.isPresent()) {
				return ResponseEntity.ok(session.get());
			} else {
				return ResponseEntity.ok(null);
			}
		} catch (Exception e) {
			log.error("CashierSessionAPI::getCurrentSession:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Open a new cashier session
	 */
	@PostMapping("/open")
	public ResponseEntity<?> openSession(@RequestBody Map<String, Object> request) {
		try {
			log.info("CashierSessionAPI::openSession");
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			
			Double openingCash = null;
			if (request.get("openingCash") instanceof Number) {
				openingCash = ((Number) request.get("openingCash")).doubleValue();
			} else if (request.get("openingCash") instanceof String) {
				openingCash = Double.parseDouble((String) request.get("openingCash"));
			}

			if (openingCash == null || openingCash < 0) {
				return ResponseEntity.badRequest().body(createErrorResponse("Opening cash must be a positive number"));
			}

			CashierSession session = service.openSession(currentUser, openingCash);
			return ResponseEntity.ok(session);
		} catch (IllegalStateException e) {
			log.error("CashierSessionAPI::openSession:error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::openSession:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Close current session (supports both old format and new cash count format)
	 */
	@PostMapping("/close")
	public ResponseEntity<?> closeSession(@RequestBody Map<String, Object> request) {
		try {
			log.info("CashierSessionAPI::closeSession");
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			java.util.Optional<CashierSession> currentSession = service.getCurrentOpenSession(currentUser);

			if (!currentSession.isPresent()) {
				return ResponseEntity.badRequest().body(createErrorResponse("No open session found"));
			}

			// Check if new format with cash count lines
			if (request.containsKey("cashCountLines")) {
				// New format with detailed cash count
				com.digithink.zsretail.dto.CloseSessionRequestDTO closeRequest = new com.digithink.zsretail.dto.CloseSessionRequestDTO();
				
				Double actualCash = null;
				if (request.get("actualCash") instanceof Number) {
					actualCash = ((Number) request.get("actualCash")).doubleValue();
				} else if (request.get("actualCash") instanceof String) {
					actualCash = Double.parseDouble((String) request.get("actualCash"));
				}
				closeRequest.setActualCash(actualCash);
				
				String notes = (String) request.get("notes");
				closeRequest.setNotes(notes);
				
				// Parse badge code and permission (optional, for discrepancy validation)
				if (request.get("badgeCode") != null) {
					closeRequest.setBadgeCode((String) request.get("badgeCode"));
				}
				if (request.get("badgePermission") != null) {
					closeRequest.setBadgePermission((String) request.get("badgePermission"));
				}
				
				// Parse cash count lines
				@SuppressWarnings("unchecked")
				java.util.List<Map<String, Object>> cashCountLinesData = 
					(java.util.List<Map<String, Object>>) request.get("cashCountLines");
				
				if (cashCountLinesData != null) {
					java.util.List<com.digithink.zsretail.dto.CloseSessionRequestDTO.CashCountLineDTO> cashCountLines = 
						new java.util.ArrayList<>();
					
					for (Map<String, Object> lineData : cashCountLinesData) {
						com.digithink.zsretail.dto.CloseSessionRequestDTO.CashCountLineDTO lineDTO = 
							new com.digithink.zsretail.dto.CloseSessionRequestDTO.CashCountLineDTO();
						
						// Denomination value
						if (lineData.get("denominationValue") instanceof Number) {
							lineDTO.setDenominationValue(((Number) lineData.get("denominationValue")).doubleValue());
						} else if (lineData.get("denominationValue") instanceof String) {
							lineDTO.setDenominationValue(Double.parseDouble((String) lineData.get("denominationValue")));
						}
						
						// Quantity
						if (lineData.get("quantity") instanceof Number) {
							lineDTO.setQuantity(((Number) lineData.get("quantity")).intValue());
						} else if (lineData.get("quantity") instanceof String) {
							lineDTO.setQuantity(Integer.parseInt((String) lineData.get("quantity")));
						}
						
						// Payment method ID (optional)
						if (lineData.get("paymentMethodId") != null) {
							if (lineData.get("paymentMethodId") instanceof Number) {
								lineDTO.setPaymentMethodId(((Number) lineData.get("paymentMethodId")).longValue());
							} else if (lineData.get("paymentMethodId") instanceof String) {
								lineDTO.setPaymentMethodId(Long.parseLong((String) lineData.get("paymentMethodId")));
							}
						}
						
						// Reference number (optional)
						if (lineData.get("referenceNumber") != null) {
							lineDTO.setReferenceNumber((String) lineData.get("referenceNumber"));
						}
						
						// Notes (optional)
						if (lineData.get("notes") != null) {
							lineDTO.setNotes((String) lineData.get("notes"));
						}
						
						cashCountLines.add(lineDTO);
					}
					
					closeRequest.setCashCountLines(cashCountLines);
				} else {
					closeRequest.setCashCountLines(new java.util.ArrayList<>());
				}
				
				CashierSession closedSession = service.closeSessionWithCashCount(
					currentSession.get().getId(), closeRequest);
				com.digithink.zsretail.dto.SessionCloseTicketDTO ticketData = service.getSessionCloseTicketData(closedSession.getId());
				return ResponseEntity.ok(java.util.Map.of("session", closedSession, "sessionCloseTicket", ticketData));
			} else {
				// Legacy format (backward compatibility)
				Double actualCash = null;
				if (request.get("actualCash") instanceof Number) {
					actualCash = ((Number) request.get("actualCash")).doubleValue();
				} else if (request.get("actualCash") instanceof String) {
					actualCash = Double.parseDouble((String) request.get("actualCash"));
				}

				String notes = (String) request.get("notes");

				CashierSession closedSession = service.closeSession(currentSession.get().getId(), actualCash, notes);
				com.digithink.zsretail.dto.SessionCloseTicketDTO ticketData = service.getSessionCloseTicketData(closedSession.getId());
				return ResponseEntity.ok(java.util.Map.of("session", closedSession, "sessionCloseTicket", ticketData));
			}
		} catch (CashDiscrepancyException e) {
			log.warn("CashierSessionAPI::closeSession:cash discrepancy detected: " + e.getMessage());
			return ResponseEntity.badRequest().body(e.getErrorData());
		} catch (IllegalStateException e) {
			log.error("CashierSessionAPI::closeSession:error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::closeSession:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Close a session by ID (for admin users)
	 */
	@PostMapping("/{id}/close")
	public ResponseEntity<?> closeSessionById(@PathVariable Long id, @RequestBody Map<String, Object> request) {
		try {
			log.info("CashierSessionAPI::closeSessionById: " + id);

			// Get session
			java.util.Optional<CashierSession> sessionOpt = service.findById(id);
			if (!sessionOpt.isPresent()) {
				return ResponseEntity.badRequest().body(createErrorResponse("Session not found"));
			}
			
			CashierSession session = sessionOpt.get();
			
			// Check if session is already closed
			if (session.getStatus() != com.digithink.zsretail.model.enumeration.SessionStatus.OPENED) {
				return ResponseEntity.badRequest().body(createErrorResponse("Session is not open"));
			}
			
			// Parse request similar to closeSession method
			com.digithink.zsretail.dto.CloseSessionRequestDTO closeRequest = new com.digithink.zsretail.dto.CloseSessionRequestDTO();
			
			String notes = (String) request.get("notes");
			closeRequest.setNotes(notes);
			
			// Parse cash count lines
			@SuppressWarnings("unchecked")
			java.util.List<Map<String, Object>> cashCountLinesData = 
				(java.util.List<Map<String, Object>>) request.get("cashCountLines");
			
			if (cashCountLinesData != null && !cashCountLinesData.isEmpty()) {
				java.util.List<com.digithink.zsretail.dto.CloseSessionRequestDTO.CashCountLineDTO> cashCountLines = 
					new java.util.ArrayList<>();
				
				for (Map<String, Object> lineData : cashCountLinesData) {
					com.digithink.zsretail.dto.CloseSessionRequestDTO.CashCountLineDTO lineDTO = 
						new com.digithink.zsretail.dto.CloseSessionRequestDTO.CashCountLineDTO();
					
					// Denomination value
					if (lineData.get("denominationValue") != null) {
						if (lineData.get("denominationValue") instanceof Number) {
							lineDTO.setDenominationValue(((Number) lineData.get("denominationValue")).doubleValue());
						} else if (lineData.get("denominationValue") instanceof String) {
							lineDTO.setDenominationValue(Double.parseDouble((String) lineData.get("denominationValue")));
						}
					}
					
					// Quantity
					if (lineData.get("quantity") != null) {
						if (lineData.get("quantity") instanceof Number) {
							lineDTO.setQuantity(((Number) lineData.get("quantity")).intValue());
						} else if (lineData.get("quantity") instanceof String) {
							lineDTO.setQuantity(Integer.parseInt((String) lineData.get("quantity")));
						}
					}
					
					// Payment method ID (optional)
					if (lineData.get("paymentMethodId") != null) {
						if (lineData.get("paymentMethodId") instanceof Number) {
							lineDTO.setPaymentMethodId(((Number) lineData.get("paymentMethodId")).longValue());
						} else if (lineData.get("paymentMethodId") instanceof String) {
							lineDTO.setPaymentMethodId(Long.parseLong((String) lineData.get("paymentMethodId")));
						}
					}
					
					// Reference number (optional)
					if (lineData.get("referenceNumber") != null) {
						lineDTO.setReferenceNumber((String) lineData.get("referenceNumber"));
					}
					
					// Notes (optional)
					if (lineData.get("notes") != null) {
						lineDTO.setNotes((String) lineData.get("notes"));
					}
					
					cashCountLines.add(lineDTO);
				}
				
				closeRequest.setCashCountLines(cashCountLines);
			} else {
				closeRequest.setCashCountLines(new java.util.ArrayList<>());
			}
			
			CashierSession closedSession = service.closeSessionWithCashCount(id, closeRequest);
			com.digithink.zsretail.dto.SessionCloseTicketDTO ticketData = service.getSessionCloseTicketData(closedSession.getId());
			return ResponseEntity.ok(java.util.Map.of("session", closedSession, "sessionCloseTicket", ticketData));
		} catch (Exception e) {
			log.error("CashierSessionAPI::closeSessionById:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Verify session and set responsible closure cash (for responsible user)
	 */
	@PostMapping("/verify")
	public ResponseEntity<?> verifySession(@RequestBody Map<String, Object> request) {
		try {
			log.info("CashierSessionAPI::verifySession");
			UserAccount currentUser = currentUserProvider.getCurrentUser();

			Long sessionId = null;
			if (request.get("sessionId") instanceof Number) {
				sessionId = ((Number) request.get("sessionId")).longValue();
			} else if (request.get("sessionId") instanceof String) {
				sessionId = Long.parseLong((String) request.get("sessionId"));
			}

			if (sessionId == null) {
				return ResponseEntity.badRequest().body(createErrorResponse("Session ID is required"));
			}

			Double responsibleClosureCash = null;
			if (request.get("responsibleClosureCash") instanceof Number) {
				responsibleClosureCash = ((Number) request.get("responsibleClosureCash")).doubleValue();
			} else if (request.get("responsibleClosureCash") instanceof String) {
				responsibleClosureCash = Double.parseDouble((String) request.get("responsibleClosureCash"));
			}

			if (responsibleClosureCash == null || responsibleClosureCash < 0) {
				return ResponseEntity.badRequest().body(createErrorResponse("Responsible closure cash must be a positive number"));
			}

			String verificationNotes = (String) request.get("verificationNotes");

			@SuppressWarnings("unchecked")
			List<Map<String, Object>> paymentDetails = request.get("paymentDetails") instanceof List
				? (List<Map<String, Object>>) request.get("paymentDetails") : null;

			CashierSession verifiedSession = service.verifySession(sessionId, responsibleClosureCash, verificationNotes, currentUser, paymentDetails);
			return ResponseEntity.ok(verifiedSession);
		} catch (IllegalStateException e) {
			log.error("CashierSessionAPI::verifySession:error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::verifySession:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get paginated session history with filters (admin & responsible)
	 */
	@GetMapping("/history")
	public ResponseEntity<?> getSessionHistory(
			@RequestParam(required = false) String search,
			@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) String syncStatus,
			@RequestParam(required = false) String cashierId,
			@RequestParam(required = false) String minTotalSales,
			@RequestParam(required = false) String maxTotalSales,
			@RequestParam(required = false) String minSalesCount,
			@RequestParam(required = false) String maxSalesCount,
			@RequestParam(required = false) String differenceSign,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		try {
			log.info("CashierSessionAPI::getSessionHistory");
			return ResponseEntity.ok(service.getSessionHistory(search, dateFrom, dateTo, status, syncStatus, cashierId, minTotalSales, maxTotalSales, minSalesCount, maxSalesCount, differenceSign, page, size));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getSessionHistory:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get session dashboard - list all sessions with statistics (admin & responsible)
	 */
	@GetMapping("/dashboard")
	public ResponseEntity<?> getSessionDashboard() {
		try {
			log.info("CashierSessionAPI::getSessionDashboard");

			return ResponseEntity.ok(service.getSessionDashboard());
		} catch (Exception e) {
			log.error("CashierSessionAPI::getSessionDashboard:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Get session by ID with full details including cash count lines (admin & responsible)
	 */
	@GetMapping("/{id}/details")
	public ResponseEntity<?> getSessionDetails(@PathVariable Long id) {
		try {
			log.info("CashierSessionAPI::getSessionDetails: " + id);

			return ResponseEntity.ok(service.getSessionDetails(id));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getSessionDetails:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}

