package com.digithink.zsretail.store.controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.exception.CashDiscrepancyException;
import com.digithink.zsretail.store.exception.TillRightRequiredException;
import com.digithink.zsretail.store.posmachine.DrawerRuleException;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;
import com.digithink.zsretail.store.service.CashierSessionService;
import com.digithink.zsretail.store.service.SessionReportService;
import com.digithink.zsretail.store.service.StoreReportService;
import com.digithink.zsretail.store.service.DayJournalService;
import com.digithink.zsretail.store.service.StoreDaysService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("cashier-session")
@Log4j2
public class CashierSessionAPI extends _BaseController<CashierSession, Long, CashierSessionService> {

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@Autowired
	private SessionReportService sessionReportService;

	@Autowired
	private StoreReportService storeReportService;

	@Autowired
	private DayJournalService dayJournalService;

	@Autowired
	private StoreDaysService storeDaysService;

	/** 3.1.2, the Journal's gains: only where the store keeps its own stock. */
	@Autowired
	private ApplicationModeService applicationModeService;

	/** 3.2.0, POS machines step 2, part B: the till's question about its POS machine. */
	@Autowired
	private SessionPosMachineService sessionPosMachineService;

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
	 * 3.1.1: how many tickets the current user's open session holds, {"ticketCount": n} (0 without a session); read by
	 * the till when its screen opens (catalogue hidden, the right rail). A count only, never an amount: the closing
	 * count stays blind.
	 */
	/**
	 * 3.2.0, step 6 polish: the payment methods of the current user's open session's sales, active or not (empty without
	 * a session): the closing window's rows for a method switched off since. Methods only, never an amount (a blind count).
	 */
	@GetMapping("/current/payment-methods")
	public ResponseEntity<?> getCurrentSessionPaymentMethods() {
		try {
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			return ResponseEntity.ok(service.getCurrentOpenSession(currentUser).map(service::sessionPaymentMethods)
					.orElse(Collections.emptyList()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getCurrentSessionPaymentMethods:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * 3.2.0, POS machines step 2, part B: what the till reads when its screens open (TillPosMachine): who the drawer
	 * belongs to, the address seen, the POS machine of the current user's open session, and whether this PC is refused
	 * selling, holding and returning for it (rule 3). Any logged user; refuses nothing itself.
	 */
	@GetMapping("/current/pos-machine")
	public ResponseEntity<?> getCurrentPosMachine(HttpServletRequest http) {
		try {
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			return ResponseEntity.ok(sessionPosMachineService.tillPosMachine(currentUser, http.getRemoteAddr()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getCurrentPosMachine:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * 3.2.0, the opening amount: the float proposed to the logged user's opening from this PC (what the responsible left
	 * in the same drawer at the verification of its most recent closed session): {proposedFloat, sourceSessionNumber,
	 * sourceVerifiedAt, ...}, or {} when none (the opening is free).
	 */
	@GetMapping("/opening-proposal")
	public ResponseEntity<?> getOpeningProposal(HttpServletRequest http) {
		try {
			UserAccount user = currentUserProvider.getCurrentUser();
			return ResponseEntity.ok(service.openingProposal(user, http.getRemoteAddr()).<Object>map(p -> p)
					.orElse(java.util.Collections.emptyMap()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getOpeningProposal:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	@GetMapping("/current/ticket-count")
	public ResponseEntity<?> getCurrentTicketCount() {
		try {
			UserAccount currentUser = currentUserProvider.getCurrentUser();
			long count = service.getCurrentOpenSession(currentUser).map(service::countSessionTickets).orElse(0L);
			return ResponseEntity.ok(Collections.singletonMap("ticketCount", count));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getCurrentTicketCount:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Open a new cashier session
	 */
	@PostMapping("/open")
	public ResponseEntity<?> openSession(@RequestBody Map<String, Object> request, HttpServletRequest http) {
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

			// 3.2.0, POS machines step 2: the session keeps the POS machine of this PC (its address)
			CashierSession session = service.openSession(currentUser, openingCash, http.getRemoteAddr());
			return ResponseEntity.ok(session);
		} catch (DrawerRuleException e) {
			// 3.2.0, POS machines step 2, part B: the drawer of the POS machine, rules 1 and 2 (409, its code and values)
			log.info("CashierSessionAPI::openSession refused: {}", e.getMessage());
			return ResponseEntity.status(409).body(e.body());
		} catch (IllegalStateException e) {
			log.error("CashierSessionAPI::openSession:error: " + e.getMessage(), e);
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::openSession:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Close the current user's session: the counted lines (cashCountLines, optional), actualCash (optional), notes, the
	 * badge grant (tillGrant) and the confirmation of a cash difference (discrepancyConfirmed); one path for every body
	 * (3.2.0, POS machines step 3).
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

			// 3.2.0, POS machines step 3: one closing path. A body without cashCountLines (the old {actualCash, notes},
			// or the till's {notes} when its closing window has no row) closes as the till's body, with no counted line:
			// the old path called the closing from this class, outside its transaction, and its row lock answered 500
			// "no transaction is in progress" (since 1.8.0)
			com.digithink.zsretail.store.dto.CloseSessionRequestDTO closeRequest = new com.digithink.zsretail.store.dto.CloseSessionRequestDTO();
			
			Double actualCash = null;
			if (request.get("actualCash") instanceof Number) {
				actualCash = ((Number) request.get("actualCash")).doubleValue();
			} else if (request.get("actualCash") instanceof String) {
				actualCash = Double.parseDouble((String) request.get("actualCash"));
			}
			closeRequest.setActualCash(actualCash);
			
			String notes = (String) request.get("notes");
			closeRequest.setNotes(notes);
			
			// Step D (3.1.0): the grant of the badge scan for a close with a cash difference (badgeCode and
			// badgePermission are no longer read)
			if (request.get("tillGrant") != null) {
				closeRequest.setTillGrant(String.valueOf(request.get("tillGrant")));
			}
			// The user saw the difference warning and confirmed it: counts when his role holds the right (step C)
			closeRequest.setDiscrepancyConfirmed(Boolean.TRUE.equals(request.get("discrepancyConfirmed")));
			
			// Parse cash count lines
			@SuppressWarnings("unchecked")
			java.util.List<Map<String, Object>> cashCountLinesData = 
				(java.util.List<Map<String, Object>>) request.get("cashCountLines");
			
			if (cashCountLinesData != null) {
				java.util.List<com.digithink.zsretail.store.dto.CloseSessionRequestDTO.CashCountLineDTO> cashCountLines = 
					new java.util.ArrayList<>();
				
				for (Map<String, Object> lineData : cashCountLinesData) {
					com.digithink.zsretail.store.dto.CloseSessionRequestDTO.CashCountLineDTO lineDTO = 
						new com.digithink.zsretail.store.dto.CloseSessionRequestDTO.CashCountLineDTO();
					
					// Denomination value
					if (lineData.get("denominationValue") instanceof Number) {
						lineDTO.setDenominationValue(((Number) lineData.get("denominationValue")).doubleValue());
					} else if (lineData.get("denominationValue") instanceof String) {
						lineDTO.setDenominationValue(Double.parseDouble((String) lineData.get("denominationValue")));
					}
					
					// Quantity
					if (lineData.get("quantity") instanceof Number) {
						lineDTO.setQuantity(cashCountQuantity((Number) lineData.get("quantity")));
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
			com.digithink.zsretail.store.dto.SessionCloseTicketDTO ticketData = service.getSessionCloseTicketData(closedSession.getId());
			return ResponseEntity.ok(java.util.Map.of("session", closedSession, "sessionCloseTicket", ticketData));
		} catch (TillRightRequiredException e) {
			log.warn("CashierSessionAPI::closeSession:till right required: " + e.getMessage());
			return e.response();
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
			if (session.getStatus() != com.digithink.zsretail.core.model.enumeration.SessionStatus.OPENED) {
				return ResponseEntity.badRequest().body(createErrorResponse("Session is not open"));
			}
			
			// Parse request similar to closeSession method
			com.digithink.zsretail.store.dto.CloseSessionRequestDTO closeRequest = new com.digithink.zsretail.store.dto.CloseSessionRequestDTO();
			
			String notes = (String) request.get("notes");
			closeRequest.setNotes(notes);
			
			// Parse cash count lines
			@SuppressWarnings("unchecked")
			java.util.List<Map<String, Object>> cashCountLinesData = 
				(java.util.List<Map<String, Object>>) request.get("cashCountLines");
			
			if (cashCountLinesData != null && !cashCountLinesData.isEmpty()) {
				java.util.List<com.digithink.zsretail.store.dto.CloseSessionRequestDTO.CashCountLineDTO> cashCountLines = 
					new java.util.ArrayList<>();
				
				for (Map<String, Object> lineData : cashCountLinesData) {
					com.digithink.zsretail.store.dto.CloseSessionRequestDTO.CashCountLineDTO lineDTO = 
						new com.digithink.zsretail.store.dto.CloseSessionRequestDTO.CashCountLineDTO();
					
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
							lineDTO.setQuantity(cashCountQuantity((Number) lineData.get("quantity")));
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
			com.digithink.zsretail.store.dto.SessionCloseTicketDTO ticketData = service.getSessionCloseTicketData(closedSession.getId());
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

			// 3.2.0, the opening amount: the required "Retrait du tiroir" {choice: ALL | NONE | AMOUNT, amount}
			String withdrawalChoice = null;
			Double withdrawalAmount = null;
			if (request.get("drawerWithdrawal") instanceof Map) {
				Map<?, ?> withdrawal = (Map<?, ?>) request.get("drawerWithdrawal");
				withdrawalChoice = withdrawal.get("choice") != null ? String.valueOf(withdrawal.get("choice")) : null;
				withdrawalAmount = withdrawal.get("amount") instanceof Number
						? ((Number) withdrawal.get("amount")).doubleValue() : null;
			}

			CashierSession verifiedSession = service.verifySession(sessionId, responsibleClosureCash, verificationNotes,
					currentUser, paymentDetails, withdrawalChoice, withdrawalAmount);
			return ResponseEntity.ok(verifiedSession);
		} catch (com.digithink.zsretail.store.cashmovement.CashMovementException e) {
			log.info("CashierSessionAPI::verifySession refused: {}", e.getMessage());
			return ResponseEntity.badRequest().body(e.body());
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
			@RequestParam(required = false) String posMachineId,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "10") int size) {
		try {
			log.info("CashierSessionAPI::getSessionHistory");
			return ResponseEntity.ok(service.getSessionHistory(search, dateFrom, dateTo, status, syncStatus, cashierId, minTotalSales, maxTotalSales, minSalesCount, maxSalesCount, differenceSign, posMachineId, page, size));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getSessionHistory:error: " + e.getMessage(), e);
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

	/**
	 * 3.1.0: the session ticket, X for an open session, Z for a closed or verified one, read-only (admin and responsible
	 * pages; docs/modules/cash-sessions.md). 404 when the session does not exist.
	 */
	@GetMapping("/{id}/report-ticket")
	public ResponseEntity<?> getReportTicket(@PathVariable Long id) {
		try {
			log.info("CashierSessionAPI::getReportTicket: " + id);
			return ResponseEntity.ok(sessionReportService.build(id));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(404).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getReportTicket:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * 3.1.0: the store ticket X, today: the sessions closed today and those open now, added up, read-only
	 * (docs/modules/cash-sessions.md, "Store ticket X / Z"). 404 when there is none.
	 */
	@GetMapping("/store-report-ticket/x")
	public ResponseEntity<?> getStoreReportTicketX(@RequestParam(required = false) Long posMachineId) {
		try {
			log.info("CashierSessionAPI::getStoreReportTicketX" + (posMachineId != null ? ": POS machine " + posMachineId : ""));
			// 3.2.0, POS machines step 3: posMachineId, the ticket of that POS machine's sessions (404 without one)
			return ResponseEntity.ok(storeReportService.buildX(posMachineId));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(404).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getStoreReportTicketX:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * 3.1.0: the store ticket Z of a date (YYYY-MM-DD): the sessions closed that day, added up, read-only. 400 for a
	 * date that does not parse, 404 when no session was closed that day.
	 */
	@GetMapping("/store-report-ticket/z")
	public ResponseEntity<?> getStoreReportTicketZ(@RequestParam String date,
			@RequestParam(required = false) Long posMachineId) {
		java.time.LocalDate day;
		try {
			day = java.time.LocalDate.parse(date);
		} catch (java.time.format.DateTimeParseException e) {
			return ResponseEntity.badRequest().body(createErrorResponse("date: '" + date + "' is not a date YYYY-MM-DD"));
		}
		try {
			log.info("CashierSessionAPI::getStoreReportTicketZ: " + day
					+ (posMachineId != null ? ", POS machine " + posMachineId : ""));
			return ResponseEntity.ok(storeReportService.buildZ(day, posMachineId));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(404).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getStoreReportTicketZ:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * 3.1.0: the days of a month (YYYY-MM), newest first, read-only (docs/modules/cash-sessions.md, "Journal"). 400 for
	 * a month that does not parse; a month without anything gives an empty list.
	 */
	@GetMapping("/store-days")
	public ResponseEntity<?> getStoreDays(@RequestParam String month) {
		java.time.YearMonth yearMonth = parseMonth(month);
		if (yearMonth == null) {
			return ResponseEntity.badRequest().body(createErrorResponse("month: '" + month + "' is not a month YYYY-MM"));
		}
		try {
			return ResponseEntity.ok(storeDaysService.buildMonth(yearMonth, withMargins()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getStoreDays:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** 3.1.0: the days of a month as .xlsx (jours-YYYY-MM.xlsx). */
	@GetMapping("/store-days/excel")
	public ResponseEntity<?> getStoreDaysExcel(@RequestParam String month) {
		java.time.YearMonth yearMonth = parseMonth(month);
		if (yearMonth == null) {
			return ResponseEntity.badRequest().body(createErrorResponse("month: '" + month + "' is not a month YYYY-MM"));
		}
		try {
			boolean margins = withMargins();
			byte[] file = com.digithink.zsretail.store.service.JournalExcel.days(yearMonth,
					storeDaysService.buildMonth(yearMonth, margins), margins ? storeDaysService.monthGains(yearMonth) : null);
			return excel(file, com.digithink.zsretail.store.service.JournalExcel.daysFileName(yearMonth));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getStoreDaysExcel:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * 3.1.0: the journal of a date (YYYY-MM-DD): every finished ticket, return and cancelled ticket of that date, read-only.
	 * 400 for a date that does not parse, 404 when the date has no line.
	 */
	@GetMapping("/day-journal")
	public ResponseEntity<?> getDayJournal(@RequestParam String date) {
		java.time.LocalDate day = parseDate(date);
		if (day == null) {
			return ResponseEntity.badRequest().body(createErrorResponse("date: '" + date + "' is not a date YYYY-MM-DD"));
		}
		try {
			return ResponseEntity.ok(dayJournalService.build(day, withMargins()));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(404).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getDayJournal:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** 3.1.0: the journal of a date as .xlsx (journal-YYYY-MM-DD.xlsx); 404 when the date has no line. */
	@GetMapping("/day-journal/excel")
	public ResponseEntity<?> getDayJournalExcel(@RequestParam String date) {
		java.time.LocalDate day = parseDate(date);
		if (day == null) {
			return ResponseEntity.badRequest().body(createErrorResponse("date: '" + date + "' is not a date YYYY-MM-DD"));
		}
		try {
			boolean margins = withMargins();
			com.digithink.zsretail.store.dto.DayJournalDTO journal = dayJournalService.build(day, margins);
			// 3.2.0, POS machines step 3: with the drawer of the POS machine, a second sheet "Postes de caisse", the
			// page's per-machine subtotals (the store ticket the page shows: the X today, the Z another day)
			return excel(com.digithink.zsretail.store.service.JournalExcel.journal(journal, margins,
					storeReportService.dayTicketByMachine(day)),
					com.digithink.zsretail.store.service.JournalExcel.journalFileName(journal));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.status(404).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("CashierSessionAPI::getDayJournalExcel:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * 3.1.2: the Journal (days, day journal, their .xlsx) carries the gains only where the store keeps its own stock and
	 * for a role with read:admin-margins (SalesHeaderAPI.MARGINS_PERMISSION); never otherwise, whatever the page shows.
	 */
	public boolean withMargins() {
		return applicationModeService != null && applicationModeService.keepsOwnStock() && currentUserProvider != null
				&& currentUserProvider.currentUserHasPermission(SalesHeaderAPI.MARGINS_PERMISSION);
	}

	private static java.time.LocalDate parseDate(String value) {
		try {
			return java.time.LocalDate.parse(value);
		} catch (java.time.format.DateTimeParseException e) {
			return null;
		}
	}

	private static java.time.YearMonth parseMonth(String value) {
		try {
			return java.time.YearMonth.parse(value);
		} catch (java.time.format.DateTimeParseException e) {
			return null;
		}
	}

	/** An .xlsx download: the file, its type and its name. */
	private static ResponseEntity<byte[]> excel(byte[] file, String fileName) {
		return ResponseEntity.ok()
				.header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
				.contentType(org.springframework.http.MediaType.parseMediaType(
						com.digithink.zsretail.store.service.JournalExcel.CONTENT_TYPE))
				.body(file);
	}

	/**
	 * 2.2.1: a cash count stays a whole number; a quantity sent with decimals (1.5) is refused naming the field (400 on
	 * close), never read as 1 as until 2.2.0.
	 */
	static Integer cashCountQuantity(Number value) {
		java.math.BigDecimal quantity = new java.math.BigDecimal(value.toString());
		if (!Quantities.isWhole(quantity)) {
			throw new IllegalStateException("cashCountLines.quantity: " + Quantities.plain(quantity)
					+ " is not a whole number, and decimal quantities are not supported here yet.");
		}
		return quantity.stripTrailingZeros().intValueExact();
	}
}
