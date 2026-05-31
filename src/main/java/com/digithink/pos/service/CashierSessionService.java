package com.digithink.pos.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.pos.dto.CloseSessionRequestDTO;
import com.digithink.pos.dto.SessionCloseTicketDTO;
import com.digithink.pos.dto.SessionDashboardDTO;
import com.digithink.pos.model.CashierSession;
import com.digithink.pos.model.Payment;
import com.digithink.pos.model.PaymentMethod;
import com.digithink.pos.model.ReturnHeader;
import com.digithink.pos.model.SalesHeader;
import com.digithink.pos.model.SessionCashCount;
import com.digithink.pos.model.UserAccount;
import com.digithink.pos.model.enumeration.CounterType;
import com.digithink.pos.model.enumeration.PaymentMethodType;
import com.digithink.pos.model.enumeration.ReturnType;
import com.digithink.pos.model.enumeration.SessionStatus;
import com.digithink.pos.model.enumeration.SynchronizationStatus;
import com.digithink.pos.model.enumeration.TransactionStatus;
import com.digithink.pos.model.enumeration.BadgePermission;
import com.digithink.pos.model.GeneralSetup;
import com.digithink.pos.exception.CashDiscrepancyException;
import com.digithink.pos.repository.CashierSessionRepository;
import com.digithink.pos.repository.GeneralSetupRepository;
import com.digithink.pos.repository.PaymentMethodRepository;
import com.digithink.pos.repository.PaymentRepository;
import com.digithink.pos.repository.ReturnHeaderRepository;
import com.digithink.pos.repository.SalesHeaderRepository;
import com.digithink.pos.repository.SessionCashCountRepository;
import com.digithink.pos.repository._BaseRepository;
import com.digithink.pos.erp.repository.PaymentHeaderRepository;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.Comparator;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class CashierSessionService extends _BaseService<CashierSession, Long> {

	@Autowired
	private CashierSessionRepository cashierSessionRepository;

	@Autowired
	private SessionCashCountService sessionCashCountService;

	@Autowired
	private PaymentMethodRepository paymentMethodRepository;

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private PaymentRepository paymentRepository;

	@Autowired
	private SessionCashCountRepository sessionCashCountRepository;

	@Autowired
	private ReturnHeaderRepository returnHeaderRepository;

	@Autowired
	private PaymentHeaderRepository paymentHeaderRepository;

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	@Autowired
	private BadgeService badgeService;

	@Override
	protected _BaseRepository<CashierSession, Long> getRepository() {
		return cashierSessionRepository;
	}

	/**
	 * Get the current open session for a cashier
	 */
	public Optional<CashierSession> getCurrentOpenSession(UserAccount cashier) {
		return cashierSessionRepository.findByCashierAndStatus(cashier, SessionStatus.OPENED);
	}

	/**
	 * Create a new cashier session with opening cash
	 * 
	 * @throws Exception
	 */
	public CashierSession openSession(UserAccount cashier, Double openingCash) throws Exception {
		// Check if cashier already has an open session
		Optional<CashierSession> existingSession = getCurrentOpenSession(cashier);
		if (existingSession.isPresent()) {
			throw new IllegalStateException("Cashier already has an open session");
		}

		CashierSession session = new CashierSession();
		session.setCashier(cashier);
		session.setOpeningCash(openingCash);
		session.setOpenedAt(LocalDateTime.now());
		session.setStatus(SessionStatus.OPENED);
		// Generate session number - count by day (e.g., SESSION251102001)
		LocalDateTime todayStart = LocalDateTime.now().withHour(0).withMinute(0).withSecond(0).withNano(0);
		long count = cashierSessionRepository.countByOpenedAtGreaterThanEqual(todayStart);
		String dateStr = LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyMMdd"));
		String sessionNumber = "SESSION" + dateStr + String.format("%03d", count + 1);
		session.setSessionNumber(sessionNumber);

		return save(session);
	}

	/**
	 * Close a cashier session (legacy method for backward compatibility)
	 * 
	 * @throws Exception
	 */
	public CashierSession closeSession(Long sessionId, Double actualCash, String notes) throws Exception {
		CloseSessionRequestDTO request = new CloseSessionRequestDTO();
		request.setActualCash(actualCash);
		request.setNotes(notes);
		request.setCashCountLines(new ArrayList<>());
		return closeSessionWithCashCount(sessionId, request);
	}

	/**
	 * Get expected cash (real cash) for a session by ID This is a public method
	 * that exposes the calculated expected cash amount.
	 * 
	 * @param sessionId The session ID
	 * @return The calculated expected cash amount
	 */
	public Double getExpectedCash(Long sessionId) {
		CashierSession session = findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Session not found"));
		return calculateRealCash(session);
	}

	/**
	 * Calculate real cash (expected cash) for a session This method calculates the
	 * expected cash amount based on transactions. Can be used for both OPENED and
	 * CLOSED sessions.
	 * 
	 * Formula: realCash = openingCash + cash sales - change given - simple returns
	 * (cash refunds)
	 * 
	 * Note: - real_cash = realCash (calculated expected amount based on
	 * transactions) - actual_cash = posUserClosureCash (the actual cash counted by
	 * the POS user when closing) - Excludes PENDING and CANCELLED sales from
	 * calculations
	 */
	private Double calculateRealCash(CashierSession session) {
		Double realCash = session.getOpeningCash() != null ? session.getOpeningCash() : 0.0;

		// Get all sales for this session, excluding PENDING and CANCELLED
		List<SalesHeader> sales = salesHeaderRepository.findByCashierSession(session).stream()
				.filter(sale -> sale.getStatus() != TransactionStatus.PENDING
						&& sale.getStatus() != TransactionStatus.CANCELLED)
				.collect(Collectors.toList());

		for (SalesHeader sale : sales) {
			// Get all payments for this sale
			List<Payment> payments = paymentRepository.findBySalesHeader(sale);

			boolean hasCashPayment = false;
			for (Payment payment : payments) {
				if (payment.getPaymentMethod() != null
						&& payment.getPaymentMethod().getType() == PaymentMethodType.CLIENT_ESPECES) {
					hasCashPayment = true;
					if (payment.getTotalAmount() != null) {
						realCash += payment.getTotalAmount();
					}
				}
			}

			// Only subtract change for sales that actually had a cash component
			if (hasCashPayment && sale.getChangeAmount() != null && sale.getChangeAmount() > 0) {
				realCash -= sale.getChangeAmount();
			}
		}

		// Subtract simple returns (cash refunds) - these reduce the cash in the till
		List<ReturnHeader> returns = returnHeaderRepository.findByCashierSession(session);
		for (ReturnHeader returnHeader : returns) {
			if (returnHeader.getReturnType() == ReturnType.SIMPLE_RETURN
					&& returnHeader.getTotalReturnAmount() != null) {
				realCash -= returnHeader.getTotalReturnAmount();
			}
		}

		return realCash;
	}

	/**
	 * Close a cashier session with detailed cash count lines
	 * 
	 * @throws Exception
	 */
	@Transactional(rollbackFor = Exception.class)
	public CashierSession closeSessionWithCashCount(Long sessionId, CloseSessionRequestDTO request) throws Exception {
		CashierSession session = findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Session not found"));

		if (session.getStatus() != SessionStatus.OPENED) {
			throw new IllegalStateException("Session is not open");
		}

		// Check for pending tickets - session cannot be closed if there are pending
		// tickets
		long pendingCount = salesHeaderRepository
				.findByCashierSessionAndStatus(session, com.digithink.pos.model.enumeration.TransactionStatus.PENDING)
				.size();

		if (pendingCount > 0) {
			throw new IllegalStateException("Cannot close session: There are " + pendingCount
					+ " pending ticket(s). Please complete or cancel all pending tickets before closing the session.");
		}

		// Calculate real cash (expected cash based on sales)
		Double realCash = calculateRealCash(session);
		session.setRealCash(realCash);
		log.info("Real cash calculated: " + realCash + " for session: " + session.getSessionNumber());

		// Calculate POS user closure cash from cash count lines if provided
		Double calculatedPosUserCash = null;
		if (request.getCashCountLines() != null && !request.getCashCountLines().isEmpty()) {
			calculatedPosUserCash = request.getCashCountLines().stream()
					.mapToDouble(line -> (line.getDenominationValue() != null ? line.getDenominationValue() : 0.0)
							* (line.getQuantity() != null ? line.getQuantity() : 0))
					.sum();
		}

		// Use provided actualCash or calculated from lines for POS user closure cash
		Double posUserClosureCash = request.getActualCash() != null ? request.getActualCash() : calculatedPosUserCash;

		// Check if cash discrepancy check is enabled
		Optional<GeneralSetup> discrepancyCheckSetup = generalSetupRepository
				.findByCode("ENABLE_CASH_DISCREPANCY_CHECK");
		boolean discrepancyCheckEnabled = true; // Default to enabled
		if (discrepancyCheckSetup.isPresent()) {
			String valeur = discrepancyCheckSetup.get().getValeur();
			discrepancyCheckEnabled = valeur != null && valeur.equalsIgnoreCase("true");
		}

		// If discrepancy check is enabled, validate amounts
		if (discrepancyCheckEnabled && posUserClosureCash != null && realCash != null) {
			// Compare amounts with tolerance of 0.01 TND for floating point precision
			double difference = Math.abs(realCash - posUserClosureCash);
			if (difference > 0.01) {
				// Discrepancy exists - check if badge was provided
				if (request.getBadgeCode() != null && !request.getBadgeCode().trim().isEmpty()
						&& request.getBadgePermission() != null
						&& request.getBadgePermission().equals("CLOSE_SESSION_WITH_DISCREPANCY")) {
					// Badge provided - validate it without logging (already logged by frontend
					// scan)
					// Use findUserByBadgeCode which checks revoked/expired, then check permission
					java.util.Optional<UserAccount> badgeUserOpt = badgeService
							.findUserByBadgeCode(request.getBadgeCode().trim());
					if (!badgeUserOpt.isPresent()) {
						throw new IllegalStateException("Badge validation failed: BADGE_NOT_EXISTS");
					}

					UserAccount badgeUser = badgeUserOpt.get();
					boolean hasPermission = badgeService.hasPermission(badgeUser,
							BadgePermission.CLOSE_SESSION_WITH_DISCREPANCY);
					if (!hasPermission) {
						throw new IllegalStateException("Badge validation failed: BADGE_NO_ACCESS");
					}
					// Badge validated successfully - proceed with closure
					log.info("Session closure with discrepancy authorized by badge: " + request.getBadgeCode());
				} else {
					// Badge NOT provided - throw structured exception for frontend
					throw new CashDiscrepancyException(realCash, posUserClosureCash, difference);
				}
			}
		}

		session.setPosUserClosureCash(posUserClosureCash);

		session.setClosedAt(LocalDateTime.now());
		session.setStatus(SessionStatus.CLOSED);
		// Mark session as not synched so it can be exported to ERP
		session.setSynchronizationStatus(SynchronizationStatus.NOT_SYNCHED);

		session = save(session);
		log.info("Session closed: " + session.getSessionNumber() + ", Real Cash: " + realCash
				+ ", POS User Closure Cash: " + posUserClosureCash);

		// Save cash count lines (counted by POS_USER)
		if (request.getCashCountLines() != null && !request.getCashCountLines().isEmpty()) {
			List<SessionCashCount> cashCounts = new ArrayList<>();
			for (CloseSessionRequestDTO.CashCountLineDTO lineDTO : request.getCashCountLines()) {
				SessionCashCount cashCount = new SessionCashCount();
				cashCount.setCashierSession(session);
				cashCount.setDenominationValue(lineDTO.getDenominationValue());
				cashCount.setQuantity(lineDTO.getQuantity());
				cashCount.setCounterType(CounterType.POS_USER);

				// Calculate line total
				Double lineTotal = (lineDTO.getDenominationValue() != null ? lineDTO.getDenominationValue() : 0.0)
						* (lineDTO.getQuantity() != null ? lineDTO.getQuantity() : 0);
				cashCount.setLineTotal(lineTotal);

				// Set payment method if provided
				if (lineDTO.getPaymentMethodId() != null) {
					PaymentMethod paymentMethod = paymentMethodRepository.findById(lineDTO.getPaymentMethodId())
							.orElse(null);
					cashCount.setPaymentMethod(paymentMethod);
				}

				cashCount.setReferenceNumber(lineDTO.getReferenceNumber());
				cashCount.setNotes(lineDTO.getNotes());
				cashCount.setActive(true);

				cashCount = sessionCashCountService.save(cashCount);
				cashCounts.add(cashCount);
				log.info(
						"Cash count line saved: " + cashCount.getDenominationValue() + " x " + cashCount.getQuantity());
			}
			log.info("Total cash count lines saved: " + cashCounts.size());
		}

		return session;
	}

	/**
	 * Verify session and set responsible closure cash (for responsible user)
	 * 
	 * @throws Exception
	 */
	@Transactional(rollbackFor = Exception.class)
	public CashierSession verifySession(Long sessionId, Double responsibleClosureCash, String verificationNotes,
			UserAccount responsibleUser, List<Map<String, Object>> paymentDetails) throws Exception {
		CashierSession session = findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Session not found"));

		if (session.getStatus() != SessionStatus.CLOSED) {
			throw new IllegalStateException("Session must be closed before verification");
		}

		session.setResponsibleClosureCash(responsibleClosureCash);
		session.setVerifiedBy(responsibleUser);
		session.setVerifiedAt(LocalDateTime.now());
		session.setVerificationNotes(verificationNotes);
		session.setStatus(SessionStatus.TERMINATED);
		if (session.getSynchronizationStatus() == null
				|| session.getSynchronizationStatus() == SynchronizationStatus.NOT_SYNCHED) {
			session.setSynchronizationStatus(SynchronizationStatus.NOT_SYNCHED);
		}
		session = save(session);

		// Save per-method RESPONSIBLE SessionCashCount rows
		log.info("[verifySession] paymentDetails received: {}",
				paymentDetails != null ? paymentDetails.size() + " entries" : "NULL");
		if (paymentDetails != null && !paymentDetails.isEmpty()) {
			// Remove any existing RESPONSIBLE rows for this session (idempotent)
			List<SessionCashCount> existing = sessionCashCountRepository.findByCashierSession(session);
			long deletedCount = existing.stream().filter(s -> s.getCounterType() == CounterType.RESPONSIBLE)
					.peek(sessionCashCountRepository::delete).count();
			log.info("[verifySession] deleted {} existing RESPONSIBLE rows for session {}", deletedCount,
					session.getSessionNumber());

			// Find ESPECE code to distinguish cash from non-cash
			String especeCode = paymentMethodRepository.findByType(PaymentMethodType.CLIENT_ESPECES)
					.map(PaymentMethod::getCode).orElse(null);
			log.info("[verifySession] especeCode resolved: {}", especeCode);

			int savedCount = 0;
			for (Map<String, Object> detail : paymentDetails) {
				String code = (String) detail.get("code");
				Double amount = detail.get("amount") instanceof Number ? ((Number) detail.get("amount")).doubleValue()
						: null;
				log.info("[verifySession] processing detail: code={}, amount={}, amountType={}", code, amount,
						detail.get("amount") != null ? detail.get("amount").getClass().getSimpleName() : "null");
				if (code == null || amount == null || amount <= 0) {
					log.warn("[verifySession] skipping detail: code={}, amount={}", code, amount);
					continue;
				}

				SessionCashCount scc = new SessionCashCount();
				scc.setCashierSession(session);
				scc.setDenominationValue(amount);
				scc.setQuantity(1);
				scc.setLineTotal(amount);
				scc.setCounterType(CounterType.RESPONSIBLE);
				// Cash method: null paymentMethod; others: look up by code
				if (!code.equals(especeCode)) {
					boolean found = paymentMethodRepository.findByCode(code).map(pm -> {
						scc.setPaymentMethod(pm);
						return true;
					}).orElse(false);
					log.info("[verifySession] non-cash code={}, paymentMethod found={}", code, found);
				} else {
					log.info("[verifySession] cash code={}, paymentMethod stays null", code);
				}
				SessionCashCount saved = sessionCashCountRepository.save(scc);
				log.info("[verifySession] saved SCC id={}, counterType={}, lineTotal={}, paymentMethod={}",
						saved.getId(), saved.getCounterType(), saved.getLineTotal(),
						saved.getPaymentMethod() != null ? saved.getPaymentMethod().getCode() : "null");
				savedCount++;
			}
			log.info("[verifySession] total RESPONSIBLE rows saved: {}", savedCount);
		} else {
			log.warn("[verifySession] paymentDetails is null or empty — no RESPONSIBLE SCC rows will be saved");
		}

		log.info("[verifySession] session verified: {}, responsibleClosureCash={}", session.getSessionNumber(),
				responsibleClosureCash);
		return session;
	}

	/**
	 * Get session dashboard - list all sessions with statistics
	 */
	public List<SessionDashboardDTO> getSessionDashboard() {
		List<CashierSession> sessions = cashierSessionRepository.findAll();

		return sessions.stream().map(session -> {
			// Get sales count and total amount for this session (gross sales - no returns
			// subtracted)
			// Exclude PENDING and CANCELLED sales
			List<SalesHeader> allSales = salesHeaderRepository.findByCashierSession(session);
			List<SalesHeader> sales = allSales.stream().filter(sale -> sale.getStatus() != TransactionStatus.PENDING
					&& sale.getStatus() != TransactionStatus.CANCELLED).collect(Collectors.toList());
			Long salesCount = (long) sales.size();
			Double totalSalesAmount = sales.stream()
					.mapToDouble(sale -> sale.getTotalAmount() != null ? sale.getTotalAmount() : 0.0).sum();

			// Get returns for this session
			List<ReturnHeader> returns = returnHeaderRepository.findByCashierSession(session);
			Long returnsCount = (long) returns.size();

			// Calculate total return amounts by type
			Double totalReturnsAmount = returns.stream()
					.mapToDouble(ret -> ret.getTotalReturnAmount() != null ? ret.getTotalReturnAmount() : 0.0).sum();

			Double simpleReturnsAmount = returns.stream().filter(ret -> ret.getReturnType() == ReturnType.SIMPLE_RETURN)
					.mapToDouble(ret -> ret.getTotalReturnAmount() != null ? ret.getTotalReturnAmount() : 0.0).sum();

			Double voucherReturnsAmount = returns.stream()
					.filter(ret -> ret.getReturnType() == ReturnType.RETURN_VOUCHER)
					.mapToDouble(ret -> ret.getTotalReturnAmount() != null ? ret.getTotalReturnAmount() : 0.0).sum();

			// Calculate real cash for this session (even if open)
			// Real cash = openingCash + cash sales - change given - simple returns
			Double calculatedRealCash = calculateRealCash(session);

			// For closed sessions, use stored realCash if available, otherwise calculate
			// For open sessions, always calculate
			Double realCash = session.getRealCash() != null && session.getStatus() == SessionStatus.CLOSED
					? session.getRealCash()
					: calculatedRealCash;

			return SessionDashboardDTO.fromEntity(session, salesCount, totalSalesAmount, returnsCount,
					totalReturnsAmount, simpleReturnsAmount, voucherReturnsAmount, realCash);
		}).collect(Collectors.toList());
	}

	/**
	 * Get paginated session history with filters (server-side pagination)
	 */
	public Map<String, Object> getSessionHistory(String searchStr, String dateFromStr, String dateToStr,
			String statusStr, String syncStatusStr, String cashierIdStr, String minTotalSalesStr,
			String maxTotalSalesStr, String minSalesCountStr, String maxSalesCountStr, String differenceSignStr,
			int page, int size) {

		LocalDateTime from = null;
		if (dateFromStr != null && !dateFromStr.trim().isEmpty()) {
			try {
				from = java.time.LocalDate.parse(dateFromStr).atStartOfDay();
			} catch (Exception e) {
				/* ignore */ }
		}
		LocalDateTime to = null;
		if (dateToStr != null && !dateToStr.trim().isEmpty()) {
			try {
				to = java.time.LocalDate.parse(dateToStr).atTime(23, 59, 59);
			} catch (Exception e) {
				/* ignore */ }
		}

		final LocalDateTime finalFrom = from;
		final LocalDateTime finalTo = to;
		final String finalSearch = (searchStr != null && !searchStr.trim().isEmpty()) ? searchStr.trim() : null;
		final SessionStatus finalStatus = (statusStr != null && !statusStr.equals("all"))
				? SessionStatus.valueOf(statusStr)
				: null;
		final SynchronizationStatus finalSyncStatus = (syncStatusStr != null && !syncStatusStr.equals("all"))
				? SynchronizationStatus.valueOf(syncStatusStr)
				: null;

		Long finalCashierId = null;
		if (cashierIdStr != null && !cashierIdStr.trim().isEmpty()) {
			try {
				finalCashierId = Long.parseLong(cashierIdStr.trim());
			} catch (Exception e) {
				/* ignore */ }
		}
		final Long fCashierId = finalCashierId;

		Double finalMinSales = null;
		if (minTotalSalesStr != null && !minTotalSalesStr.trim().isEmpty()) {
			try {
				finalMinSales = Double.parseDouble(minTotalSalesStr.trim());
			} catch (Exception e) {
				/* ignore */ }
		}
		Double finalMaxSales = null;
		if (maxTotalSalesStr != null && !maxTotalSalesStr.trim().isEmpty()) {
			try {
				finalMaxSales = Double.parseDouble(maxTotalSalesStr.trim());
			} catch (Exception e) {
				/* ignore */ }
		}
		final Double fMinSales = finalMinSales;
		final Double fMaxSales = finalMaxSales;

		Long finalMinSalesCount = null;
		if (minSalesCountStr != null && !minSalesCountStr.trim().isEmpty()) {
			try {
				finalMinSalesCount = Long.parseLong(minSalesCountStr.trim());
			} catch (Exception e) {
				/* ignore */ }
		}
		Long finalMaxSalesCount = null;
		if (maxSalesCountStr != null && !maxSalesCountStr.trim().isEmpty()) {
			try {
				finalMaxSalesCount = Long.parseLong(maxSalesCountStr.trim());
			} catch (Exception e) {
				/* ignore */ }
		}
		final Long fMinSalesCount = finalMinSalesCount;
		final Long fMaxSalesCount = finalMaxSalesCount;
		final String fDiffSign = (differenceSignStr != null && !differenceSignStr.equals("all")) ? differenceSignStr
				: null;

		org.springframework.data.jpa.domain.Specification<CashierSession> spec = (root, query, cb) -> {
			List<javax.persistence.criteria.Predicate> predicates = new ArrayList<>();
			if (finalFrom != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.get("openedAt"), finalFrom));
			}
			if (finalTo != null) {
				predicates.add(cb.lessThanOrEqualTo(root.get("openedAt"), finalTo));
			}
			if (finalStatus != null) {
				predicates.add(cb.equal(root.get("status"), finalStatus));
			}
			if (finalSyncStatus != null) {
				predicates.add(cb.equal(root.get("synchronizationStatus"), finalSyncStatus));
			}
			if (finalSearch != null) {
				String pattern = "%" + finalSearch.toLowerCase() + "%";
				javax.persistence.criteria.Join<CashierSession, UserAccount> cashierJoin = root.join("cashier",
						javax.persistence.criteria.JoinType.LEFT);
				query.distinct(true);
				predicates.add(cb.or(cb.like(cb.lower(root.get("sessionNumber")), pattern),
						cb.like(cb.lower(cashierJoin.get("username")), pattern),
						cb.like(cb.lower(cashierJoin.get("fullName")), pattern)));
			}
			if (fCashierId != null) {
				predicates.add(cb.equal(root.get("cashier").get("id"), fCashierId));
			}
			if (fMinSales != null || fMaxSales != null) {
				javax.persistence.criteria.Subquery<Double> salesSub = query.subquery(Double.class);
				javax.persistence.criteria.Root<SalesHeader> shRoot = salesSub.from(SalesHeader.class);
				javax.persistence.criteria.Expression<Double> sumExpr = cb.sum(shRoot.<Double>get("totalAmount"));
				salesSub.select(cb.coalesce(sumExpr, 0.0));
				salesSub.where(cb.equal(shRoot.get("cashierSession"), root), cb.not(shRoot.get("status")
						.in(java.util.Arrays.asList(TransactionStatus.PENDING, TransactionStatus.CANCELLED))));
				if (fMinSales != null)
					predicates.add(cb.ge(salesSub, fMinSales));
				if (fMaxSales != null)
					predicates.add(cb.le(salesSub, fMaxSales));
			}
			if (fMinSalesCount != null || fMaxSalesCount != null) {
				javax.persistence.criteria.Subquery<Long> countSub = query.subquery(Long.class);
				javax.persistence.criteria.Root<SalesHeader> shCount = countSub.from(SalesHeader.class);
				countSub.select(cb.count(shCount));
				countSub.where(cb.equal(shCount.get("cashierSession"), root), cb.not(shCount.get("status")
						.in(java.util.Arrays.asList(TransactionStatus.PENDING, TransactionStatus.CANCELLED))));
				if (fMinSalesCount != null)
					predicates.add(cb.ge(countSub, fMinSalesCount));
				if (fMaxSalesCount != null)
					predicates.add(cb.le(countSub, fMaxSalesCount));
			}
			if (fDiffSign != null) {
				// Filter only sessions that have at least one closure value
				predicates.add(cb.or(cb.isNotNull(root.get("responsibleClosureCash")),
						cb.isNotNull(root.get("posUserClosureCash"))));
				// totalSales subquery
				javax.persistence.criteria.Subquery<Double> diffSalesSub = query.subquery(Double.class);
				javax.persistence.criteria.Root<SalesHeader> diffSh = diffSalesSub.from(SalesHeader.class);
				diffSalesSub.select(cb.coalesce(cb.sum(diffSh.<Double>get("totalAmount")), 0.0));
				diffSalesSub.where(cb.equal(diffSh.get("cashierSession"), root), cb.not(diffSh.get("status")
						.in(Arrays.asList(TransactionStatus.PENDING, TransactionStatus.CANCELLED))));
				// simpleReturns subquery
				javax.persistence.criteria.Subquery<Double> diffRetSub = query.subquery(Double.class);
				javax.persistence.criteria.Root<ReturnHeader> diffRh = diffRetSub.from(ReturnHeader.class);
				diffRetSub.select(cb.coalesce(cb.sum(diffRh.<Double>get("totalReturnAmount")), 0.0));
				diffRetSub.where(cb.equal(diffRh.get("cashierSession"), root),
						cb.equal(diffRh.get("returnType"), ReturnType.SIMPLE_RETURN));
				// totalSystemAmount = openingCash + totalSales - simpleReturns
				javax.persistence.criteria.Expression<Double> openingExpr = cb.coalesce(root.<Double>get("openingCash"),
						0.0);
				javax.persistence.criteria.Expression<Double> totalSystemExpr = cb
						.diff(cb.sum(openingExpr, diffSalesSub), diffRetSub);
				// Use responsibleClosureCash when available (TERMINATED), fall back to
				// posUserClosureCash
				javax.persistence.criteria.Expression<Double> effectiveClosure = cb
						.coalesce(root.<Double>get("responsibleClosureCash"), root.<Double>get("posUserClosureCash"));
				javax.persistence.criteria.Expression<Double> diff = cb.diff(effectiveClosure, totalSystemExpr);
				if ("POSITIVE".equals(fDiffSign))
					predicates.add(cb.gt(diff, 0.0));
				else if ("NEGATIVE".equals(fDiffSign))
					predicates.add(cb.lt(diff, 0.0));
				else if ("ZERO".equals(fDiffSign))
					predicates.add(cb.between(diff, -0.005, 0.005));
			}
			return cb.and(predicates.toArray(new javax.persistence.criteria.Predicate[0]));
		};

		org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(page, size,
				org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC,
						"openedAt"));

		org.springframework.data.domain.Page<CashierSession> sessionPage = cashierSessionRepository.findAll(spec,
				pageable);

		List<SessionDashboardDTO> dtos = sessionPage.getContent().stream().map(session -> {
			List<SalesHeader> allSales = salesHeaderRepository.findByCashierSession(session);
			List<SalesHeader> sales = allSales.stream().filter(
					s -> s.getStatus() != TransactionStatus.PENDING && s.getStatus() != TransactionStatus.CANCELLED)
					.collect(Collectors.toList());
			Long salesCount = (long) sales.size();
			Double totalSalesAmount = sales.stream()
					.mapToDouble(s -> s.getTotalAmount() != null ? s.getTotalAmount() : 0.0).sum();
			List<ReturnHeader> returns = returnHeaderRepository.findByCashierSession(session);
			Long returnsCount = (long) returns.size();
			Double totalReturnsAmount = returns.stream()
					.mapToDouble(r -> r.getTotalReturnAmount() != null ? r.getTotalReturnAmount() : 0.0).sum();
			Double simpleReturnsAmount = returns.stream().filter(r -> r.getReturnType() == ReturnType.SIMPLE_RETURN)
					.mapToDouble(r -> r.getTotalReturnAmount() != null ? r.getTotalReturnAmount() : 0.0).sum();
			Double voucherReturnsAmount = returns.stream().filter(r -> r.getReturnType() == ReturnType.RETURN_VOUCHER)
					.mapToDouble(r -> r.getTotalReturnAmount() != null ? r.getTotalReturnAmount() : 0.0).sum();
			Double calculatedRealCash = calculateRealCash(session);
			Double realCash = session.getRealCash() != null && session.getStatus() == SessionStatus.CLOSED
					? session.getRealCash()
					: calculatedRealCash;
			return SessionDashboardDTO.fromEntity(session, salesCount, totalSalesAmount, returnsCount,
					totalReturnsAmount, simpleReturnsAmount, voucherReturnsAmount, realCash);
		}).collect(Collectors.toList());

		Map<String, Object> result = new HashMap<>();
		result.put("content", dtos);
		result.put("totalElements", sessionPage.getTotalElements());
		result.put("totalPages", sessionPage.getTotalPages());
		result.put("number", sessionPage.getNumber());
		result.put("size", sessionPage.getSize());
		return result;
	}

	/**
	 * Get session details including cash count lines
	 */
	public Map<String, Object> getSessionDetails(Long sessionId) {
		CashierSession session = findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Session not found"));

		// Get sales for this session (gross sales - no returns subtracted)
		// Exclude PENDING and CANCELLED sales
		List<SalesHeader> allSales = salesHeaderRepository.findByCashierSession(session);
		List<SalesHeader> sales = allSales.stream().filter(sale -> sale.getStatus() != TransactionStatus.PENDING
				&& sale.getStatus() != TransactionStatus.CANCELLED).collect(Collectors.toList());
		Long salesCount = (long) sales.size();
		Double totalSalesAmount = sales.stream()
				.mapToDouble(sale -> sale.getTotalAmount() != null ? sale.getTotalAmount() : 0.0).sum();

		// Get returns for this session
		List<ReturnHeader> returns = returnHeaderRepository.findByCashierSession(session);
		Long returnsCount = (long) returns.size();

		// Calculate total return amounts by type
		Double totalReturnsAmount = returns.stream()
				.mapToDouble(ret -> ret.getTotalReturnAmount() != null ? ret.getTotalReturnAmount() : 0.0).sum();

		Double simpleReturnsAmount = returns.stream().filter(ret -> ret.getReturnType() == ReturnType.SIMPLE_RETURN)
				.mapToDouble(ret -> ret.getTotalReturnAmount() != null ? ret.getTotalReturnAmount() : 0.0).sum();

		Double voucherReturnsAmount = returns.stream().filter(ret -> ret.getReturnType() == ReturnType.RETURN_VOUCHER)
				.mapToDouble(ret -> ret.getTotalReturnAmount() != null ? ret.getTotalReturnAmount() : 0.0).sum();

		// Calculate real cash for this session (even if open)
		Double calculatedRealCash = calculateRealCash(session);
		Double realCash = session.getRealCash() != null && session.getStatus() == SessionStatus.CLOSED
				? session.getRealCash()
				: calculatedRealCash;

		// Get cash count lines (used for payment summary aggregation only)
		List<SessionCashCount> cashCountLines = sessionCashCountRepository.findByCashierSession(session);
		long respRows = cashCountLines.stream().filter(s -> s.getCounterType() == CounterType.RESPONSIBLE).count();
		long posRows = cashCountLines.stream().filter(s -> s.getCounterType() == CounterType.POS_USER).count();
		log.info("[getSessionDetails] session={}, cashCountLines total={}, POS_USER={}, RESPONSIBLE={}", sessionId,
				cashCountLines.size(), posRows, respRows);

		// Get payment headers for this session (needed for sync status per payment
		// class)
		List<com.digithink.pos.erp.model.PaymentHeader> paymentHeaders = paymentHeaderRepository
				.findByCashierSession(session);

		// Build payment summary — batch-load all payments (replaces N+1 paymentLines
		// loop)
		List<TransactionStatus> excluded = Arrays.asList(TransactionStatus.PENDING, TransactionStatus.CANCELLED);
		List<Payment> allPayments = paymentRepository.findByCashierSession(session, excluded);

		com.digithink.pos.model.PaymentMethod especeMethod = paymentMethodRepository
				.findByType(PaymentMethodType.CLIENT_ESPECES).orElse(null);
		String especeCode = especeMethod != null ? especeMethod.getCode() : null;

		// Aggregate payments: code → name / distinct ticket IDs / raw amount sum
		Map<String, String> codeToName = new LinkedHashMap<>();
		Map<String, Set<Long>> codeToTicketIds = new LinkedHashMap<>();
		Map<String, Double> codeToRawAmount = new LinkedHashMap<>();
		for (Payment p : allPayments) {
			if (p.getPaymentMethod() == null)
				continue;
			String code = p.getPaymentMethod().getCode();
			codeToName.put(code, p.getPaymentMethod().getName());
			codeToTicketIds.computeIfAbsent(code, k -> new HashSet<>()).add(p.getSalesHeader().getId());
			codeToRawAmount.merge(code, p.getTotalAmount() != null ? p.getTotalAmount() : 0.0, Double::sum);
		}

		// Aggregate SessionCashCount by method code and counter type
		// POS_USER: cashier's per-method closure
		// RESPONSIBLE: responsible's per-method closure (when entered per method;
		// otherwise only session total exists)
		Map<String, Double> posClosureByCode = new LinkedHashMap<>();
		Map<String, Double> respClosureByCode = new LinkedHashMap<>();
		for (SessionCashCount scc : cashCountLines) {
			String code = scc.getPaymentMethod() != null ? scc.getPaymentMethod().getCode() : especeCode;
			if (code == null)
				continue;
			if (scc.getCounterType() == CounterType.POS_USER) {
				posClosureByCode.merge(code, scc.getLineTotal() != null ? scc.getLineTotal() : 0.0, Double::sum);
			} else if (scc.getCounterType() == CounterType.RESPONSIBLE) {
				respClosureByCode.merge(code, scc.getLineTotal() != null ? scc.getLineTotal() : 0.0, Double::sum);
			}
		}
		// Expose whether per-method responsible data exists (false = only session-level
		// total available)
		boolean hasPerMethodRespClosure = !respClosureByCode.isEmpty();

		// Union of all method codes across all data sources
		Set<String> allCodes = new LinkedHashSet<>();
		allCodes.addAll(codeToName.keySet());
		allCodes.addAll(posClosureByCode.keySet());
		allCodes.addAll(respClosureByCode.keySet());

		List<Map<String, Object>> paymentSummary = new ArrayList<>();
		for (String code : allCodes) {
			Map<String, Object> row = new HashMap<>();
			row.put("code", code);
			row.put("name", codeToName.getOrDefault(code, code));
			row.put("ticketCount", codeToTicketIds.containsKey(code) ? (long) codeToTicketIds.get(code).size() : 0L);

			boolean isEspece = code.equals(especeCode);
			// ESPECE system amount = expected cash in drawer (openingCash + net cash from
			// sales)
			// Other methods = raw sum of payment amounts
			double sysAmount = isEspece ? (realCash != null ? realCash : 0.0) : codeToRawAmount.getOrDefault(code, 0.0);
			row.put("systemAmount", sysAmount);
			row.put("isEspece", isEspece);

			Double posClosure = posClosureByCode.get(code);
			row.put("posClosureAmount", posClosure);
			row.put("deltaPOS", posClosure != null ? posClosure - sysAmount : null);

			Double respClosure = respClosureByCode.get(code);
			row.put("respClosureAmount", respClosure);
			row.put("deltaResp", respClosure != null ? respClosure - sysAmount : null);

			com.digithink.pos.erp.model.PaymentHeader matchedHeader = paymentHeaders.stream()
					.filter(h -> code.equals(h.getPaymentClass())).findFirst().orElse(null);
			row.put("syncStatus", matchedHeader != null ? matchedHeader.getSynchronizationStatus() : null);
			row.put("erpNo", matchedHeader != null ? matchedHeader.getErpNo() : null);

			boolean hasSystem = codeToRawAmount.containsKey(code) || (isEspece && sysAmount > 0);
			boolean hasClosure = posClosureByCode.containsKey(code) || respClosureByCode.containsKey(code);
			row.put("countOnly", !hasSystem && hasClosure);
			row.put("systemOnly", hasSystem && !hasClosure);

			paymentSummary.add(row);
		}

		// Build response
		Map<String, Object> response = new HashMap<>();
		response.put("session", SessionDashboardDTO.fromEntity(session, salesCount, totalSalesAmount, returnsCount,
				totalReturnsAmount, simpleReturnsAmount, voucherReturnsAmount, realCash));
		response.put("salesCount", salesCount);
		response.put("totalSalesAmount", totalSalesAmount); // Gross sales (sum of all sales)
		response.put("returnsCount", returnsCount);
		response.put("totalReturnsAmount", totalReturnsAmount);
		response.put("simpleReturnsAmount", simpleReturnsAmount);
		response.put("voucherReturnsAmount", voucherReturnsAmount);
		response.put("sales", sales);
		response.put("returns", returns);
		response.put("paymentHeaders", paymentHeaders);
		response.put("paymentSummary", paymentSummary);
		response.put("hasPerMethodRespClosure", hasPerMethodRespClosure);

		return response;
	}

	/**
	 * Build session close ticket DTO for printing (declared amounts only). Used
	 * after closing a session to print the close ticket.
	 */
	public SessionCloseTicketDTO getSessionCloseTicketData(Long sessionId) {
		CashierSession session = findById(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Session not found"));
		if (session.getStatus() != SessionStatus.CLOSED) {
			throw new IllegalStateException("Session is not closed");
		}

		// Sales count (completed tickets only)
		List<SalesHeader> sales = salesHeaderRepository.findByCashierSession(session).stream()
				.filter(sale -> sale.getStatus() != TransactionStatus.PENDING
						&& sale.getStatus() != TransactionStatus.CANCELLED)
				.collect(Collectors.toList());
		Long ticketsCount = (long) sales.size();

		// Returns count
		List<ReturnHeader> returns = returnHeaderRepository.findByCashierSession(session);
		Long returnsCount = (long) returns.size();

		// Declared amounts: from POS_USER cash count lines, grouped by payment method
		List<SessionCashCount> cashCountLines = sessionCashCountRepository.findByCashierSessionAndCounterType(session,
				CounterType.POS_USER);

		Double totalDeclared = session.getPosUserClosureCash();
		if (totalDeclared == null && cashCountLines != null && !cashCountLines.isEmpty()) {
			totalDeclared = cashCountLines.stream()
					.mapToDouble(line -> line.getLineTotal() != null ? line.getLineTotal() : 0.0).sum();
		}
		if (totalDeclared == null) {
			totalDeclared = 0.0;
		}

		// Group by payment method — skip null-payment lines (fond de caisse fallback)
		Map<String, Double> methodToAmount = new HashMap<>();
		double especeTotal = 0.0;
		if (cashCountLines != null) {
			for (SessionCashCount line : cashCountLines) {
				if (line.getPaymentMethod() == null) continue;
				String name = line.getPaymentMethod().getName() != null
						? line.getPaymentMethod().getName()
						: "Cash";
				Double amount = line.getLineTotal() != null ? line.getLineTotal() : 0.0;
				methodToAmount.merge(name, amount, Double::sum);
				if (PaymentMethodType.CLIENT_ESPECES.equals(line.getPaymentMethod().getType())) {
					especeTotal += amount;
				}
			}
		}
		double opening = session.getOpeningCash() != null ? session.getOpeningCash() : 0.0;
		double netEspeces = Math.max(0.0, especeTotal - opening);

		List<SessionCloseTicketDTO.PaymentMethodAmount> paymentMethodAmounts = methodToAmount.entrySet().stream()
				.sorted(Comparator.comparing(Map.Entry::getKey))
				.map(e -> new SessionCloseTicketDTO.PaymentMethodAmount(e.getKey(), e.getValue()))
				.collect(Collectors.toList());

		String cashierName = session.getCashier() != null
				? (session.getCashier().getFullName() != null ? session.getCashier().getFullName()
						: session.getCashier().getUsername())
				: "N/A";

		return SessionCloseTicketDTO.builder().sessionNumber(session.getSessionNumber()).cashierName(cashierName)
				.openedAt(session.getOpenedAt()).closedAt(session.getClosedAt()).openingCash(session.getOpeningCash())
				.totalDeclared(totalDeclared).netEspeces(netEspeces).paymentMethodAmounts(paymentMethodAmounts)
				.ticketsCount(ticketsCount).returnsCount(returnsCount).build();
	}
}
