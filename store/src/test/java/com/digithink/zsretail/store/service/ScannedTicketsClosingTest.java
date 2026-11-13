package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.CounterType;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodBehaviour;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.store.dto.SessionCloseTicketDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.ControlLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.ScannedTickets;
import com.digithink.zsretail.store.dto.StoreReportTicketDTO;
import com.digithink.zsretail.store.erp.repository.PaymentHeaderRepository;
import com.digithink.zsretail.store.model.ScannedTicketPayment;
import com.digithink.zsretail.store.model.SessionCashCount;
import com.digithink.zsretail.store.repository.PaymentChangeLogRepository;
import com.digithink.zsretail.store.repository.ScannedTicketPaymentRepository;
import com.digithink.zsretail.store.repository.SessionCashCountRepository;
import com.digithink.zsretail.store.support.InMemoryTillRights;

/**
 * 3.2.0, step 6: a scanned-tickets method at closing and on the tickets X / Z. The cashier declares the number of
 * tickets and their face total (one count row: quantity = tickets, denomination = line total = face total); the
 * expected values are the session's tickets; the ticket's block gives face = deduction + excess kept + net, the net
 * being the PAIEMENTS line. The session: 18.900 paid by one Servimax ticket (7.000 at -10 %: 6.300) and 12.600 cash;
 * 10.000 paid by a Servimax (6.300) and a Sodexo 5.000 of which 3.700 applied (1.300 kept); a cancelled sale's ticket
 * left out. So: 3 tickets, face 19.000, counted 17.600 (deduction 1.400), applied 16.300, excess 1.300.
 */
class ScannedTicketsClosingTest {

	private final PaymentMethod cash = method(1L, "ESP", "Espèces", PaymentMethodType.CLIENT_ESPECES, null, 1);
	private final PaymentMethod meal = method(3L, "TR", "Ticket Restaurant", null, PaymentMethodBehaviour.SCANNED_TICKETS,
			3);
	private final Item goods = new Item();

	private CashierSession session;
	private final List<SalesHeader> sales = new ArrayList<>();
	private final List<SalesLine> lines = new ArrayList<>();
	private final List<Payment> payments = new ArrayList<>();
	private final List<ScannedTicketPayment> tickets = new ArrayList<>();
	private final List<SessionCashCount> counts = new ArrayList<>();
	private final List<SessionCashCount> saved = new ArrayList<>();
	private long nextId = 100;

	private SessionReportService reports;
	private CashierSessionService sessions;

	@BeforeEach
	void setUp() throws Exception {
		goods.setItemCode("ART001");
		session = new CashierSession();
		session.setId(7L);
		session.setSessionNumber("SESSION261113001");
		session.setCashier(user("Amira Ben Salem"));
		session.setOpeningCash(100.0);
		session.setOpenedAt(LocalDateTime.of(2026, 11, 13, 8, 0));
		session.setClosedAt(LocalDateTime.of(2026, 11, 13, 18, 0));
		session.setStatus(SessionStatus.CLOSED);
		session.setRealCash(112.6);

		SalesHeader first = sale(TransactionStatus.COMPLETED, 18.9);
		pay(first, cash, 12.6);
		ticket(pay(first, meal, 6.3), "Servimax Repas", "7.000", "6.300", "6.300", "0.000");
		SalesHeader second = sale(TransactionStatus.COMPLETED, 10.0);
		ticket(pay(second, meal, 6.3), "Servimax Repas", "7.000", "6.300", "6.300", "0.000");
		ticket(pay(second, meal, 3.7), "Sodexo Repas", "5.000", "5.000", "3.700", "1.300");
		SalesHeader cancelled = sale(TransactionStatus.CANCELLED, 7.0);
		ticket(pay(cancelled, meal, 7.0), "Servimax Repas", "7.000", "7.000", "7.000", "0.000");

		CashierSessionRepository sessionRepo = proxy(CashierSessionRepository.class, (m, a) -> {
			if ("findById".equals(m) || "findByIdForUpdate".equals(m)) {
				return session.getId().equals(a[0]) ? Optional.of(session) : Optional.empty();
			}
			return "save".equals(m) ? a[0] : UNHANDLED;
		});
		SalesHeaderRepository salesRepo = proxy(SalesHeaderRepository.class, (m, a) -> {
			if ("findByCashierSession".equals(m)) return new ArrayList<>(sales);
			if ("findByCashierSessionAndStatus".equals(m)) {
				return sales.stream().filter(s -> s.getStatus() == a[1]).collect(Collectors.toList());
			}
			return UNHANDLED;
		});
		PaymentRepository paymentRepo = proxy(PaymentRepository.class, (m, a) -> {
			if ("findByCashierSession".equals(m)) {
				List<?> excluded = (List<?>) a[1];
				return payments.stream().filter(p -> !excluded.contains(p.getSalesHeader().getStatus()))
						.collect(Collectors.toList());
			}
			if ("findBySalesHeader".equals(m)) {
				return payments.stream().filter(p -> p.getSalesHeader() == a[0]).collect(Collectors.toList());
			}
			return UNHANDLED;
		});
		ReturnHeaderRepository returnRepo = proxy(ReturnHeaderRepository.class,
				(m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>() : UNHANDLED);
		PaymentMethodRepository methodRepo = proxy(PaymentMethodRepository.class, (m, a) -> {
			if ("findByType".equals(m)) return a[0] == PaymentMethodType.CLIENT_ESPECES ? Optional.of(cash) : Optional.empty();
			if ("findById".equals(m)) {
				return Arrays.asList(cash, meal).stream().filter(pm -> pm.getId().equals(a[0])).findFirst();
			}
			if ("findByCode".equals(m)) {
				return Arrays.asList(cash, meal).stream().filter(pm -> pm.getCode().equals(a[0])).findFirst();
			}
			if ("findAll".equals(m)) return Arrays.asList(cash, meal);
			return UNHANDLED;
		});
		SessionCashCountRepository countRepo = proxy(SessionCashCountRepository.class, (m, a) -> {
			if ("findByCashierSession".equals(m)) return new ArrayList<>(counts);
			if ("findByCashierSessionAndCounterType".equals(m)) {
				return counts.stream().filter(c -> c.getCounterType() == a[1]).collect(Collectors.toList());
			}
			if ("save".equals(m)) {
				counts.add((SessionCashCount) a[0]);
				return a[0];
			}
			return UNHANDLED;
		});
		ScannedTicketPaymentRepository ticketRepo = proxy(ScannedTicketPaymentRepository.class, (m, a) -> {
			if (!"findByCashierSession".equals(m)) return UNHANDLED;
			List<?> excluded = (List<?>) a[1];
			return tickets.stream().filter(t -> !excluded.contains(t.getPayment().getSalesHeader().getStatus()))
					.collect(Collectors.toList());
		});
		CurrentUserProvider user = new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return user("Karim Responsable");
			}

			@Override
			public String getCurrentUserName() {
				return "karim";
			}
		};

		sessions = new CashierSessionService();
		set(sessions, CashierSessionService.class, "cashierSessionRepository", sessionRepo);
		set(sessions, CashierSessionService.class, "salesHeaderRepository", salesRepo);
		set(sessions, CashierSessionService.class, "paymentRepository", paymentRepo);
		set(sessions, CashierSessionService.class, "returnHeaderRepository", returnRepo);
		set(sessions, CashierSessionService.class, "paymentMethodRepository", methodRepo);
		set(sessions, CashierSessionService.class, "sessionCashCountRepository", countRepo);
		set(sessions, CashierSessionService.class, "scannedTicketPaymentRepository", ticketRepo);
		set(sessions, CashierSessionService.class, "paymentHeaderRepository", proxy(PaymentHeaderRepository.class,
				(m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>() : UNHANDLED));
		set(sessions, CashierSessionService.class, "tillRightsGuard", InMemoryTillRights.allowAll());
		set(sessions, CashierSessionService.class, "generalSetupRepository", proxy(GeneralSetupRepository.class,
				(m, a) -> {
					if (!"findByCode".equals(m)) return UNHANDLED;
					GeneralSetup on = new GeneralSetup();
					on.setCode((String) a[0]);
					on.setValeur("true");
					return "ENABLE_CASH_DISCREPANCY_CHECK".equals(a[0]) ? Optional.of(on) : Optional.empty();
				}));
		set(sessions, _BaseService.class, "currentUserProvider", user);
		SessionCashCountService countService = new SessionCashCountService() {
			@Override
			public SessionCashCount save(SessionCashCount count) {
				saved.add(count);
				return count;
			}
		};
		set(sessions, CashierSessionService.class, "sessionCashCountService", countService);

		reports = new SessionReportService();
		set(reports, SessionReportService.class, "cashierSessionRepository", sessionRepo);
		set(reports, SessionReportService.class, "cashierSessionService", sessions);
		set(reports, SessionReportService.class, "salesHeaderRepository", salesRepo);
		set(reports, SessionReportService.class, "paymentRepository", paymentRepo);
		set(reports, SessionReportService.class, "returnHeaderRepository", returnRepo);
		set(reports, SessionReportService.class, "paymentMethodRepository", methodRepo);
		set(reports, SessionReportService.class, "sessionCashCountRepository", countRepo);
		set(reports, SessionReportService.class, "scannedTicketPaymentRepository", ticketRepo);
		set(reports, SessionReportService.class, "currentUserProvider", user);
		set(reports, SessionReportService.class, "salesLineRepository", proxy(SalesLineRepository.class,
				(m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>(lines) : UNHANDLED));
		set(reports, SessionReportService.class, "paymentChangeLogRepository", proxy(PaymentChangeLogRepository.class,
				(m, a) -> "countBySalesHeaderCashierSessionAndCreatedAtAfter".equals(m) ? 0L : UNHANDLED));
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private static PaymentMethod method(Long id, String code, String name, PaymentMethodType type,
			PaymentMethodBehaviour behaviour, int order) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setCode(code);
		method.setName(name);
		method.setType(type);
		method.setBehaviour(behaviour != null ? behaviour : PaymentMethodBehaviour.AMOUNT);
		method.setDisplayOrder(order);
		return method;
	}

	private static UserAccount user(String fullName) {
		UserAccount user = new UserAccount();
		user.setUsername(fullName.toLowerCase().replace(' ', '.'));
		user.setFullName(fullName);
		return user;
	}

	private SalesHeader sale(TransactionStatus status, double total) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setSalesNumber("S" + sale.getId());
		sale.setCashierSession(session);
		sale.setStatus(status);
		sale.setTotalAmount(total);
		sale.setDiscountAmount(0.0);
		sale.setChangeAmount(0.0);
		sales.add(sale);
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setSalesHeader(sale);
		line.setItem(goods);
		line.setQuantity(BigDecimal.ONE);
		line.setVatPercent(0);
		line.setLineTotalIncludingVat(total);
		line.setLineTotal(total);
		line.setVatAmount(0.0);
		line.setUnitPriceIncludingVat(total);
		if (status != TransactionStatus.CANCELLED) {
			lines.add(line);
		}
		return sale;
	}

	private Payment pay(SalesHeader sale, PaymentMethod method, double amount) {
		Payment payment = new Payment();
		payment.setId(nextId++);
		payment.setSalesHeader(sale);
		payment.setPaymentMethod(method);
		payment.setTotalAmount(amount);
		payments.add(payment);
		return payment;
	}

	private void ticket(Payment payment, String issuer, String face, String counted, String applied, String excess) {
		ScannedTicketPayment ticket = new ScannedTicketPayment();
		ticket.setId(nextId++);
		ticket.setPayment(payment);
		ticket.setIssuerName(issuer);
		ticket.setFaceValue(new BigDecimal(face));
		ticket.setCountedValue(new BigDecimal(counted));
		ticket.setAppliedAmount(new BigDecimal(applied));
		ticket.setExcessAmount(new BigDecimal(excess));
		tickets.add(ticket);
	}

	/** A count row as the closing stores it (a scanned-tickets row: denomination = face total, quantity = tickets). */
	private void count(CounterType counter, PaymentMethod method, double amount, int quantity) {
		SessionCashCount count = new SessionCashCount();
		count.setCashierSession(session);
		count.setCounterType(counter);
		count.setPaymentMethod(method);
		count.setDenominationValue(amount);
		count.setQuantity(quantity);
		count.setLineTotal(CashierSessionService.countLineTotal(amount, quantity, method));
		counts.add(count);
	}

	private static ControlLine line(List<ControlLine> control, String code) {
		return control.stream().filter(c -> code.equals(c.getCode())).findFirst().orElse(null);
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	// ─── The ticket Z: CONTRÔLE ─────────────────────────────────────

	@Test
	@DisplayName("Declared as expected: 3 tickets, face 19.000: no difference, on the face total and on the number")
	void matching() {
		count(CounterType.POS_USER, cash, 112.6, 1);
		count(CounterType.POS_USER, meal, 19.0, 3);
		ControlLine tr = line(reports.build(7L).getControl(), "TR");
		assertTrue(tr.isScanned());
		assertEquals(19.0, tr.getExpected());
		assertEquals(19.0, tr.getCounted());
		assertEquals(0.0, tr.getDifference());
		assertEquals(3L, tr.getExpectedCount());
		assertEquals(3L, tr.getCountedCount());
		assertEquals(0L, tr.getCountDifference());
	}

	@Test
	@DisplayName("A wrong face total: the difference on the face total, none on the number of tickets")
	void wrongFaceTotal() {
		count(CounterType.POS_USER, meal, 18.0, 3);
		ControlLine tr = line(reports.build(7L).getControl(), "TR");
		assertEquals(-1.0, tr.getDifference());
		assertEquals(0L, tr.getCountDifference());
	}

	@Test
	@DisplayName("A wrong number of tickets: the difference in tickets, none on the face total")
	void wrongNumberOfTickets() {
		count(CounterType.POS_USER, meal, 19.0, 2);
		ControlLine tr = line(reports.build(7L).getControl(), "TR");
		assertEquals(0.0, tr.getDifference());
		assertEquals(2L, tr.getCountedCount());
		assertEquals(-1L, tr.getCountDifference());
	}

	@Test
	@DisplayName("A session closed before step 6: the method was not declared, '—' (null), the expected values shown")
	void oldSessionNotDeclared() {
		count(CounterType.POS_USER, cash, 112.6, 1);
		SessionReportTicketDTO z = reports.build(7L);
		ControlLine tr = line(z.getControl(), "TR");
		assertEquals(19.0, tr.getExpected());
		assertEquals(3L, tr.getExpectedCount());
		assertNull(tr.getCounted());
		assertNull(tr.getCountedCount());
		assertNull(tr.getCountDifference());
		assertEquals(112.6, line(z.getControl(), "ESP").getCounted());
		assertFalse(line(z.getControl(), "ESP").isScanned());
		assertNull(line(z.getControl(), "ESP").getExpectedCount());
	}

	@Test
	@DisplayName("A ticket X: the expected face total and number, nothing counted")
	void ticketX() {
		session.setStatus(SessionStatus.OPENED);
		session.setClosedAt(null);
		session.setRealCash(null);
		SessionReportTicketDTO x = reports.build(7L);
		ControlLine tr = line(x.getControl(), "TR");
		assertEquals("X", x.getType());
		assertEquals(19.0, tr.getExpected());
		assertEquals(3L, tr.getExpectedCount());
		assertNull(tr.getCounted());
		assertNull(tr.getCountedCount());
	}

	// ─── The ticket Z: the block of the method ──────────────────────

	@Test
	@DisplayName("The block: 3 tickets, face 19.000 = deduction 1.400 + excess kept 1.300 + net 16.300; two issuers")
	void blockWithExcessAndTwoIssuers() {
		SessionReportTicketDTO z = reports.build(7L);
		assertEquals(1, z.getScannedTickets().size());
		ScannedTickets tr = z.getScannedTickets().get(0);
		assertEquals("TR", tr.getCode());
		assertEquals("Ticket Restaurant", tr.getName());
		assertEquals(3L, tr.getTicketsCount());
		assertEquals(19.0, tr.getFaceTotal());
		assertEquals(1.4, tr.getDeduction());
		assertEquals(1.3, tr.getExcess());
		assertEquals(16.3, tr.getNet());
		assertEquals(0, BigDecimal.valueOf(tr.getFaceTotal()).compareTo(BigDecimal.valueOf(tr.getDeduction())
				.add(BigDecimal.valueOf(tr.getExcess())).add(BigDecimal.valueOf(tr.getNet()))));
		assertEquals(Arrays.asList("Servimax Repas 2 14.0", "Sodexo Repas 1 5.0"), tr.getIssuers().stream()
				.map(i -> i.getIssuerName() + " " + i.getTicketsCount() + " " + i.getFaceTotal())
				.collect(Collectors.toList()));
		// PAIEMENTS stays the net (the cancelled sale's ticket left out)
		assertEquals(16.3, z.getPayments().stream().filter(p -> "TR".equals(p.getCode())).findFirst().get().getAmount());
	}

	@Test
	@DisplayName("The responsible's verification per method: the number of tickets checked too")
	void verification() {
		session.setStatus(SessionStatus.TERMINATED);
		session.setVerifiedBy(user("Karim Responsable"));
		count(CounterType.POS_USER, meal, 19.0, 3);
		count(CounterType.RESPONSIBLE, cash, 112.6, 1);
		count(CounterType.RESPONSIBLE, meal, 19.0, 2);
		ControlLine tr = line(reports.build(7L).getVerification().getLines(), "TR");
		assertEquals(0.0, tr.getDifference());
		assertEquals(2L, tr.getCountedCount());
		assertEquals(-1L, tr.getCountDifference());
	}

	// ─── The closing and the session's details ──────────────────────

	@Test
	@DisplayName("Closing: the scanned row keeps tickets as quantity and face total as its total; the cash check ignores it")
	void closing() throws Exception {
		session.setStatus(SessionStatus.OPENED);
		session.setClosedAt(null);
		session.setRealCash(null);
		CloseSessionRequestDTO request = new CloseSessionRequestDTO();
		request.setCashCountLines(Arrays.asList(closeLine(1L, 112.6, 1), closeLine(3L, 19.0, 3)));
		sessions.closeSessionWithCashCount(7L, request);
		assertEquals(SessionStatus.CLOSED, session.getStatus());
		assertEquals(112.6, session.getRealCash(), 1e-9, "the cash only");
		SessionCashCount tr = saved.stream().filter(c -> c.getPaymentMethod() == meal).findFirst().get();
		assertEquals(3, tr.getQuantity());
		assertEquals(19.0, tr.getDenominationValue(), 1e-9);
		assertEquals(19.0, tr.getLineTotal(), 1e-9, "the face total, not 19 x 3");
		assertEquals(131.6, session.getPosUserClosureCash(), 1e-9, "every row's total, the face total included");
	}

	private static CloseSessionRequestDTO.CashCountLineDTO closeLine(Long methodId, double amount, int quantity) {
		CloseSessionRequestDTO.CashCountLineDTO line = new CloseSessionRequestDTO.CashCountLineDTO();
		line.setPaymentMethodId(methodId);
		line.setDenominationValue(amount);
		line.setQuantity(quantity);
		return line;
	}

	@Test
	@DisplayName("The session's details (history, verification): the face total and the numbers of tickets, the net beside")
	@SuppressWarnings("unchecked")
	void details() {
		count(CounterType.POS_USER, cash, 112.6, 1);
		count(CounterType.POS_USER, meal, 18.0, 2);
		Map<String, Object> details = sessions.getSessionDetails(7L);
		Map<String, Object> tr = ((List<Map<String, Object>>) details.get("paymentSummary")).stream()
				.filter(r -> "TR".equals(r.get("code"))).findFirst().get();
		assertEquals(true, tr.get("scanned"));
		assertEquals(19.0, (double) tr.get("systemAmount"), 1e-9);
		assertEquals(16.3, (double) tr.get("netAmount"), 1e-9);
		assertEquals(3L, tr.get("systemCount"));
		assertEquals(18.0, (double) tr.get("posClosureAmount"), 1e-9);
		assertEquals(-1.0, (double) tr.get("deltaPOS"), 1e-9);
		assertEquals(2L, tr.get("posClosureCount"));
		assertEquals(-1L, tr.get("deltaCountPOS"));
		assertNull(tr.get("respClosureCount"));
		Map<String, Object> esp = ((List<Map<String, Object>>) details.get("paymentSummary")).stream()
				.filter(r -> "ESP".equals(r.get("code"))).findFirst().get();
		assertEquals(false, esp.get("scanned"));
		assertFalse(esp.containsKey("systemCount"));
	}

	@Test
	@DisplayName("The verification form's line for the method: the face total and its 'count', stored as the quantity")
	void verifyStoresTheCount() throws Exception {
		Map<String, Object> cashLine = new HashMap<>();
		cashLine.put("code", "ESP");
		cashLine.put("amount", 112.6);
		Map<String, Object> mealLine = new HashMap<>();
		mealLine.put("code", "TR");
		mealLine.put("amount", 19.0);
		mealLine.put("count", 3);
		sessions.verifySession(7L, 131.6, null, user("Karim Responsable"), Arrays.asList(cashLine, mealLine));
		SessionCashCount tr = counts.stream()
				.filter(c -> c.getCounterType() == CounterType.RESPONSIBLE && c.getPaymentMethod() == meal).findFirst()
				.get();
		assertEquals(3, tr.getQuantity());
		assertEquals(19.0, tr.getLineTotal(), 1e-9);
	}

	@Test
	@DisplayName("The declared closing ticket (CASHIER_CLOSING_TICKET DECLARED): the face total and the number of tickets")
	void declaredTicket() {
		count(CounterType.POS_USER, cash, 112.6, 1);
		count(CounterType.POS_USER, meal, 19.0, 3);
		SessionCloseTicketDTO ticket = sessions.getSessionCloseTicketData(7L);
		SessionCloseTicketDTO.PaymentMethodAmount tr = ticket.getPaymentMethodAmounts().stream()
				.filter(p -> "Ticket Restaurant".equals(p.getPaymentMethodName())).findFirst().get();
		assertEquals(19.0, tr.getAmount(), 1e-9);
		assertEquals(3L, tr.getTicketsCount());
		assertNull(ticket.getPaymentMethodAmounts().stream()
				.filter(p -> "Espèces".equals(p.getPaymentMethodName())).findFirst().get().getTicketsCount());
	}

	@Test
	@DisplayName("Polish: the session's methods for the closing window, a switched-off one included, a cancelled sale's left out")
	void sessionPaymentMethods() {
		PaymentMethod card = method(5L, "TEST_CARTE", "Carte Pluxee TEST", null, null, 9);
		card.setActive(false);
		pay(sales.get(0), card, 0.0);
		PaymentMethod cheque = method(6L, "CHQ", "Chèque", PaymentMethodType.CLIENT_CHEQUE, null, 5);
		pay(sales.get(2), cheque, 7.0); // the cancelled sale only
		assertEquals(Arrays.asList("ESP", "TR", "TEST_CARTE"), sessions.sessionPaymentMethods(session).stream()
				.map(PaymentMethod::getCode).collect(Collectors.toList()));
	}

	// ─── The store ticket Z ─────────────────────────────────────────

	@Test
	@DisplayName("Store Z: two sessions' blocks added up per method, the issuers merged; the numbers of tickets in CONTRÔLE")
	void storeZ() throws Exception {
		count(CounterType.POS_USER, meal, 19.0, 3);
		SessionReportTicketDTO first = reports.build(7L);
		SessionReportTicketDTO second = reports.build(7L);
		second.setSessionId(8L);
		// the second session: one Sodexo ticket more declared, 4 tickets expected
		ScannedTickets block = second.getScannedTickets().get(0);
		block.setTicketsCount(1);
		block.setFaceTotal(5.0);
		block.setDeduction(0.0);
		block.setExcess(0.0);
		block.setNet(5.0);
		block.setIssuers(new ArrayList<>(Arrays.asList(new SessionReportTicketDTO.Issuer("Sodexo Repas", 1, 5.0))));
		ControlLine tr2 = line(second.getControl(), "TR");
		tr2.setExpected(5.0);
		tr2.setCounted(5.0);
		tr2.setDifference(0.0);
		tr2.setExpectedCount(1L);
		tr2.setCountedCount(2L);
		tr2.setCountDifference(1L);

		CashierSession other = new CashierSession();
		other.setId(8L);
		Map<Long, SessionReportTicketDTO> built = new HashMap<>();
		built.put(7L, first);
		built.put(8L, second);
		StoreReportService store = new StoreReportService();
		set(store, StoreReportService.class, "sessionReportService", new SessionReportService() {
			@Override
			public SessionReportTicketDTO build(Long sessionId) {
				return built.get(sessionId);
			}
		});
		set(store, StoreReportService.class, "cashierSessionRepository", proxy(CashierSessionRepository.class,
				(m, a) -> m.startsWith("findByStatusIn") ? Arrays.asList(session, other) : UNHANDLED));
		set(store, StoreReportService.class, "paymentMethodRepository", proxy(PaymentMethodRepository.class,
				(m, a) -> "findAll".equals(m) ? Arrays.asList(cash, meal) : UNHANDLED));
		set(store, StoreReportService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return user("Karim Responsable");
			}
		});
		set(store, StoreReportService.class, "companyInformationService", new CompanyInformationService(null) {
			@Override
			public CompanyInformationDTO get() {
				return new CompanyInformationDTO();
			}
		});
		store.clock = Clock.fixed(LocalDate.of(2026, 11, 13).atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant(),
				ZoneId.systemDefault());

		StoreReportTicketDTO z = store.buildZ(LocalDate.of(2026, 11, 13));
		ScannedTickets tr = z.getScannedTickets().get(0);
		assertEquals(4L, tr.getTicketsCount());
		assertEquals(24.0, tr.getFaceTotal());
		assertEquals(1.4, tr.getDeduction());
		assertEquals(1.3, tr.getExcess());
		assertEquals(21.3, tr.getNet());
		assertEquals(Arrays.asList("Servimax Repas 2 14.0", "Sodexo Repas 2 10.0"), tr.getIssuers().stream()
				.map(i -> i.getIssuerName() + " " + i.getTicketsCount() + " " + i.getFaceTotal())
				.collect(Collectors.toList()));
		ControlLine control = line(z.getControl(), "TR");
		assertTrue(control.isScanned());
		assertEquals(24.0, control.getExpected());
		assertEquals(4L, control.getExpectedCount());
		assertEquals(5L, control.getCountedCount());
		assertEquals(1L, control.getCountDifference());
	}
}
