package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.CounterType;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.core.utils.Quantities;
import com.digithink.zsretail.store.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.store.dto.SessionCloseTicketDTO;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;
import com.digithink.zsretail.store.erp.repository.PaymentHeaderRepository;
import com.digithink.zsretail.store.exception.CashDiscrepancyException;
import com.digithink.zsretail.store.model.SessionCashCount;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;
import com.digithink.zsretail.store.repository.ScannedTicketPaymentRepository;
import com.digithink.zsretail.store.repository.SessionCashCountRepository;

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
	private ScannedTicketPaymentRepository scannedTicketPaymentRepository;

	@Autowired
	private ReturnHeaderRepository returnHeaderRepository;

	@Autowired
	private PaymentHeaderRepository paymentHeaderRepository;

	@Autowired
	private GeneralSetupRepository generalSetupRepository;

	/** Step D (3.1.0): the right to close with a cash difference, checked on the close. */
	@Autowired
	private TillRightsGuard tillRightsGuard;

	@Autowired
	private ApplicationModeService applicationModeService;

	/** 3.2.0, POS machines step 2: the POS machine of each session. */
	@Autowired
	private SessionPosMachineService sessionPosMachineService;

	static final String HEAD_OFFICE_REFUSAL = "This installation is a head office: cashier sessions cannot be opened.";

	@Override
	protected _BaseRepository<CashierSession, Long> getRepository() {
		return cashierSessionRepository;
	}

	/**
	 * A new session (openSession, or the generic POST /cashier-session) is refused on a head office.
	 * Existing sessions are saved as before.
	 */
	@Override
	public CashierSession save(CashierSession entity) throws Exception {
		if (entity.getId() == null) {
			refuseOnHeadOffice();
		}
		return super.save(entity);
	}

	/** A head office has no till (docs/modules/head-office.md). */
	private void refuseOnHeadOffice() {
		if (applicationModeService.isHeadOffice()) {
			throw new IllegalStateException(HEAD_OFFICE_REFUSAL);
		}
	}

	/**
	 * Get the current open session for a cashier
	 */
	public Optional<CashierSession> getCurrentOpenSession(UserAccount cashier) {
		return cashierSessionRepository.findByCashierAndStatus(cashier, SessionStatus.OPENED);
	}

	/**
	 * 3.1.1: how many tickets a session holds, the sales completed (refunded since included). A count, never an amount:
	 * the till shows it, and the cashier's closing count stays blind. One COUNT on the session's index.
	 */
	public long countSessionTickets(CashierSession session) {
		return salesHeaderRepository.countByCashierSessionAndStatusIn(session,
				Arrays.asList(TransactionStatus.COMPLETED, TransactionStatus.REFUNDED));
	}

	/**
	 * Create a new cashier session with opening cash. 3.2.0, POS machines step 2: the session keeps the POS machine
	 * registered at the address of the request (remoteAddress, request.getRemoteAddr()); none when that PC is not
	 * registered.
	 *
	 * @throws Exception
	 */
	@Transactional(rollbackFor = Exception.class)
	public CashierSession openSession(UserAccount cashier, Double openingCash, String remoteAddress) throws Exception {
		refuseOnHeadOffice();

		// Check if cashier already has an open session
		Optional<CashierSession> existingSession = getCurrentOpenSession(cashier);
		if (existingSession.isPresent()) {
			throw new IllegalStateException("Cashier already has an open session");
		}
		PosMachine machine = sessionPosMachineService.machineForOpening(remoteAddress);

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

		CashierSession saved = save(session);
		sessionPosMachineService.record(saved, machine);
		return saved;
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

		// 3.1.0 (test log I-07): to the millime, as stored, so the closing check compares the same value (no 0.0002)
		return Quantities.roundAmount(realCash);
	}

	/**
	 * Recompute and persist the expected cash (realCash) for a CLOSED session. For an
	 * OPENED session realCash is always computed on the fly, so nothing is stored; for
	 * a CLOSED session the value was frozen at closure and must be refreshed after an
	 * admin changes a ticket's payment method (cash &lt;-&gt; non-cash). Returns the new
	 * realCash, or the unchanged stored value when the session is not CLOSED.
	 */
	@Transactional(rollbackFor = Exception.class)
	public Double recalculateRealCashForClosedSession(CashierSession session) throws Exception {
		if (session == null || session.getStatus() != SessionStatus.CLOSED) {
			return session != null ? session.getRealCash() : null;
		}
		Double realCash = calculateRealCash(session);
		session.setRealCash(realCash);
		save(session);
		log.info("Recalculated realCash for closed session {}: {}", session.getSessionNumber(), realCash);
		return realCash;
	}

	/**
	 * Close a cashier session with detailed cash count lines
	 * 
	 * @throws Exception
	 */
	@Transactional(rollbackFor = Exception.class)
	public CashierSession closeSessionWithCashCount(Long sessionId, CloseSessionRequestDTO request) throws Exception {
		// Pessimistic lock: serialize against admin payment-method changes that recompute realCash.
		CashierSession session = cashierSessionRepository.findByIdForUpdate(sessionId)
				.orElseThrow(() -> new IllegalArgumentException("Session not found"));

		if (session.getStatus() != SessionStatus.OPENED) {
			throw new IllegalStateException("Session is not open");
		}

		// Check for pending tickets - session cannot be closed if there are pending
		// tickets
		long pendingCount = salesHeaderRepository
				.findByCashierSessionAndStatus(session, com.digithink.zsretail.core.model.enumeration.TransactionStatus.PENDING)
				.size();

		if (pendingCount > 0) {
			throw new IllegalStateException("Cannot close session: There are " + pendingCount
					+ " pending ticket(s). Please complete or cancel all pending tickets before closing the session.");
		}

		// Calculate real cash (expected cash based on sales)
		Double realCash = calculateRealCash(session);
		session.setRealCash(realCash);
		log.info("Real cash calculated: " + realCash + " for session: " + session.getSessionNumber());

		// The methods of the count lines, read once (step 6: a scanned-tickets row's total is its face total)
		Map<Long, PaymentMethod> lineMethods = new HashMap<>();
		if (request.getCashCountLines() != null) {
			for (CloseSessionRequestDTO.CashCountLineDTO line : request.getCashCountLines()) {
				if (line.getPaymentMethodId() != null && !lineMethods.containsKey(line.getPaymentMethodId())) {
					lineMethods.put(line.getPaymentMethodId(),
							paymentMethodRepository.findById(line.getPaymentMethodId()).orElse(null));
				}
			}
		}

		// Calculate POS user closure cash from cash count lines if provided
		Double calculatedPosUserCash = null;
		if (request.getCashCountLines() != null && !request.getCashCountLines().isEmpty()) {
			calculatedPosUserCash = request.getCashCountLines().stream()
					.mapToDouble(line -> countLineTotal(line.getDenominationValue(), line.getQuantity(),
							line.getPaymentMethodId() != null ? lineMethods.get(line.getPaymentMethodId()) : null))
					.sum();
		}

		// Use provided actualCash or calculated from lines for POS user closure cash
		Double posUserClosureCash = request.getActualCash() != null ? request.getActualCash() : calculatedPosUserCash;

		// 3.1.0: the cash check compares cash with cash: realCash counts only the cash (opening + cash sales - change -
		// simple returns), so only the cash rows are compared with it, not the card slips, cheques or vouchers counted
		// beside (they made a false difference); posUserClosureCash stays the total counted, which the session reports
		// compare with all the sales
		Double countedCash = request.getActualCash() != null ? request.getActualCash()
				: countedCash(request.getCashCountLines());
		checkCashDiscrepancy(session, realCash, countedCash, request);

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

				// Set payment method if provided
				PaymentMethod paymentMethod = lineDTO.getPaymentMethodId() != null
						? lineMethods.get(lineDTO.getPaymentMethodId())
						: null;
				cashCount.setPaymentMethod(paymentMethod);

				// Calculate line total (step 6: a scanned-tickets row, its face total)
				cashCount.setLineTotal(
						countLineTotal(lineDTO.getDenominationValue(), lineDTO.getQuantity(), paymentMethod));

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
	 * Step 6 polish: the payment methods of a session's sales (PENDING and CANCELLED left out), active or not, each once:
	 * the till's closing window gives a row to a method switched off that still took payments. No amount.
	 */
	public List<PaymentMethod> sessionPaymentMethods(CashierSession session) {
		Map<Long, PaymentMethod> methods = new LinkedHashMap<>();
		for (Payment payment : paymentRepository.findByCashierSession(session,
				Arrays.asList(TransactionStatus.PENDING, TransactionStatus.CANCELLED))) {
			if (payment.getPaymentMethod() != null) {
				methods.putIfAbsent(payment.getPaymentMethod().getId(), payment.getPaymentMethod());
			}
		}
		return new ArrayList<>(methods.values());
	}

	/**
	 * 3.2.0: the session's scanned tickets' face total minus their net (deduction + excess kept), sales PENDING and
	 * CANCELLED left out: the sessions list adds it to its system amount, as the session details compare a scanned-tickets
	 * method on its face total (SessionDashboardDTO.fromEntity). 0 without scanned tickets.
	 */
	double scannedFaceGap(CashierSession session) {
		BigDecimal gap = BigDecimal.ZERO;
		for (ScannedTicketsSummary.Method method : ScannedTicketsSummary
				.byMethodCode(scannedTicketPaymentRepository.findByCashierSession(session,
						Arrays.asList(TransactionStatus.PENDING, TransactionStatus.CANCELLED)))
				.values()) {
			gap = gap.add(method.getFace().subtract(method.getNet()));
		}
		return gap.doubleValue();
	}

	/**
	 * A count row's total: denomination x quantity. Step 6: a scanned-tickets method's row holds the face total the
	 * cashier read on the tickets as its denomination and their number as its quantity, so its total is the face total.
	 */
	static double countLineTotal(Double denomination, Integer quantity, PaymentMethod method) {
		double value = denomination != null ? denomination : 0.0;
		if (ScannedTicketsSummary.isScanned(method)) {
			return value;
		}
		return value * (quantity != null ? quantity : 0);
	}

	/**
	 * 3.1.0: the cash counted at closing: the rows of the cash method (CLIENT_ESPECES) and the rows without method (the
	 * float, "fond de caisse"), the cash the expected amount (calculateRealCash) is made of; null without any row.
	 */
	Double countedCash(List<CloseSessionRequestDTO.CashCountLineDTO> lines) {
		if (lines == null || lines.isEmpty()) {
			return null;
		}
		Long cashMethodId = paymentMethodRepository.findByType(PaymentMethodType.CLIENT_ESPECES)
				.map(PaymentMethod::getId).orElse(null);
		return lines.stream()
				.filter(line -> line.getPaymentMethodId() == null || line.getPaymentMethodId().equals(cashMethodId))
				.mapToDouble(line -> (line.getDenominationValue() != null ? line.getDenominationValue() : 0.0)
						* (line.getQuantity() != null ? line.getQuantity() : 0))
				.sum();
	}

	/**
	 * The cash counted against the cash expected when the session is closed (ENABLE_CASH_DISCREPANCY_CHECK, on when the
	 * setting is absent). A difference above 0.01 needs the right CLOSE_SESSION_WITH_DISCREPANCY (TillRightsGuard): the
	 * logged user whose role holds it confirms the difference shown ({@code discrepancyConfirmed}, step C; the first
	 * request still gets the warning), anyone else sends the grant of a badge scan ({@code tillGrant}, step D, used once).
	 * Neither came: {@link CashDiscrepancyException} (the till shows the difference and asks for the right). A
	 * confirmation from a role without the right, or a grant not valid: 403 TILL_RIGHT_REQUIRED.
	 */
	void checkCashDiscrepancy(CashierSession session, Double realCash, Double posUserClosureCash,
			CloseSessionRequestDTO request) {
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
				if (!tillRightsGuard.closeWithDifference(loggedUser(), session, request.isDiscrepancyConfirmed(),
						request.getTillGrant())) {
					// Neither a confirmation nor a grant: the till shows the difference, then asks for the right
					throw new CashDiscrepancyException(realCash, posUserClosureCash, difference);
				}
				log.info("Session closure with discrepancy authorized ({}): session {}, user {}, expected {}, counted {}",
						request.isDiscrepancyConfirmed() ? "role's right" : "badge's grant", session.getSessionNumber(),
						currentUserProvider.getCurrentUserName(), realCash, posUserClosureCash);
			}
		}
	}

	/** The logged user, null without one (a request with no security context). */
	private UserAccount loggedUser() {
		try {
			return currentUserProvider.getCurrentUser();
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * Verify session and set responsible closure cash (for responsible user)
	 * 
	 * @throws Exception
	 */
	@Transactional(rollbackFor = Exception.class)
	public CashierSession verifySession(Long sessionId, Double responsibleClosureCash, String verificationNotes,
			UserAccount responsibleUser, List<Map<String, Object>> paymentDetails) throws Exception {
		// Pessimistic lock: serialize against admin payment-method changes that recompute realCash.
		CashierSession session = cashierSessionRepository.findByIdForUpdate(sessionId)
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
				// Step 6: a scanned-tickets method: the amount is the face total, "count" the number of tickets
				if (ScannedTicketsSummary.isScanned(scc.getPaymentMethod())) {
					scc.setQuantity(detail.get("count") instanceof Number ? ((Number) detail.get("count")).intValue() : 0);
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
	 * Get paginated session history with filters (server-side pagination)
	 */
	public Map<String, Object> getSessionHistory(String searchStr, String dateFromStr, String dateToStr,
			String statusStr, String syncStatusStr, String cashierIdStr, String minTotalSalesStr,
			String maxTotalSalesStr, String minSalesCountStr, String maxSalesCountStr, String differenceSignStr,
			String posMachineIdStr, int page, int size) {

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
		// 3.2.0, POS machines step 2: the sessions opened on this POS machine
		Long finalPosMachineId = null;
		if (posMachineIdStr != null && !posMachineIdStr.trim().isEmpty()) {
			try {
				finalPosMachineId = Long.parseLong(posMachineIdStr.trim());
			} catch (Exception e) {
				/* ignore */ }
		}
		final Long fPosMachineId = finalPosMachineId;

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
			if (fPosMachineId != null) {
				predicates.add(SessionPosMachineService.openedOn(root.get("id"), fPosMachineId, query, cb));
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
					totalReturnsAmount, simpleReturnsAmount, voucherReturnsAmount, realCash, scannedFaceGap(session));
		}).collect(Collectors.toList());
		// 3.2.0, POS machines step 2: the POS machine of each session of the page, one query
		Map<Long, String> machineNames = sessionPosMachineService
				.namesBySession(dtos.stream().map(SessionDashboardDTO::getId).collect(Collectors.toList()));
		dtos.forEach(dto -> dto.setPosMachineName(machineNames.get(dto.getId())));

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
		List<com.digithink.zsretail.store.erp.model.PaymentHeader> paymentHeaders = paymentHeaderRepository
				.findByCashierSession(session);

		// Build payment summary — batch-load all payments (replaces N+1 paymentLines
		// loop)
		List<TransactionStatus> excluded = Arrays.asList(TransactionStatus.PENDING, TransactionStatus.CANCELLED);
		List<Payment> allPayments = paymentRepository.findByCashierSession(session, excluded);

		com.digithink.zsretail.core.model.PaymentMethod especeMethod = paymentMethodRepository
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
		// Step 6: a scanned-tickets method is checked on its papers: the system's number of tickets and face total, the
		// numbers of tickets declared (the count rows' quantity)
		Map<String, ScannedTicketsSummary.Method> scanned = ScannedTicketsSummary
				.byMethodCode(scannedTicketPaymentRepository.findByCashierSession(session, excluded));
		Set<String> scannedCodes = new HashSet<>(scanned.keySet());
		Map<String, Long> posTicketsByCode = new HashMap<>();
		Map<String, Long> respTicketsByCode = new HashMap<>();
		for (SessionCashCount scc : cashCountLines) {
			if (!ScannedTicketsSummary.isScanned(scc.getPaymentMethod())) {
				continue;
			}
			String code = scc.getPaymentMethod().getCode();
			scannedCodes.add(code);
			codeToName.putIfAbsent(code, scc.getPaymentMethod().getName());
			long tickets = scc.getQuantity() != null ? scc.getQuantity() : 0L;
			(scc.getCounterType() == CounterType.POS_USER ? posTicketsByCode : respTicketsByCode).merge(code, tickets,
					Long::sum);
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
			// Step 6: a scanned-tickets method: the face total (what is declared), the net stays its payments
			boolean isScanned = scannedCodes.contains(code);
			if (isScanned) {
				sysAmount = scanned.containsKey(code) ? scanned.get(code).getFace().doubleValue() : 0.0;
				row.put("netAmount", codeToRawAmount.getOrDefault(code, 0.0));
			}
			row.put("systemAmount", sysAmount);
			row.put("isEspece", isEspece);
			row.put("scanned", isScanned);

			Double posClosure = posClosureByCode.get(code);
			row.put("posClosureAmount", posClosure);
			row.put("deltaPOS", posClosure != null ? posClosure - sysAmount : null);

			Double respClosure = respClosureByCode.get(code);
			row.put("respClosureAmount", respClosure);
			row.put("deltaResp", respClosure != null ? respClosure - sysAmount : null);

			if (isScanned) {
				long systemCount = scanned.containsKey(code) ? scanned.get(code).getCount() : 0L;
				row.put("systemCount", systemCount);
				Long posCount = posClosure != null ? posTicketsByCode.getOrDefault(code, 0L) : null;
				row.put("posClosureCount", posCount);
				row.put("deltaCountPOS", posCount != null ? posCount - systemCount : null);
				Long respCount = respClosure != null ? respTicketsByCode.getOrDefault(code, 0L) : null;
				row.put("respClosureCount", respCount);
				row.put("deltaCountResp", respCount != null ? respCount - systemCount : null);
			}

			com.digithink.zsretail.store.erp.model.PaymentHeader matchedHeader = paymentHeaders.stream()
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
		SessionDashboardDTO sessionDto = SessionDashboardDTO.fromEntity(session, salesCount, totalSalesAmount,
				returnsCount, totalReturnsAmount, simpleReturnsAmount, voucherReturnsAmount, realCash,
				scannedFaceGap(session));
		sessionDto.setPosMachineName(sessionPosMachineService.nameOf(session.getId())); // 3.2.0, POS machines step 2
		response.put("session", sessionDto);
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

		// Group by payment method — null-payment lines (fond de caisse) count toward
		// especeTotal (they are cash) but are not shown separately in the display list
		Map<String, Double> methodToAmount = new HashMap<>();
		Map<String, Long> methodToTickets = new HashMap<>();
		double especeTotal = 0.0;
		if (cashCountLines != null) {
			for (SessionCashCount line : cashCountLines) {
				Double amount = line.getLineTotal() != null ? line.getLineTotal() : 0.0;
				if (line.getPaymentMethod() == null) {
					especeTotal += amount; // null line = cash in drawer, counts toward net
					continue;
				}
				String name = line.getPaymentMethod().getName() != null
						? line.getPaymentMethod().getName()
						: "Cash";
				methodToAmount.merge(name, amount, Double::sum);
				// step 6: a scanned-tickets method: its number of tickets beside the face total
				if (ScannedTicketsSummary.isScanned(line.getPaymentMethod())) {
					methodToTickets.merge(name, line.getQuantity() != null ? line.getQuantity().longValue() : 0L,
							Long::sum);
				}
				if (PaymentMethodType.CLIENT_ESPECES.equals(line.getPaymentMethod().getType())) {
					especeTotal += amount;
				}
			}
		}
		double opening = session.getOpeningCash() != null ? session.getOpeningCash() : 0.0;
		double netEspeces = Math.max(0.0, especeTotal - opening);

		List<SessionCloseTicketDTO.PaymentMethodAmount> paymentMethodAmounts = methodToAmount.entrySet().stream()
				.sorted(Comparator.comparing(Map.Entry::getKey))
				.map(e -> new SessionCloseTicketDTO.PaymentMethodAmount(e.getKey(), e.getValue(),
						methodToTickets.get(e.getKey())))
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
