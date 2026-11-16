package com.digithink.zsretail.store.livecash;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.GeneralSetupRepository;
import com.digithink.zsretail.core.repository.PaymentRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.model.CashMovementType;
import com.digithink.zsretail.store.cashmovement.repository.CashMovementRepository;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.service.CashDrawerSetting;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;
import com.digithink.zsretail.store.service.CashierSessionService;
import com.digithink.zsretail.store.service.SessionAmounts;
import com.digithink.zsretail.store.support.InMemoryExpectedCash;
import com.digithink.zsretail.store.support.InMemorySessionFigures;

/**
 * 3.2.0, "Caisse en direct" (LiveCashService, GET /cashier-session/live) over in-memory tables, 2026-11-16 at 15:00.
 * <ul>
 * <li>Caisse 1: Amira's open session S1, opened with 100: T1 50 in cash (60 given, 10 of change), T2 30 by card (a manual
 * discount of 3), T3 35 split 20 cash + 15 card, T4 40 cash CANCELLED, T5 PENDING; a simple return of 12 and a voucher
 * return of 8; an apport of 25, a prélèvement of 40, a dépense of 7.5; one refused badge scan. Expected cash: 100 + 60 +
 * 20 - 10 - 12 + 25 - 40 - 7.5 = 135.5.</li>
 * <li>Caisse 2: its latest session S2, fermée yesterday, not clôturée.</li>
 * <li>Caisse 3: its latest session S4, clôturée this morning (a ticket of 20 today): no session.</li>
 * <li>Caisse 4, inactive, no session: not shown.</li>
 * <li>Bilel's open session S3 without POS machine, opened with 50, one ticket of 10 in cash: expected 60.</li>
 * </ul>
 */
class LiveCashTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 11, 16);

	private final UserAccount amira = user(1L, "Amira");
	private final UserAccount bilel = user(2L, "Bilel");
	private final PaymentMethod cash = method(1L, "ESPECE", PaymentMethodType.CLIENT_ESPECES);
	private final PaymentMethod card = method(2L, "TPE", PaymentMethodType.CLIENT_TPE);
	private final PosMachine m1 = machine(10L, "Caisse 1", true);
	private final PosMachine m2 = machine(20L, "Caisse 2", true);
	private final PosMachine m3 = machine(30L, "Caisse 3", true);
	private final PosMachine m4 = machine(40L, "Caisse 4", false);
	private final List<PosMachine> machines = Arrays.asList(m1, m2, m3, m4);
	private final List<Payment> payments = new ArrayList<>();

	private InMemorySessionFigures db;
	private CashierSessionService sessions;
	private LiveCashService service;
	private CashierSession s1;
	private CashierSession s3;
	private long nextId = 100;

	@BeforeEach
	void setUp() throws Exception {
		db = new InMemorySessionFigures();
		CashierSession s2 = session(m2, amira, SessionStatus.CLOSED, TODAY.minusDays(1).atTime(9, 0), 0.0);
		s2.setClosedAt(TODAY.minusDays(1).atTime(18, 30));
		CashierSession s4 = session(m3, bilel, SessionStatus.TERMINATED, TODAY.atTime(7, 0), 0.0);
		s4.setClosedAt(TODAY.atTime(9, 0));
		sale(s4, TransactionStatus.COMPLETED, 20.0, 0.0, TODAY.atTime(8, 0), cash, 20.0);
		s1 = session(m1, amira, SessionStatus.OPENED, TODAY.atTime(9, 30), 100.0);
		sale(s1, TransactionStatus.COMPLETED, 50.0, 10.0, TODAY.atTime(10, 0), cash, 60.0);
		SalesHeader t2 = sale(s1, TransactionStatus.COMPLETED, 30.0, 0.0, TODAY.atTime(11, 0), card, 30.0);
		t2.setDiscountAmount(3.0);
		t2.setDiscountSource("MANUAL");
		SalesHeader t3 = sale(s1, TransactionStatus.COMPLETED, 35.0, 0.0, TODAY.atTime(14, 10), cash, 20.0);
		pay(t3, card, 15.0);
		sale(s1, TransactionStatus.CANCELLED, 40.0, 0.0, TODAY.atTime(14, 20), cash, 40.0);
		sale(s1, TransactionStatus.PENDING, 9.0, 0.0, TODAY.atTime(14, 50), null, null);
		giveBack(s1, ReturnType.SIMPLE_RETURN, 12.0);
		giveBack(s1, ReturnType.RETURN_VOUCHER, 8.0);
		move(s1, CashMovementType.APPORT, 25.0);
		move(s1, CashMovementType.PRELEVEMENT, 40.0);
		move(s1, CashMovementType.DEPENSE, 7.5);
		BadgeScanLog refused = new BadgeScanLog();
		refused.setSessionId(s1.getId());
		refused.setSuccess(false);
		db.badgeLogs.add(refused);
		s3 = session(null, bilel, SessionStatus.OPENED, TODAY.atTime(12, 0), 50.0);
		sale(s3, TransactionStatus.COMPLETED, 10.0, 0.0, TODAY.atTime(12, 30), cash, 10.0);

		SalesHeaderRepository salesRepo = proxy(SalesHeaderRepository.class, (m, a) -> "findByCashierSession".equals(m)
				? db.sales.stream().filter(s -> sameSession(s.getCashierSession(), a[0])).collect(Collectors.toList())
				: UNHANDLED);
		PaymentRepository paymentRepo = proxy(PaymentRepository.class, (m, a) -> "findBySalesHeader".equals(m)
				? payments.stream().filter(p -> p.getSalesHeader() == a[0]).collect(Collectors.toList())
				: UNHANDLED);
		ReturnHeaderRepository returnRepo = proxy(ReturnHeaderRepository.class, (m, a) -> "findByCashierSession".equals(m)
				? db.returns.stream().filter(r -> sameSession(r.getCashierSession(), a[0])).collect(Collectors.toList())
				: UNHANDLED);
		// the closing's own reading of the movements (cashMovementNet), one session at a time
		CashMovementRepository movementRepo = proxy(CashMovementRepository.class,
				(m, a) -> "findByCashierSessionOrderByMovedAtAsc".equals(m)
						? db.movements.stream().filter(mv -> mv.getCashierSession() == a[0]).collect(Collectors.toList())
						: UNHANDLED);
		sessions = new CashierSessionService() {
			@Override
			public Optional<CashierSession> findById(Long id) {
				return db.sessions.stream().filter(s -> s.getId().equals(id)).findFirst();
			}
		};
		set(sessions, CashierSessionService.class, "expectedCashSums",
				InMemoryExpectedCash.over(salesRepo, paymentRepo, returnRepo));
		set(sessions, CashierSessionService.class, "cashMovementRepository", movementRepo);

		GeneralSetupRepository setups = proxy(GeneralSetupRepository.class, (m, a) -> {
			if (!"findByCode".equals(m)) {
				return UNHANDLED;
			}
			GeneralSetup owner = new GeneralSetup();
			owner.setValeur("CASHIER");
			return Optional.of(owner);
		});
		service = new LiveCashService();
		set(service, LiveCashService.class, "live", liveRepository());
		set(service, LiveCashService.class, "sessionFigures", db.service);
		set(service, LiveCashService.class, "watchRepository", db.watchRepository);
		set(service, LiveCashService.class, "cashierSessions", sessions);
		set(service, LiveCashService.class, "sessionPosMachines",
				new SessionPosMachineService(null, null, null, new CashDrawerSetting(setups)));
		set(service, LiveCashService.class, "clock",
				Clock.fixed(TODAY.atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));
	}

	@Test
	@DisplayName("An open session with cash sales, a return, an entrée, a prélèvement and a dépense: the expected cash is"
			+ " what the closing computes (135.5), with the session's numbers and 'À surveiller'")
	void openSessionAsTheClosing() {
		LiveCashDTO live = service.read(true);
		LiveCashDTO.OpenSession o = drawer(live, "Caisse 1").getSessions().get(0);
		assertEquals(135.5, o.getExpectedCash(), 1e-9);
		assertEquals(sessions.getExpectedCash(s1.getId()), o.getExpectedCash(), 1e-9,
				"the closing's computation (calculateRealCash, its own reading of the movements)");
		assertEquals("Amira", o.getCashierName());
		assertEquals(TODAY.atTime(9, 30), o.getOpenedAt());
		assertEquals(100.0, o.getOpeningCash(), 1e-9);
		assertEquals(3, o.getTickets(), "neither PENDING nor CANCELLED");
		assertEquals(115.0, o.getSales(), 1e-9);
		assertEquals(TODAY.atTime(14, 10), o.getLastTicketAt(), "the cancelled and the pending ticket do not count");
		assertEquals(1, o.getApportCount());
		assertEquals(25.0, o.getApportTotal(), 1e-9);
		assertEquals(1, o.getWithdrawalCount());
		assertEquals(40.0, o.getWithdrawalTotal(), 1e-9);
		assertEquals(1, o.getExpenseCount());
		assertEquals(7.5, o.getExpenseTotal(), 1e-9);
		LiveCashDTO.Watch w = o.getWatch();
		assertEquals(1, w.getCancelledCount());
		assertEquals(40.0, w.getCancelledAmount(), 1e-9);
		assertEquals(2, w.getReturnsCount());
		assertEquals(20.0, w.getReturnsAmount(), 1e-9);
		assertEquals(1, w.getDiscountTickets());
		assertEquals(1, w.getManualDiscountTickets());
		assertEquals(3.0, w.getDiscountAmount(), 1e-9);
		assertEquals(1, w.getRefusedAuthorisations());

		LiveCashDTO.Day day = live.getDay();
		assertEquals(2, day.getOpenSessions());
		assertEquals(5, day.getTickets(), "the Journal's day: S1's 3, S3's 1 and the clôturée S4's ticket of this morning");
		assertEquals(145.0, day.getSales(), 1e-9);
		assertEquals(195.5, day.getExpectedCash(), 1e-9, "135.5 + 60, the open drawers");
		assertFalse(live.isAmountsHidden());
		assertEquals("CASHIER", live.getDrawerOwner());
	}

	@Test
	@DisplayName("The drawers: each active POS machine (OPEN, WAITING since yesterday, NONE after a clôture), the inactive"
			+ " one hidden, then the open session without POS machine")
	void drawers() {
		LiveCashDTO live = service.read(true);
		assertEquals(Arrays.asList("Caisse 1", "Caisse 2", "Caisse 3", null),
				live.getDrawers().stream().map(LiveCashDTO.Drawer::getPosMachineName).collect(Collectors.toList()));
		assertEquals("OPEN", drawer(live, "Caisse 1").getState());
		assertNull(drawer(live, "Caisse 1").getWaiting());
		LiveCashDTO.Drawer waiting = drawer(live, "Caisse 2");
		assertEquals("WAITING", waiting.getState());
		assertTrue(waiting.getSessions().isEmpty());
		assertEquals(TODAY.minusDays(1).atTime(18, 30), waiting.getWaiting().getClosedAt(), "fermée yesterday, still shown");
		assertNotNull(waiting.getWaiting().getSessionNumber());
		assertEquals("Amira", waiting.getWaiting().getCashierName());
		LiveCashDTO.Drawer none = drawer(live, "Caisse 3");
		assertEquals("NONE", none.getState());
		assertNull(none.getWaiting());
		assertTrue(none.getSessions().isEmpty());
		LiveCashDTO.Drawer unassigned = live.getDrawers().get(3);
		assertNull(unassigned.getPosMachineId());
		assertEquals("OPEN", unassigned.getState());
		assertEquals("Bilel", unassigned.getSessions().get(0).getCashierName());
		assertEquals(60.0, unassigned.getSessions().get(0).getExpectedCash(), 1e-9);
	}

	@Test
	@DisplayName("Without 'Voir montants session': no amount at all (not sent, not summed), every count kept")
	void withoutTheAmountsRight() {
		LiveCashDTO live = service.read(false);
		assertTrue(live.isAmountsHidden());
		assertNull(live.getDay().getSales());
		assertNull(live.getDay().getExpectedCash());
		assertEquals(5, live.getDay().getTickets());
		assertEquals(2, live.getDay().getOpenSessions());
		for (LiveCashDTO.Drawer drawer : live.getDrawers()) {
			for (LiveCashDTO.OpenSession o : drawer.getSessions()) {
				assertNull(o.getExpectedCash());
				assertNull(o.getOpeningCash());
				assertNull(o.getSales());
				assertNull(o.getApportTotal());
				assertNull(o.getWithdrawalTotal());
				assertNull(o.getExpenseTotal());
				assertNull(o.getWatch().getCancelledAmount());
				assertNull(o.getWatch().getReturnsAmount());
				assertNull(o.getWatch().getDiscountAmount());
				assertNull(o.getWatch().getManualDiscountAmount());
			}
		}
		LiveCashDTO.OpenSession o = drawer(live, "Caisse 1").getSessions().get(0);
		assertEquals(3, o.getTickets());
		assertEquals(1, o.getApportCount());
		assertEquals(2, o.getWatch().getReturnsCount());
		assertEquals(1, o.getWatch().getCancelledCount());
		assertEquals(1, o.getWatch().getRefusedAuthorisations());
	}

	@Test
	@DisplayName("GET /cashier-session/live: 403 without read:admin-sessions-history, 200 with it (amounts per the right)")
	void endpointRights() throws Exception {
		assertEquals(403, api("read:view-session-amounts", "read:day-journal").read().getStatusCodeValue());
		ResponseEntity<?> blind = api("read:admin-sessions-history").read();
		assertEquals(200, blind.getStatusCodeValue());
		assertTrue(((LiveCashDTO) blind.getBody()).isAmountsHidden());
		ResponseEntity<?> full = api("read:admin-sessions-history", SessionAmounts.PERMISSION).read();
		assertEquals(200, full.getStatusCodeValue());
		assertEquals(195.5, ((LiveCashDTO) full.getBody()).getDay().getExpectedCash(), 1e-9);
	}

	@Test
	@DisplayName("No open session: the day's tickets, zeros elsewhere, the POS machines shown")
	void nothingOpen() {
		db.sessions.forEach(s -> {
			if (s.getStatus() == SessionStatus.OPENED) {
				s.setStatus(SessionStatus.TERMINATED);
			}
		});
		LiveCashDTO live = service.read(true);
		assertEquals(0, live.getDay().getOpenSessions());
		assertEquals(0.0, live.getDay().getExpectedCash(), 1e-9);
		assertEquals(3, live.getDrawers().size(), "the three active machines, no open session without machine");
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	/** LiveCashRepository's reads, as their JPQL reads. */
	private LiveCashRepository liveRepository() {
		return proxy(LiveCashRepository.class, (m, a) -> {
			switch (m) {
				case "openSessions":
					return db.sessions.stream().filter(s -> s.getStatus() == a[0])
							.sorted(Comparator.comparing(CashierSession::getOpenedAt).thenComparing(CashierSession::getId))
							.map(s -> new Object[] { s.getId(), s.getSessionNumber(), s.getOpenedAt(), s.getOpeningCash(),
									s.getCashier().getId(), s.getCashier().getFullName(), s.getCashier().getUsername() })
							.collect(Collectors.toList());
				case "machines":
					return machines.stream().filter(pm -> Boolean.TRUE.equals(pm.getActive())
							|| db.sessions.stream().anyMatch(s -> s.getStatus() == SessionStatus.OPENED
									&& db.machineOf.get(s.getId()) == pm))
							.sorted(Comparator.comparing(PosMachine::getName))
							.map(pm -> new Object[] { pm.getId(), pm.getName(), pm.getActive() }).collect(Collectors.toList());
				case "machineOf": {
					Collection<?> ids = (Collection<?>) a[0];
					return db.machineOf.entrySet().stream().filter(e -> ids.contains(e.getKey()))
							.map(e -> new Object[] { e.getKey(), e.getValue().getId() }).collect(Collectors.toList());
				}
				case "latestSessionOfEachMachine": {
					List<Object[]> rows = new ArrayList<>();
					for (PosMachine pm : machines) {
						db.sessions.stream().filter(s -> db.machineOf.get(s.getId()) == pm)
								.max(Comparator.comparing(CashierSession::getId))
								.ifPresent(s -> rows.add(new Object[] { pm.getId(), s.getId(), s.getSessionNumber(),
										s.getStatus(), s.getClosedAt(), s.getCashier().getFullName(),
										s.getCashier().getUsername() }));
					}
					return rows;
				}
				case "lastTicketTimes": {
					Collection<?> ids = (Collection<?>) a[0];
					Collection<?> excluded = (Collection<?>) a[1];
					List<Object[]> rows = new ArrayList<>();
					for (Object id : ids) {
						db.sales.stream().filter(s -> s.getCashierSession().getId().equals(id) && !excluded.contains(s.getStatus()))
								.map(SalesHeader::getSalesDate).max(Comparator.naturalOrder())
								.ifPresent(t -> rows.add(new Object[] { id, t }));
					}
					return rows;
				}
				case "dayTotals": {
					LocalDateTime from = (LocalDateTime) a[0];
					LocalDateTime to = (LocalDateTime) a[1];
					Collection<?> excluded = (Collection<?>) a[2];
					List<SalesHeader> day = db.sales.stream().filter(s -> !excluded.contains(s.getStatus())
							&& !s.getSalesDate().isBefore(from) && s.getSalesDate().isBefore(to)).collect(Collectors.toList());
					return java.util.Collections.singletonList(new Object[] { (long) day.size(),
							day.isEmpty() ? null : day.stream().mapToDouble(SalesHeader::getTotalAmount).sum() });
				}
				default:
					return UNHANDLED;
			}
		});
	}

	private LiveCashAPI api(String... permissions) throws Exception {
		AppRole role = new AppRole();
		role.setPermissions(new HashSet<>(Arrays.asList(permissions)));
		UserAccount responsible = user(9L, "Responsable");
		responsible.setAppRole(role);
		LiveCashAPI api = new LiveCashAPI();
		set(api, LiveCashAPI.class, "service", service);
		set(api, LiveCashAPI.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return responsible;
			}
		});
		return api;
	}

	/** By id, as the SQL: the page reads the open sessions as columns (its own objects, not the test's). */
	private static boolean sameSession(CashierSession session, Object other) {
		return other instanceof CashierSession && session.getId().equals(((CashierSession) other).getId());
	}

	private static LiveCashDTO.Drawer drawer(LiveCashDTO live, String name) {
		return live.getDrawers().stream().filter(d -> Objects.equals(name, d.getPosMachineName())).findFirst().get();
	}

	private CashierSession session(PosMachine machine, UserAccount cashier, SessionStatus status, LocalDateTime openedAt,
			double opening) {
		CashierSession session = new CashierSession();
		session.setId(nextId++);
		session.setSessionNumber("SESSION" + session.getId());
		session.setCashier(cashier);
		session.setStatus(status);
		session.setOpenedAt(openedAt);
		session.setOpeningCash(opening);
		db.sessions.add(session);
		if (machine != null) {
			db.machineOf.put(session.getId(), machine);
		}
		return session;
	}

	private SalesHeader sale(CashierSession session, TransactionStatus status, double total, double change,
			LocalDateTime at, PaymentMethod method, Double paid) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setCashierSession(session);
		sale.setStatus(status);
		sale.setTotalAmount(total);
		sale.setChangeAmount(change);
		sale.setSalesDate(at);
		db.sales.add(sale);
		if (method != null) {
			pay(sale, method, paid);
		}
		return sale;
	}

	private void pay(SalesHeader sale, PaymentMethod method, Double amount) {
		Payment payment = new Payment();
		payment.setId(nextId++);
		payment.setSalesHeader(sale);
		payment.setPaymentMethod(method);
		payment.setTotalAmount(amount);
		payments.add(payment);
	}

	private void giveBack(CashierSession session, ReturnType type, double amount) {
		ReturnHeader ret = new ReturnHeader();
		ret.setId(nextId++);
		ret.setCashierSession(session);
		ret.setReturnType(type);
		ret.setTotalReturnAmount(amount);
		db.returns.add(ret);
	}

	private void move(CashierSession session, CashMovementType type, double amount) {
		CashMovement movement = new CashMovement();
		movement.setId(nextId++);
		movement.setCashierSession(session);
		movement.setType(type);
		movement.setAmount(amount);
		movement.setMovedAt(TODAY.atTime(13, 0));
		db.movements.add(movement);
	}

	private static UserAccount user(Long id, String name) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(name.toLowerCase());
		user.setFullName(name);
		return user;
	}

	private static PaymentMethod method(Long id, String code, PaymentMethodType type) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setCode(code);
		method.setName(code);
		method.setType(type);
		return method;
	}

	private static PosMachine machine(Long id, String name, boolean active) {
		PosMachine machine = new PosMachine();
		machine.setId(id);
		machine.setName(name);
		machine.setActive(active);
		return machine;
	}

	private static void set(Object target, Class<?> owner, String name, Object value) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
