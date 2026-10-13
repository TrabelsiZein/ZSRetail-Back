package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.inject;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
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
import com.digithink.zsretail.core.service.CompanyInformationService;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.ControlLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.PaymentLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.VatLine;
import com.digithink.zsretail.store.dto.StoreReportTicketDTO;
import com.digithink.zsretail.store.model.SessionCashCount;
import com.digithink.zsretail.store.repository.PaymentChangeLogRepository;
import com.digithink.zsretail.store.repository.SessionCashCountRepository;

/**
 * 3.1.0: the store ticket X / Z (StoreReportService): the real SessionReportService (and CashierSessionService for an
 * open session's expected cash) over several sessions in memory; the session repository stub applies the rules of its
 * two queries. No Spring.
 */
class StoreReportServiceTest {

	private static final double EPS = 1e-9;
	private static final LocalDate DAY = LocalDate.of(2026, 10, 13);

	private final PaymentMethod cash = method(1L, "ESP", "Espèces", PaymentMethodType.CLIENT_ESPECES, 1);
	private final PaymentMethod card = method(2L, "TPE", "Carte bancaire", PaymentMethodType.CLIENT_TPE, 2);
	private final Item goods = item("ART001");
	private final Item stampItem = item("TAX_STAMP");

	private final List<CashierSession> sessions = new ArrayList<>();
	private final List<SalesHeader> sales = new ArrayList<>();
	private final List<SalesLine> lines = new ArrayList<>();
	private final List<Payment> payments = new ArrayList<>();
	private final List<ReturnHeader> returns = new ArrayList<>();
	private final List<SessionCashCount> counts = new ArrayList<>();
	/** The sessions with payment method changes after their closing (PaymentChangeLog). */
	private final List<CashierSession> modifiedAfterClosing = new ArrayList<>();
	private long nextId = 1;

	private StoreReportService service;

	@BeforeEach
	void setUp() {
		CashierSessionRepository sessionRepo = proxy(CashierSessionRepository.class, (m, a) -> {
			switch (m) {
				case "findById":
					return sessions.stream().filter(s -> s.getId().equals(a[0])).findFirst();
				case "findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByOpenedAtAsc": {
					List<?> statuses = (List<?>) a[0];
					LocalDateTime from = (LocalDateTime) a[1];
					LocalDateTime to = (LocalDateTime) a[2];
					return byOpening(s -> statuses.contains(s.getStatus()) && s.getClosedAt() != null
							&& !s.getClosedAt().isBefore(from) && s.getClosedAt().isBefore(to));
				}
				case "findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrStatusOrderByOpenedAtAsc": {
					List<?> statuses = (List<?>) a[0];
					LocalDateTime from = (LocalDateTime) a[1];
					LocalDateTime to = (LocalDateTime) a[2];
					return byOpening(s -> (statuses.contains(s.getStatus()) && s.getClosedAt() != null
							&& !s.getClosedAt().isBefore(from) && s.getClosedAt().isBefore(to)) || s.getStatus() == a[3]);
				}
				default:
					return UNHANDLED;
			}
		});
		SalesHeaderRepository salesRepo = proxy(SalesHeaderRepository.class, (m, a) -> "findByCashierSession".equals(m)
				? sales.stream().filter(s -> s.getCashierSession() == a[0]).collect(Collectors.toList())
				: UNHANDLED);
		PaymentRepository paymentRepo = proxy(PaymentRepository.class, (m, a) -> {
			if ("findByCashierSession".equals(m)) {
				List<?> excluded = (List<?>) a[1];
				return payments.stream().filter(p -> p.getSalesHeader().getCashierSession() == a[0]
						&& !excluded.contains(p.getSalesHeader().getStatus())).collect(Collectors.toList());
			}
			if ("findBySalesHeader".equals(m)) {
				return payments.stream().filter(p -> p.getSalesHeader() == a[0]).collect(Collectors.toList());
			}
			return UNHANDLED;
		});
		ReturnHeaderRepository returnRepo = proxy(ReturnHeaderRepository.class, (m, a) -> "findByCashierSession".equals(m)
				? returns.stream().filter(r -> r.getCashierSession() == a[0]).collect(Collectors.toList())
				: UNHANDLED);
		PaymentMethodRepository methodRepo = proxy(PaymentMethodRepository.class, (m, a) -> {
			if ("findByType".equals(m) && a[0] == PaymentMethodType.CLIENT_ESPECES) return Optional.of(cash);
			if ("findAll".equals(m)) return Arrays.asList(card, cash); // not in the till's order: the service sorts
			return UNHANDLED;
		});
		CurrentUserProvider user = new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return user("resp1", "Karim Responsable");
			}
		};

		CashierSessionService sessionService = new CashierSessionService();
		inject(sessionService, "cashierSessionRepository", sessionRepo);
		inject(sessionService, "salesHeaderRepository", salesRepo);
		inject(sessionService, "paymentRepository", paymentRepo);
		inject(sessionService, "returnHeaderRepository", returnRepo);

		SessionReportService sessionReports = new SessionReportService();
		inject(sessionReports, "cashierSessionRepository", sessionRepo);
		inject(sessionReports, "cashierSessionService", sessionService);
		inject(sessionReports, "salesHeaderRepository", salesRepo);
		inject(sessionReports, "paymentRepository", paymentRepo);
		inject(sessionReports, "returnHeaderRepository", returnRepo);
		inject(sessionReports, "paymentMethodRepository", methodRepo);
		inject(sessionReports, "currentUserProvider", user);
		inject(sessionReports, "salesLineRepository", proxy(SalesLineRepository.class, (m, a) -> {
			if (!"findByCashierSession".equals(m)) return UNHANDLED;
			List<?> excluded = (List<?>) a[1];
			return lines.stream().filter(l -> l.getSalesHeader().getCashierSession() == a[0]
					&& !excluded.contains(l.getSalesHeader().getStatus())).collect(Collectors.toList());
		}));
		inject(sessionReports, "sessionCashCountRepository", proxy(SessionCashCountRepository.class,
				(m, a) -> "findByCashierSession".equals(m)
						? counts.stream().filter(c -> c.getCashierSession() == a[0]).collect(Collectors.toList())
						: UNHANDLED));
		inject(sessionReports, "paymentChangeLogRepository", proxy(PaymentChangeLogRepository.class,
				(m, a) -> "countBySalesHeaderCashierSessionAndCreatedAtAfter".equals(m)
						? (modifiedAfterClosing.contains(a[0]) ? 2L : 0L)
						: UNHANDLED));

		service = new StoreReportService();
		inject(service, "cashierSessionRepository", sessionRepo);
		inject(service, "sessionReportService", sessionReports);
		inject(service, "paymentMethodRepository", methodRepo);
		inject(service, "currentUserProvider", user);
		inject(service, "companyInformationService", new CompanyInformationService(null) {
			@Override
			public CompanyInformationDTO get() {
				CompanyInformationDTO info = new CompanyInformationDTO();
				info.setCompanyName("PARA FENDRI");
				return info;
			}
		});
		service.clock = Clock.fixed(DAY.atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant(),
				ZoneId.systemDefault());
	}

	private List<CashierSession> byOpening(java.util.function.Predicate<CashierSession> rule) {
		return sessions.stream().filter(rule).sorted(Comparator.comparing(CashierSession::getOpenedAt))
				.collect(Collectors.toList());
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

	/** A session; closedAt null: still open. A closed session's realCash is the expected cash stored at its closing. */
	private CashierSession session(String number, LocalDateTime openedAt, LocalDateTime closedAt, double openingCash,
			Double realCash) {
		CashierSession session = new CashierSession();
		session.setId(nextId++);
		session.setSessionNumber(number);
		session.setCashier(user("cashier" + session.getId(), "Caissier " + session.getId()));
		session.setOpenedAt(openedAt);
		session.setClosedAt(closedAt);
		session.setStatus(closedAt == null ? SessionStatus.OPENED : SessionStatus.CLOSED);
		session.setOpeningCash(openingCash);
		session.setRealCash(realCash);
		sessions.add(session);
		return session;
	}

	/** A completed ticket of one goods line at a VAT rate, paid with one method. */
	private SalesHeader sale(CashierSession session, int rate, double ttc, PaymentMethod method) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setSalesNumber("S" + sale.getId());
		sale.setCashierSession(session);
		sale.setStatus(TransactionStatus.COMPLETED);
		sale.setTotalAmount(ttc);
		sale.setDiscountAmount(0.0);
		sale.setChangeAmount(0.0);
		sales.add(sale);
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setSalesHeader(sale);
		line.setItem(goods);
		line.setQuantity(BigDecimal.ONE);
		line.setVatPercent(rate);
		line.setLineTotalIncludingVat(ttc);
		line.setUnitPriceIncludingVat(ttc);
		line.setLineTotal(ttc / (1 + rate / 100.0));
		line.setVatAmount(ttc - line.getLineTotal());
		lines.add(line);
		if (method != null) {
			Payment payment = new Payment();
			payment.setId(nextId++);
			payment.setSalesHeader(sale);
			payment.setPaymentMethod(method);
			payment.setTotalAmount(ttc);
			payments.add(payment);
		}
		return sale;
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

	private void count(CashierSession session, PaymentMethod method, double amount) {
		SessionCashCount count = new SessionCashCount();
		count.setCashierSession(session);
		count.setCounterType(CounterType.POS_USER);
		count.setPaymentMethod(method);
		count.setDenominationValue(amount);
		count.setQuantity(1);
		count.setLineTotal(amount);
		counts.add(count);
	}

	/**
	 * Two sessions closed on DAY. S1: opening 100, one ticket 119 at 19 % in cash, expected cash 219, counted 219. S2:
	 * opening 50, a ticket 107 at 7 % by card and a ticket 59.5 at 19 % in cash, expected cash 109.5, counted 100 in
	 * cash and 107 by card; a simple return of 10 (so expected cash 99.5).
	 */
	private CashierSession[] twoClosedSessions() {
		CashierSession s1 = session("S1", DAY.atTime(8, 0), DAY.atTime(12, 0), 100.0, 219.0);
		sale(s1, 19, 119.0, cash);
		count(s1, null, 100.0);
		count(s1, cash, 119.0);
		CashierSession s2 = session("S2", DAY.atTime(12, 30), DAY.atTime(20, 0), 50.0, 99.5);
		sale(s2, 7, 107.0, card);
		sale(s2, 19, 59.5, cash);
		ReturnHeader ret = new ReturnHeader();
		ret.setId(nextId++);
		ret.setCashierSession(s2);
		ret.setReturnType(ReturnType.SIMPLE_RETURN);
		ret.setTotalReturnAmount(10.0);
		returns.add(ret);
		count(s2, cash, 100.0);
		count(s2, card, 107.0);
		return new CashierSession[] { s1, s2 };
	}

	private static ControlLine control(StoreReportTicketDTO dto, String code) {
		return dto.getControl().stream().filter(c -> code.equals(c.getCode())).findFirst().orElse(null);
	}

	private static PaymentLine payment(StoreReportTicketDTO dto, String code) {
		return dto.getPayments().stream().filter(p -> code.equals(p.getCode())).findFirst().orElse(null);
	}

	private static BigDecimal exact(Double value) {
		return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP);
	}

	// ─── Tests ───────────────────────────────────────────────────────

	@Test
	@DisplayName("Z sums two sessions closed the same day: sales, returns, payments, control and the float")
	void zSumsTwoClosedSessions() {
		CashierSession[] two = twoClosedSessions();
		StoreReportTicketDTO dto = service.buildZ(DAY);

		assertEquals("Z", dto.getType());
		assertEquals(DAY, dto.getDate());
		assertEquals("PARA FENDRI", dto.getStoreName());
		assertEquals(Arrays.asList("S1", "S2"),
				dto.getSessions().stream().map(StoreReportTicketDTO.SessionRow::getSessionNumber).collect(Collectors.toList()));
		// the rows: id (the page opens the session's details with it), cashier, times, status
		StoreReportTicketDTO.SessionRow first = dto.getSessions().get(0);
		assertEquals(two[0].getId(), first.getId());
		assertEquals(two[1].getId(), dto.getSessions().get(1).getId());
		assertEquals(two[0].getCashier().getFullName(), first.getCashierName());
		assertEquals(DAY.atTime(8, 0), first.getOpenedAt());
		assertEquals(DAY.atTime(12, 0), first.getClosedAt());
		assertEquals(SessionStatus.CLOSED, first.getStatus());
		assertEquals(3, dto.getSales().getTicketsCount());
		assertEquals(285.5, dto.getSales().getTotalTTC(), EPS);
		assertNull(dto.getSales().getPendingCount(), "no pending line on a Z");
		assertEquals(1, dto.getReturns().getCount());
		assertEquals(10.0, dto.getReturns().getSimpleAmount(), EPS);
		assertEquals(275.5, dto.getNetSales(), EPS);
		assertEquals(178.5, payment(dto, "ESP").getAmount(), EPS);
		assertEquals(2, payment(dto, "ESP").getTicketsCount());
		assertEquals(107.0, payment(dto, "TPE").getAmount(), EPS);
		assertEquals("ESP", dto.getPayments().get(0).getCode(), "the till's order");
		assertEquals(150.0, dto.getOpeningCash(), EPS, "the float of both sessions");
		// cash: expected 219 + 99.5, counted 219 + 100
		ControlLine cashLine = control(dto, "ESP");
		assertEquals(318.5, cashLine.getExpected(), EPS);
		assertEquals(319.0, cashLine.getCounted(), EPS);
		assertEquals(0.5, cashLine.getDifference(), EPS);
		ControlLine cardLine = control(dto, "TPE");
		assertEquals(107.0, cardLine.getExpected(), EPS);
		assertEquals(0.0, cardLine.getDifference(), EPS);
		assertEquals("Karim Responsable", dto.getPrintedBy());
		assertNotNull(dto.getPrintedAt());
	}

	@Test
	@DisplayName("VAT lines of two sessions at the same rate become one line; HT + VAT + stamp = TTC")
	void vatLinesMerged() {
		twoClosedSessions();
		stamp(sales.get(0)); // a stamp on S1's ticket: its total becomes 120
		sales.get(0).setTotalAmount(120.0);
		payments.get(0).setTotalAmount(120.0);
		StoreReportTicketDTO dto = service.buildZ(DAY);

		List<VatLine> vat = dto.getSales().getVatLines();
		assertEquals(2, vat.size(), "7 % and 19 % once each: " + vat);
		VatLine v19 = vat.stream().filter(v -> v.getRate() == 19).findFirst().get();
		assertEquals(178.5, v19.getTotalTTC(), EPS); // 119 (S1) + 59.5 (S2)
		assertEquals(150.0, v19.getBaseHT(), EPS);
		assertEquals(28.5, v19.getVatAmount(), EPS);
		VatLine v7 = vat.stream().filter(v -> v.getRate() == 7).findFirst().get();
		assertEquals(107.0, v7.getTotalTTC(), EPS);
		assertEquals(1.0, dto.getSales().getTaxStamp(), EPS);
		assertEquals(exact(dto.getSales().getTotalTTC()), exact(dto.getSales().getTotalHT())
				.add(exact(dto.getSales().getTotalVat())).add(exact(dto.getSales().getTaxStamp())));
	}

	@Test
	@DisplayName("Z ignores a session closed another day and a session still open")
	void zIgnoresOtherDayAndOpen() {
		twoClosedSessions();
		CashierSession yesterday = session("S0", DAY.minusDays(1).atTime(8, 0), DAY.minusDays(1).atTime(19, 0), 80.0,
				80.0);
		sale(yesterday, 19, 500.0, cash);
		CashierSession open = session("S3", DAY.atTime(14, 0), null, 30.0, null);
		sale(open, 19, 40.0, cash);
		StoreReportTicketDTO dto = service.buildZ(DAY);

		assertEquals(Arrays.asList("S1", "S2"),
				dto.getSessions().stream().map(StoreReportTicketDTO.SessionRow::getSessionNumber).collect(Collectors.toList()));
		assertEquals(285.5, dto.getSales().getTotalTTC(), EPS);
	}

	@Test
	@DisplayName("A session opened yesterday and closed today counts in today's Z, not in yesterday's")
	void openedYesterdayClosedToday() {
		CashierSession night = session("N1", DAY.minusDays(1).atTime(22, 0), DAY.atTime(1, 30), 20.0, 70.0);
		sale(night, 19, 50.0, cash);
		count(night, null, 70.0);

		StoreReportTicketDTO dto = service.buildZ(DAY);
		assertEquals("N1", dto.getSessions().get(0).getSessionNumber());
		assertEquals(50.0, dto.getSales().getTotalTTC(), EPS);
		assertThrows(IllegalArgumentException.class, () -> service.buildZ(DAY.minusDays(1)));
	}

	@Test
	@DisplayName("A terminated session counts in the Z of its closing day; the day's end is excluded")
	void terminatedAndDayBounds() {
		CashierSession verified = session("T1", DAY.atTime(8, 0), DAY.atTime(23, 59, 59), 0.0, 10.0);
		verified.setStatus(SessionStatus.TERMINATED);
		sale(verified, 19, 10.0, cash);
		CashierSession midnight = session("M1", DAY.atTime(18, 0), DAY.plusDays(1).atStartOfDay(), 0.0, 5.0);
		sale(midnight, 19, 5.0, cash);

		StoreReportTicketDTO dto = service.buildZ(DAY);
		assertEquals(1, dto.getSessions().size());
		assertEquals("T1", dto.getSessions().get(0).getSessionNumber());
		assertEquals("M1", service.buildZ(DAY.plusDays(1)).getSessions().get(0).getSessionNumber());
	}

	@Test
	@DisplayName("Z of a day without a closed session: refused (404 at the API) with the date in the message")
	void zWithoutSession() {
		session("S3", DAY.atTime(14, 0), null, 30.0, null);
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.buildZ(DAY));
		assertTrue(e.getMessage().contains("2026-10-13"), e.getMessage());
	}

	@Test
	@DisplayName("X: the sessions closed today and the ones open now, an open one read as a session X, counted null")
	void xIncludesOpenSession() {
		twoClosedSessions();
		CashierSession open = session("S3", DAY.atTime(14, 0), null, 30.0, null);
		sale(open, 19, 40.0, cash);
		SalesHeader parked = sale(open, 19, 25.0, null);
		parked.setStatus(TransactionStatus.PENDING);
		CashierSession stillOpen = session("S9", DAY.minusDays(2).atTime(9, 0), null, 0.0, null);
		sale(stillOpen, 7, 21.4, card);
		session("S0", DAY.minusDays(1).atTime(8, 0), DAY.minusDays(1).atTime(19, 0), 80.0, 80.0);

		StoreReportTicketDTO dto = service.buildX();
		assertEquals("X", dto.getType());
		assertEquals(DAY, dto.getDate());
		assertEquals(Arrays.asList("S9", "S1", "S2", "S3"),
				dto.getSessions().stream().map(StoreReportTicketDTO.SessionRow::getSessionNumber).collect(Collectors.toList()),
				"closed today (S1, S2), or open now (S3, and S9 since two days); not yesterday's closed S0");
		assertNull(dto.getModifiedAfterClosingCount(), "Z only");
		assertEquals(5, dto.getSales().getTicketsCount());
		assertEquals(1L, dto.getSales().getPendingCount());
		// cash expected: 219 + 99.5 (stored) + S3's computed 30 + 40
		ControlLine cashLine = control(dto, "ESP");
		assertEquals(388.5, cashLine.getExpected(), EPS);
		dto.getControl().forEach(c -> {
			assertNull(c.getCounted(), c.getCode());
			assertNull(c.getDifference(), c.getCode());
		});
		assertEquals(180.0, dto.getOpeningCash(), EPS);
	}

	@Test
	@DisplayName("A session opened yesterday and closed today is in today's X, as in today's Z: same sessions")
	void openedYesterdayClosedTodayInX() {
		CashierSession night = session("N1", DAY.minusDays(1).atTime(22, 0), DAY.atTime(1, 30), 20.0, 70.0);
		sale(night, 19, 50.0, cash);
		twoClosedSessions();

		StoreReportTicketDTO x = service.buildX();
		StoreReportTicketDTO z = service.buildZ(DAY);
		List<String> xSessions = x.getSessions().stream().map(StoreReportTicketDTO.SessionRow::getSessionNumber)
				.collect(Collectors.toList());
		assertEquals(Arrays.asList("N1", "S1", "S2"), xSessions);
		assertEquals(xSessions, z.getSessions().stream().map(StoreReportTicketDTO.SessionRow::getSessionNumber)
				.collect(Collectors.toList()), "after the last closing, X and Z hold the same sessions");
		assertEquals(z.getSales().getTotalTTC(), x.getSales().getTotalTTC(), EPS);
	}

	@Test
	@DisplayName("Z: modifiedAfterClosingCount = the sessions with a payment method changed after their closing")
	void modifiedAfterClosingCount() {
		CashierSession[] two = twoClosedSessions();
		assertEquals(0L, service.buildZ(DAY).getModifiedAfterClosingCount());
		modifiedAfterClosing.add(two[1]);
		assertEquals(1L, service.buildZ(DAY).getModifiedAfterClosingCount());
		modifiedAfterClosing.add(two[0]);
		assertEquals(2L, service.buildZ(DAY).getModifiedAfterClosingCount());
	}

	@Test
	@DisplayName("X without any session closed today nor open: refused")
	void xWithoutSession() {
		session("S0", DAY.minusDays(1).atTime(8, 0), DAY.minusDays(1).atTime(19, 0), 80.0, 80.0);
		assertThrows(IllegalArgumentException.class, () -> service.buildX());
	}

	@Test
	@DisplayName("Z: a session closed with a total only declares no card: the card's counted stays null")
	void undeclaredMethodStaysNull() {
		twoClosedSessions();
		CashierSession legacy = session("L1", DAY.atTime(9, 0), DAY.atTime(10, 0), 0.0, 0.0);
		sale(legacy, 7, 10.7, card);
		legacy.setPosUserClosureCash(0.0); // no count row: the total is the cash counted

		StoreReportTicketDTO dto = service.buildZ(DAY);
		assertEquals(117.7, control(dto, "TPE").getExpected(), EPS);
		assertNull(control(dto, "TPE").getCounted(), "L1 declared no card");
		assertNull(control(dto, "TPE").getDifference());
		assertEquals(319.0, control(dto, "ESP").getCounted(), EPS, "the cash: 219 + 100 + 0");
	}
}
