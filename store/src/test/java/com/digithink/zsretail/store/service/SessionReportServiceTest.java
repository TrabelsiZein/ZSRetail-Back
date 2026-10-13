package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.CounterType;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.ControlLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.PaymentLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.VatLine;
import com.digithink.zsretail.store.model.SessionCashCount;
import com.digithink.zsretail.store.repository.PaymentChangeLogRepository;
import com.digithink.zsretail.store.repository.SessionCashCountRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 3.1.0: the session ticket X / Z (SessionReportService). The session's tables in memory, the real CashierSessionService
 * for the expected cash of an open session (calculateRealCash), no Spring.
 */
class SessionReportServiceTest {

	private static final double EPS = 1e-9;

	private final PaymentMethod cash = method(1L, "ESP", "Espèces", PaymentMethodType.CLIENT_ESPECES, 1);
	private final PaymentMethod card = method(2L, "TPE", "Carte bancaire", PaymentMethodType.CLIENT_TPE, 2);
	private final PaymentMethod voucher = method(8L, "BON", "Bon d'achat", PaymentMethodType.RETURN_VOUCHER, 8);
	private final Item stampItem = item("TAX_STAMP");
	private final Item goods = item("ART001");

	private CashierSession session;
	private final List<SalesHeader> sales = new ArrayList<>();
	private final List<SalesLine> lines = new ArrayList<>();
	private final List<Payment> payments = new ArrayList<>();
	private final List<ReturnHeader> returns = new ArrayList<>();
	private final List<SessionCashCount> counts = new ArrayList<>();
	private long changesAfter;
	private LocalDateTime changesAskedAfter;
	private long nextId = 100;

	private SessionReportService service;

	@BeforeEach
	void setUp() {
		session = new CashierSession();
		session.setId(7L);
		session.setSessionNumber("SESSION261013001");
		session.setCashier(user("cashier1", "Amira Ben Salem"));
		session.setOpeningCash(100.0);
		session.setOpenedAt(LocalDateTime.of(2026, 10, 13, 8, 0));
		session.setStatus(SessionStatus.OPENED);

		CashierSessionRepository sessions = proxy(CashierSessionRepository.class, (m, a) -> "findById".equals(m)
				? (session.getId().equals(a[0]) ? Optional.of(session) : Optional.empty())
				: UNHANDLED);
		SalesHeaderRepository salesRepo = proxy(SalesHeaderRepository.class,
				(m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>(sales) : UNHANDLED);
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
				(m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>(returns) : UNHANDLED);

		CashierSessionService sessionService = new CashierSessionService();
		inject(sessionService, "cashierSessionRepository", sessions);
		inject(sessionService, "salesHeaderRepository", salesRepo);
		inject(sessionService, "paymentRepository", paymentRepo);
		inject(sessionService, "returnHeaderRepository", returnRepo);

		service = new SessionReportService();
		inject(service, "cashierSessionRepository", sessions);
		inject(service, "cashierSessionService", sessionService);
		inject(service, "salesHeaderRepository", salesRepo);
		inject(service, "paymentRepository", paymentRepo);
		inject(service, "returnHeaderRepository", returnRepo);
		inject(service, "salesLineRepository", proxy(SalesLineRepository.class, (m, a) -> {
			if (!"findByCashierSession".equals(m)) return UNHANDLED;
			List<?> excluded = (List<?>) a[1];
			return lines.stream().filter(l -> !excluded.contains(l.getSalesHeader().getStatus()))
					.collect(Collectors.toList());
		}));
		inject(service, "sessionCashCountRepository", proxy(SessionCashCountRepository.class,
				(m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>(counts) : UNHANDLED));
		inject(service, "paymentMethodRepository", proxy(PaymentMethodRepository.class,
				(m, a) -> "findByType".equals(m) && a[0] == PaymentMethodType.CLIENT_ESPECES ? Optional.of(cash)
						: UNHANDLED));
		inject(service, "paymentChangeLogRepository", proxy(PaymentChangeLogRepository.class, (m, a) -> {
			if (!"countBySalesHeaderCashierSessionAndCreatedAtAfter".equals(m)) return UNHANDLED;
			changesAskedAfter = (LocalDateTime) a[1];
			return changesAfter;
		}));
		inject(service, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return user("resp1", "Karim Responsable");
			}
		});
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private static PaymentMethod method(Long id, String code, String name, PaymentMethodType type, int order) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setCode(code);
		method.setName(name);
		method.setType(type);
		method.setDisplayOrder(order);
		return method;
	}

	private static Item item(String code) {
		Item item = new Item();
		item.setItemCode(code);
		return item;
	}

	private static UserAccount user(String username, String fullName) {
		UserAccount user = new UserAccount();
		user.setUsername(username);
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
		return sale;
	}

	/** A goods line as the till sends it: TTC after the line discount, discount for the whole line, TTC. */
	private SalesLine line(SalesHeader sale, int rate, double ttc, double lineDiscount) {
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setSalesHeader(sale);
		line.setItem(goods);
		line.setQuantity(BigDecimal.ONE);
		line.setVatPercent(rate);
		line.setLineTotalIncludingVat(ttc);
		line.setLineTotal(ttc / (1 + rate / 100.0));
		line.setVatAmount(ttc - line.getLineTotal());
		line.setUnitPriceIncludingVat(ttc + lineDiscount);
		line.setDiscountAmount(lineDiscount > 0 ? lineDiscount : null);
		lines.add(line);
		return line;
	}

	private void stamp(SalesHeader sale) {
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setSalesHeader(sale);
		line.setItem(stampItem);
		line.setQuantity(BigDecimal.ONE);
		line.setVatPercent(0);
		line.setLineTotal(1.0);
		line.setVatAmount(0.0);
		line.setUnitPriceIncludingVat(1.0);
		line.setLineTotalIncludingVat(1.0);
		lines.add(line);
	}

	private void pay(SalesHeader sale, PaymentMethod method, double amount) {
		Payment payment = new Payment();
		payment.setId(nextId++);
		payment.setSalesHeader(sale);
		payment.setPaymentMethod(method);
		payment.setTotalAmount(amount);
		payments.add(payment);
	}

	private void ret(ReturnType type, double amount) {
		ReturnHeader ret = new ReturnHeader();
		ret.setId(nextId++);
		ret.setCashierSession(session);
		ret.setReturnType(type);
		ret.setTotalReturnAmount(amount);
		returns.add(ret);
	}

	private void count(CounterType counter, PaymentMethod method, double amount) {
		SessionCashCount count = new SessionCashCount();
		count.setCashierSession(session);
		count.setCounterType(counter);
		count.setPaymentMethod(method);
		count.setDenominationValue(amount);
		count.setQuantity(1);
		count.setLineTotal(amount);
		counts.add(count);
	}

	/**
	 * The session of the Z examples: a ticket with a 15 % ticket discount over two VAT rates and a fiscal stamp, paid
	 * by card and cash with change; a ticket partly paid with 10 TND of points, paid with a return voucher; a pending
	 * and a cancelled ticket; a simple return of 20 and a voucher return of 15.
	 */
	private void standardSession() {
		// lines 476.000 (19 %) + 107.200 (7 %) = 583.200 goods, 15 % off = 495.720, + stamp 1.000
		SalesHeader discounted = sale(TransactionStatus.COMPLETED, 496.72);
		discounted.setDiscountAmount(87.48);
		discounted.setDiscountPercentage(15.0);
		discounted.setChangeAmount(3.28);
		line(discounted, 19, 476.0, 24.0);
		line(discounted, 7, 107.2, 0);
		stamp(discounted);
		pay(discounted, card, 200.0);
		pay(discounted, cash, 300.0);

		// goods 50.000 at 19 %, 10 TND of points: 40.000 + stamp 1.000 = 41.000
		SalesHeader withPoints = sale(TransactionStatus.COMPLETED, 41.0);
		withPoints.setLoyaltyDeductionAmount(10.0);
		withPoints.setLoyaltyPointsRedeemed(1000);
		line(withPoints, 19, 50.0, 0);
		stamp(withPoints);
		pay(withPoints, voucher, 41.0);

		SalesHeader pending = sale(TransactionStatus.PENDING, 30.0);
		line(pending, 19, 30.0, 0);
		SalesHeader cancelled = sale(TransactionStatus.CANCELLED, 12.0);
		line(cancelled, 19, 12.0, 0);
		pay(cancelled, cash, 12.0);

		ret(ReturnType.SIMPLE_RETURN, 20.0);
		ret(ReturnType.RETURN_VOUCHER, 15.0);
	}

	private void close(Double realCash) {
		session.setStatus(SessionStatus.CLOSED);
		session.setClosedAt(LocalDateTime.of(2026, 10, 13, 20, 0));
		session.setRealCash(realCash);
	}

	private static VatLine vat(SessionReportTicketDTO dto, int rate) {
		return dto.getSales().getVatLines().stream().filter(v -> v.getRate() == rate).findFirst().orElse(null);
	}

	private static PaymentLine payment(SessionReportTicketDTO dto, String code) {
		return dto.getPayments().stream().filter(p -> code.equals(p.getCode())).findFirst().orElse(null);
	}

	private static ControlLine control(List<ControlLine> lines, String code) {
		return lines.stream().filter(c -> code.equals(c.getCode())).findFirst().orElse(null);
	}

	private static BigDecimal exact(Double value) {
		return BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP);
	}

	// ─── Tests ───────────────────────────────────────────────────────

	@Test
	@DisplayName("Open session: ticket X, no closing date, pending tickets counted, expected only (calculateRealCash)")
	void ticketX() {
		standardSession();
		SessionReportTicketDTO dto = service.build(7L);

		assertEquals("X", dto.getType());
		assertNull(dto.getClosedAt());
		assertNull(dto.getVerification());
		assertEquals(2, dto.getSales().getTicketsCount(), "PENDING and CANCELLED left out");
		assertEquals(1L, dto.getSales().getPendingCount());
		assertEquals(1, dto.getSales().getCancelledCount());
		// 100 opening + 300 cash - 3.28 change - 20 simple return; the cancelled ticket's cash is not counted
		ControlLine cashLine = control(dto.getControl(), "ESP");
		assertEquals(376.72, cashLine.getExpected(), EPS);
		dto.getControl().forEach(c -> {
			assertNull(c.getCounted(), c.getCode());
			assertNull(c.getDifference(), c.getCode());
		});
		assertFalse(dto.isModifiedAfterClosing());
		assertEquals("Karim Responsable", dto.getPrintedBy());
		assertNotNull(dto.getPrintedAt());
	}

	@Test
	@DisplayName("Closed session: ticket Z, the ticket discount and the points spread over the VAT rates, to the millime")
	void ticketZVat() {
		standardSession();
		close(376.72);
		SessionReportTicketDTO dto = service.build(7L);

		assertEquals("Z", dto.getType());
		assertEquals(LocalDateTime.of(2026, 10, 13, 20, 0), dto.getClosedAt());
		assertNull(dto.getSales().getPendingCount());
		// 19 %: 476 x 0.85 = 404.600, plus the points ticket 50 x 0.8 = 40.000: 444.600 TTC = 373.613 HT + 70.987 VAT
		VatLine v19 = vat(dto, 19);
		assertEquals(444.6, v19.getTotalTTC(), EPS);
		assertEquals(373.613, v19.getBaseHT(), EPS);
		assertEquals(70.987, v19.getVatAmount(), EPS);
		// 7 %: 107.2 x 0.85 = 91.120 TTC = 85.159 HT + 5.961 VAT
		VatLine v7 = vat(dto, 7);
		assertEquals(91.12, v7.getTotalTTC(), EPS);
		assertEquals(85.159, v7.getBaseHT(), EPS);
		assertEquals(5.961, v7.getVatAmount(), EPS);
		assertNull(vat(dto, 0), "the fiscal stamp is not a VAT 0 % line");
		assertEquals(2.0, dto.getSales().getTaxStamp(), EPS);
		assertEquals(537.72, dto.getSales().getTotalTTC(), EPS);
		assertEquals(exact(dto.getSales().getTotalTTC()), exact(dto.getSales().getTotalHT())
				.add(exact(dto.getSales().getTotalVat())).add(exact(dto.getSales().getTaxStamp())),
				"HT + VAT + stamp = TTC");
		// three discount lines
		assertEquals(24.0, dto.getSales().getLineDiscounts(), EPS);
		assertEquals(87.48, dto.getSales().getTicketDiscounts(), EPS);
		assertEquals(10.0, dto.getSales().getLoyaltyDiscounts(), EPS);
	}

	@Test
	@DisplayName("Payments: cash net of the change, a two-method ticket under both, the voucher; their sum is the TTC")
	void payments() {
		standardSession();
		close(376.72);
		SessionReportTicketDTO dto = service.build(7L);

		assertEquals(296.72, payment(dto, "ESP").getAmount(), EPS);
		assertTrue(payment(dto, "ESP").isCash());
		assertEquals(200.0, payment(dto, "TPE").getAmount(), EPS);
		assertEquals(41.0, payment(dto, "BON").getAmount(), EPS);
		assertEquals(1, payment(dto, "ESP").getTicketsCount());
		assertEquals(1, payment(dto, "TPE").getTicketsCount(), "the two-method ticket counts under both");
		assertEquals("ESP", dto.getPayments().get(0).getCode(), "the till's order");
		double paid = dto.getPayments().stream().mapToDouble(PaymentLine::getAmount).sum();
		assertEquals(dto.getSales().getTotalTTC(), paid, 1e-6, "points are not a payment: the sum is the TTC");
	}

	@Test
	@DisplayName("Returns: simple (cash) and voucher apart, net sales = sales - every return")
	void returnsAndNet() {
		standardSession();
		close(376.72);
		SessionReportTicketDTO dto = service.build(7L);

		assertEquals(2, dto.getReturns().getCount());
		assertEquals(1, dto.getReturns().getSimpleCount());
		assertEquals(20.0, dto.getReturns().getSimpleAmount(), EPS);
		assertEquals(1, dto.getReturns().getVoucherCount());
		assertEquals(15.0, dto.getReturns().getVoucherAmount(), EPS);
		assertEquals(502.72, dto.getNetSales(), EPS);
	}

	@Test
	@DisplayName("Control on Z: the cashier's count per method, the float's rows as cash, a method not counted = 0")
	void controlZ() {
		standardSession();
		close(376.72);
		count(CounterType.POS_USER, null, 100.0); // the float
		count(CounterType.POS_USER, cash, 270.0);
		count(CounterType.POS_USER, card, 200.0);
		count(CounterType.RESPONSIBLE, card, 999.0); // not the cashier's: ignored on the control block
		SessionReportTicketDTO dto = service.build(7L);

		ControlLine cashLine = control(dto.getControl(), "ESP");
		assertEquals(376.72, cashLine.getExpected(), EPS);
		assertEquals(370.0, cashLine.getCounted(), EPS);
		assertEquals(-6.72, cashLine.getDifference(), EPS);
		ControlLine cardLine = control(dto.getControl(), "TPE");
		assertEquals(200.0, cardLine.getExpected(), EPS);
		assertEquals(0.0, cardLine.getDifference(), EPS);
		ControlLine voucherLine = control(dto.getControl(), "BON");
		assertEquals(41.0, voucherLine.getExpected(), EPS);
		assertEquals(0.0, voucherLine.getCounted(), EPS);
		assertEquals(-41.0, voucherLine.getDifference(), EPS);
		assertNull(dto.getVerification(), "CLOSED: no verification block");
	}

	@Test
	@DisplayName("A closed session keeps its stored expected cash (recomputed on a payment change), an open one computes it")
	void storedRealCash() {
		standardSession();
		close(350.0);
		assertEquals(350.0, control(service.build(7L).getControl(), "ESP").getExpected(), EPS);
		session.setStatus(SessionStatus.TERMINATED);
		assertEquals(350.0, control(service.build(7L).getControl(), "ESP").getExpected(), EPS);
		session.setStatus(SessionStatus.OPENED);
		assertEquals(376.72, control(service.build(7L).getControl(), "ESP").getExpected(), EPS);
	}

	@Test
	@DisplayName("A session closed with a total only (no count row): the total is the cash counted, the rest not declared")
	void closedWithTotalOnly() {
		standardSession();
		close(376.72);
		session.setPosUserClosureCash(380.0);
		SessionReportTicketDTO dto = service.build(7L);

		assertEquals(380.0, control(dto.getControl(), "ESP").getCounted(), EPS);
		assertEquals(3.28, control(dto.getControl(), "ESP").getDifference(), EPS);
		assertNull(control(dto.getControl(), "TPE").getCounted());
		assertNull(control(dto.getControl(), "TPE").getDifference());
	}

	@Test
	@DisplayName("Terminated: Z with the cashier's count, and the responsible's per method when RESPONSIBLE rows exist")
	void verificationPerMethod() {
		standardSession();
		close(376.72);
		session.setStatus(SessionStatus.TERMINATED);
		session.setVerifiedBy(user("resp1", "Karim Responsable"));
		session.setVerifiedAt(LocalDateTime.of(2026, 10, 14, 9, 0));
		count(CounterType.POS_USER, null, 370.0);
		count(CounterType.RESPONSIBLE, null, 376.72); // verifySession saves the cash without a method
		count(CounterType.RESPONSIBLE, card, 195.0);
		SessionReportTicketDTO dto = service.build(7L);

		assertEquals("Z", dto.getType());
		assertEquals(370.0, control(dto.getControl(), "ESP").getCounted(), EPS, "the control block is the cashier's");
		SessionReportTicketDTO.Verification verification = dto.getVerification();
		assertEquals("Karim Responsable", verification.getVerifiedBy());
		assertEquals(LocalDateTime.of(2026, 10, 14, 9, 0), verification.getVerifiedAt());
		assertTrue(verification.isPerMethod());
		assertEquals(0.0, control(verification.getLines(), "ESP").getDifference(), EPS);
		assertEquals(-5.0, control(verification.getLines(), "TPE").getDifference(), EPS);
		assertEquals(-41.0, control(verification.getLines(), "BON").getDifference(), EPS);
		assertNull(verification.getTotalCounted());
	}

	@Test
	@DisplayName("Terminated without RESPONSIBLE rows: responsibleClosureCash against every method's expected amount")
	void verificationTotal() {
		standardSession();
		close(376.72);
		session.setStatus(SessionStatus.TERMINATED);
		session.setResponsibleClosureCash(610.0);
		SessionReportTicketDTO dto = service.build(7L);

		SessionReportTicketDTO.Verification verification = dto.getVerification();
		assertFalse(verification.isPerMethod());
		assertTrue(verification.getLines().isEmpty());
		// 376.72 cash + 200 card + 41 voucher
		assertEquals(617.72, verification.getTotalExpected(), EPS);
		assertEquals(610.0, verification.getTotalCounted(), EPS);
		assertEquals(-7.72, verification.getTotalDifference(), EPS);
	}

	@Test
	@DisplayName("A payment method changed after the closing marks the ticket Z")
	void modifiedAfterClosing() {
		standardSession();
		close(376.72);
		assertFalse(service.build(7L).isModifiedAfterClosing());
		changesAfter = 2;
		SessionReportTicketDTO dto = service.build(7L);
		assertTrue(dto.isModifiedAfterClosing());
		assertEquals(2, dto.getPaymentChangesAfterClosing());
		assertEquals(session.getClosedAt(), changesAskedAfter, "changes after the closing only");
	}

	@Test
	@DisplayName("The server's free-item line stores an HT discount: the ticket counts its TTC value")
	void freeItemLineDiscount() {
		SalesHeader sale = sale(TransactionStatus.COMPLETED, 23.8);
		line(sale, 19, 23.8, 0);
		SalesLine free = line(sale, 19, 0.0, 0);
		free.setQuantity(BigDecimal.valueOf(2));
		free.setUnitPrice(10.0);
		free.setUnitPriceIncludingVat(11.9);
		free.setDiscountPercentage(100.0);
		free.setDiscountAmount(20.0); // unitPrice x freeQuantity, HT, as SalesHeaderService stores it
		pay(sale, cash, 23.8);

		assertEquals(23.8, service.build(7L).getSales().getLineDiscounts(), EPS);
	}

	@Test
	@DisplayName("HT + VAT + stamp = TTC to the millime over many uneven tickets; each rate's TTC = HT + VAT")
	void roundingAddsUp() {
		for (int i = 0; i < 60; i++) {
			double a = 3.333 + i * 1.777;
			double b = 0.999 + i * 0.413;
			double goodsTtc = a + b;
			double pct = (i % 7) * 3.5;
			double total = goodsTtc * (1 - pct / 100.0) + (i % 2 == 0 ? 1.0 : 0.0);
			SalesHeader sale = sale(TransactionStatus.COMPLETED, total);
			sale.setDiscountAmount(goodsTtc * pct / 100.0);
			line(sale, i % 3 == 0 ? 7 : 19, a, 0);
			line(sale, 13, b, 0);
			if (i % 2 == 0) {
				stamp(sale);
			}
		}
		SessionReportTicketDTO.Sales block = service.build(7L).getSales();
		assertEquals(exact(block.getTotalTTC()),
				exact(block.getTotalHT()).add(exact(block.getTotalVat())).add(exact(block.getTaxStamp())));
		BigDecimal ttcOfRates = BigDecimal.ZERO.setScale(3);
		for (VatLine v : block.getVatLines()) {
			assertEquals(exact(v.getTotalTTC()), exact(v.getBaseHT()).add(exact(v.getVatAmount())), "rate " + v.getRate());
			ttcOfRates = ttcOfRates.add(exact(v.getTotalTTC()));
		}
		assertEquals(exact(block.getTotalTTC()), ttcOfRates.add(exact(block.getTaxStamp())));
	}

	@Test
	@DisplayName("An empty open session: zeros, the opening cash expected")
	void emptySession() {
		SessionReportTicketDTO dto = service.build(7L);
		assertEquals(0, dto.getSales().getTicketsCount());
		assertTrue(dto.getSales().getVatLines().isEmpty());
		assertEquals(0.0, dto.getSales().getTotalTTC(), EPS);
		assertEquals(0.0, dto.getNetSales(), EPS);
		assertTrue(dto.getPayments().isEmpty());
		assertEquals(1, dto.getControl().size());
		assertEquals(100.0, control(dto.getControl(), "ESP").getExpected(), EPS);
	}

	@Test
	@DisplayName("An unknown session is refused (404 at the API)")
	void unknownSession() {
		assertThrows(IllegalArgumentException.class, () -> service.build(999L));
	}

	@Test
	@DisplayName("The JSON of a closed session with a ticket discount (printed for the step report)")
	void json() throws Exception {
		standardSession();
		close(376.72);
		count(CounterType.POS_USER, null, 100.0);
		count(CounterType.POS_USER, cash, 270.0);
		count(CounterType.POS_USER, card, 200.0);
		count(CounterType.POS_USER, voucher, 41.0);
		SessionReportTicketDTO dto = service.build(7L);
		dto.setPrintedAt(LocalDateTime.of(2026, 10, 13, 20, 5));
		ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(SerializationFeature.INDENT_OUTPUT);
		String json = mapper.writeValueAsString(dto);
		System.out.println("SESSION_REPORT_JSON\n" + json);
		assertTrue(json.contains("\"type\" : \"Z\""));
		assertTrue(json.contains("\"closedAt\" : \"2026-10-13 20:00:00\""));
	}
}
