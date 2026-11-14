package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.dto.CompanyInformationDTO;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.GeneralSetup;
import com.digithink.zsretail.core.model.Payment;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.BadgePermission;
import com.digithink.zsretail.core.model.enumeration.GrantedBy;
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
import com.digithink.zsretail.store.cashmovement.CashMovementException;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementRequest;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.repository.CashMovementRepository;
import com.digithink.zsretail.store.cashmovement.service.CashMovementService;
import com.digithink.zsretail.store.dto.CloseSessionRequestDTO;
import com.digithink.zsretail.store.dto.DayJournalDTO;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.dto.StoreReportTicketDTO;
import com.digithink.zsretail.store.exception.CashDiscrepancyException;
import com.digithink.zsretail.store.exception.TillRightRequiredException;
import com.digithink.zsretail.store.model.SessionCashCount;
import com.digithink.zsretail.store.posmachine.DrawerOwner;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.model.SessionPosMachine;
import com.digithink.zsretail.store.repository.PaymentChangeLogRepository;
import com.digithink.zsretail.store.erp.repository.PaymentHeaderRepository;
import com.digithink.zsretail.store.repository.ScannedTicketPaymentRepository;
import com.digithink.zsretail.store.repository.SessionCashCountRepository;
import com.digithink.zsretail.store.support.InMemoryRfid;
import com.digithink.zsretail.store.support.InMemoryTillRights;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * 3.2.0, POS machines step 5: the cash movements (PRELEVEMENT, DEPENSE, APPORT). The service over in-memory tables and
 * the real till rights guard (InMemoryTillRights); the real CashierSessionService (expected cash, closing check),
 * SessionReportService (ticket X / Z) and StoreReportService (store ticket, one POS machine). The session of Karim
 * (a responsible: his role holds till:cash-movement), opened with 100, one sale of 50 in cash; Amira's (a cashier: her
 * role does not hold it).
 */
class CashMovementTest {

	private static final LocalDate DAY = LocalDate.of(2026, 11, 14);

	private final PaymentMethod cash = method(1L, "ESP", "Espèces", PaymentMethodType.CLIENT_ESPECES);
	private final AppRole responsibleRole = role("RESPONSIBLE", "till:cash-movement");
	private final AppRole cashierRole = role("POS_USER");
	private final UserAccount karim = user(1L, "karim", "Karim Responsable", responsibleRole);
	private final UserAccount amira = user(2L, "amira", "Amira Caissière", cashierRole);

	private final List<CashierSession> sessions = new ArrayList<>();
	private final List<SalesHeader> sales = new ArrayList<>();
	private final List<Payment> payments = new ArrayList<>();
	private final List<CashMovement> movements = new ArrayList<>();
	private final List<SessionCashCount> savedCounts = new ArrayList<>();
	private long nextId = 100;

	private final InMemoryTillRights rights = new InMemoryTillRights();
	private final InMemoryRfid rfid = new InMemoryRfid();
	private CashMovementService movementService;
	private CashierSessionService sessionService;
	private SessionReportService reports;
	private CashMovementRepository movementRepo;
	private CashierSessionRepository sessionRepo;
	private CashierSession karimSession;
	private CashierSession amiraSession;

	@BeforeEach
	void setUp() throws Exception {
		karimSession = session(karim, "SESSION261114001", 100.0);
		amiraSession = session(amira, "SESSION261114002", 50.0);
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setCashierSession(karimSession);
		sale.setStatus(TransactionStatus.COMPLETED);
		sale.setTotalAmount(50.0);
		sale.setDiscountAmount(0.0);
		sale.setChangeAmount(0.0);
		sales.add(sale);
		Payment payment = new Payment();
		payment.setId(nextId++);
		payment.setSalesHeader(sale);
		payment.setPaymentMethod(cash);
		payment.setTotalAmount(50.0);
		payments.add(payment);

		movementRepo = proxy(CashMovementRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					CashMovement movement = (CashMovement) a[0];
					movement.setId(nextId++);
					movements.add(movement);
					return movement;
				}
				case "countByCashierSession":
					return movements.stream().filter(x -> x.getCashierSession() == a[0]).count();
				case "findByCashierSessionOrderByMovedAtAsc":
					return movements.stream().filter(x -> x.getCashierSession() == a[0]).collect(Collectors.toList());
				case "findByMovedAtGreaterThanEqualAndMovedAtLessThanOrderByMovedAtAsc": {
					LocalDateTime from = (LocalDateTime) a[0];
					LocalDateTime to = (LocalDateTime) a[1];
					return movements.stream().filter(x -> !x.getMovedAt().isBefore(from) && x.getMovedAt().isBefore(to))
							.collect(Collectors.toList());
				}
				default:
					return UNHANDLED;
			}
		});
		sessionRepo = proxy(CashierSessionRepository.class, (m, a) -> {
			switch (m) {
				case "findByCashierAndStatus":
					return sessions.stream().filter(s -> s.getCashier() == a[0] && s.getStatus() == a[1]).findFirst();
				case "findById":
				case "findByIdForUpdate":
					return sessions.stream().filter(s -> s.getId().equals(a[0])).findFirst();
				case "save":
					return a[0];
				case "findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrStatusOrderByOpenedAtAsc":
					return sessions.stream().filter(s -> s.getStatus() == SessionStatus.OPENED).collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		});
		movementService = new CashMovementService(movementRepo, sessionRepo, rights.guard, rfid.sessionMachineService);
		set(movementService, CashMovementService.class, "clock",
				Clock.fixed(DAY.atTime(11, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault()));

		SalesHeaderRepository salesRepo = proxy(SalesHeaderRepository.class, (m, a) -> {
			if ("findByCashierSession".equals(m)) {
				return sales.stream().filter(s -> s.getCashierSession() == a[0]).collect(Collectors.toList());
			}
			if ("findByCashierSessionAndStatus".equals(m)) {
				return sales.stream().filter(s -> s.getCashierSession() == a[0] && s.getStatus() == a[1])
						.collect(Collectors.toList());
			}
			return UNHANDLED;
		});
		PaymentRepository paymentRepo = proxy(PaymentRepository.class, (m, a) -> {
			if ("findBySalesHeader".equals(m)) {
				return payments.stream().filter(p -> p.getSalesHeader() == a[0]).collect(Collectors.toList());
			}
			if ("findByCashierSession".equals(m)) {
				return payments.stream().filter(p -> p.getSalesHeader().getCashierSession() == a[0])
						.collect(Collectors.toList());
			}
			return UNHANDLED;
		});
		ReturnHeaderRepository returnRepo = proxy(ReturnHeaderRepository.class,
				(m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>() : UNHANDLED);
		PaymentMethodRepository methodRepo = proxy(PaymentMethodRepository.class, (m, a) -> {
			if ("findByType".equals(m)) return a[0] == PaymentMethodType.CLIENT_ESPECES ? Optional.of(cash) : Optional.empty();
			if ("findAll".equals(m)) return Arrays.asList(cash);
			if ("findById".equals(m)) return cash.getId().equals(a[0]) ? Optional.of(cash) : Optional.empty();
			return UNHANDLED;
		});
		SessionCashCountRepository countRepo = proxy(SessionCashCountRepository.class, (m, a) -> {
			if ("findByCashierSession".equals(m)) {
				return savedCounts.stream().filter(c -> c.getCashierSession() == a[0]).collect(Collectors.toList());
			}
			return UNHANDLED;
		});
		ScannedTicketPaymentRepository ticketRepo = proxy(ScannedTicketPaymentRepository.class,
				(m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>() : UNHANDLED);
		CurrentUserProvider user = new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return karim;
			}

			@Override
			public String getCurrentUserName() {
				return "karim";
			}
		};

		sessionService = new CashierSessionService();
		set(sessionService, CashierSessionService.class, "cashierSessionRepository", sessionRepo);
		set(sessionService, CashierSessionService.class, "salesHeaderRepository", salesRepo);
		set(sessionService, CashierSessionService.class, "paymentRepository", paymentRepo);
		set(sessionService, CashierSessionService.class, "returnHeaderRepository", returnRepo);
		set(sessionService, CashierSessionService.class, "paymentMethodRepository", methodRepo);
		set(sessionService, CashierSessionService.class, "sessionCashCountRepository", countRepo);
		set(sessionService, CashierSessionService.class, "scannedTicketPaymentRepository", ticketRepo);
		set(sessionService, CashierSessionService.class, "paymentHeaderRepository", proxy(PaymentHeaderRepository.class,
				(m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>() : UNHANDLED));
		set(sessionService, CashierSessionService.class, "tillRightsGuard", InMemoryTillRights.allowAll());
		set(sessionService, CashierSessionService.class, "generalSetupRepository", proxy(GeneralSetupRepository.class,
				(m, a) -> {
					if (!"findByCode".equals(m)) return UNHANDLED;
					GeneralSetup on = new GeneralSetup();
					on.setCode((String) a[0]);
					on.setValeur("true");
					return "ENABLE_CASH_DISCREPANCY_CHECK".equals(a[0]) ? Optional.of(on) : Optional.empty();
				}));
		set(sessionService, _BaseService.class, "currentUserProvider", user);
		set(sessionService, CashierSessionService.class, "sessionCashCountService", new SessionCashCountService() {
			@Override
			public SessionCashCount save(SessionCashCount count) {
				savedCounts.add(count);
				return count;
			}
		});
		set(sessionService, CashierSessionService.class, "sessionPosMachineService", rfid.sessionMachineService);
		set(sessionService, CashierSessionService.class, "cashMovementRepository", movementRepo);

		reports = new SessionReportService();
		set(reports, SessionReportService.class, "sessionPosMachineService", rfid.sessionMachineService);
		set(reports, SessionReportService.class, "cashierSessionRepository", sessionRepo);
		set(reports, SessionReportService.class, "cashierSessionService", sessionService);
		set(reports, SessionReportService.class, "salesHeaderRepository", salesRepo);
		set(reports, SessionReportService.class, "paymentRepository", paymentRepo);
		set(reports, SessionReportService.class, "returnHeaderRepository", returnRepo);
		set(reports, SessionReportService.class, "paymentMethodRepository", methodRepo);
		set(reports, SessionReportService.class, "sessionCashCountRepository", countRepo);
		set(reports, SessionReportService.class, "scannedTicketPaymentRepository", ticketRepo);
		set(reports, SessionReportService.class, "currentUserProvider", user);
		set(reports, SessionReportService.class, "salesLineRepository",
				proxy(SalesLineRepository.class, (m, a) -> "findByCashierSession".equals(m) ? new ArrayList<>() : UNHANDLED));
		set(reports, SessionReportService.class, "paymentChangeLogRepository", proxy(PaymentChangeLogRepository.class,
				(m, a) -> "countBySalesHeaderCashierSessionAndCreatedAtAfter".equals(m) ? 0L : UNHANDLED));
		set(reports, SessionReportService.class, "cashMovementRepository", movementRepo);
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private static PaymentMethod method(Long id, String code, String name, PaymentMethodType type) {
		PaymentMethod method = new PaymentMethod();
		method.setId(id);
		method.setCode(code);
		method.setName(name);
		method.setType(type);
		method.setBehaviour(PaymentMethodBehaviour.AMOUNT);
		method.setDisplayOrder(1);
		return method;
	}

	private static AppRole role(String name, String... permissions) {
		AppRole role = new AppRole(name, name, "POS_USER".equals(name));
		role.setPermissions(new HashSet<>(Arrays.asList(permissions)));
		return role;
	}

	private static UserAccount user(Long id, String username, String fullName, AppRole role) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(username);
		user.setFullName(fullName);
		user.setActive(true);
		user.setAppRole(role);
		return user;
	}

	private CashierSession session(UserAccount cashier, String number, double openingCash) {
		CashierSession session = new CashierSession();
		session.setId(nextId++);
		session.setSessionNumber(number);
		session.setCashier(cashier);
		session.setOpeningCash(openingCash);
		session.setOpenedAt(DAY.atTime(8, 0));
		session.setStatus(SessionStatus.OPENED);
		sessions.add(session);
		return session;
	}

	private static CashMovementRequest request(String type, Double amount, String note) {
		CashMovementRequest request = new CashMovementRequest();
		request.setType(type);
		request.setAmount(amount);
		request.setNote(note);
		return request;
	}

	private static void set(Object target, Class<?> owner, String field, Object value) throws Exception {
		Field f = owner.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static SessionReportTicketDTO.ControlLine cashLine(List<SessionReportTicketDTO.ControlLine> control) {
		return control.stream().filter(c -> "ESP".equals(c.getCode())).findFirst().orElse(null);
	}

	// ─── Recording ───────────────────────────────────────────────────

	@Test
	@DisplayName("The expected cash adds the apports and subtracts the prélèvements and the dépenses, one type at a time")
	void expectedCashWithEachType() {
		assertEquals(150.0, sessionService.getExpectedCash(karimSession.getId()), 1e-9, "100 opening + 50 cash");
		movementService.record(karim, request("APPORT", 20.0, null));
		assertEquals(170.0, sessionService.getExpectedCash(karimSession.getId()), 1e-9, "apport +20");
		movementService.record(karim, request("PRELEVEMENT", 30.0, "coffre"));
		assertEquals(140.0, sessionService.getExpectedCash(karimSession.getId()), 1e-9, "prélèvement -30");
		movementService.record(karim, request("DEPENSE", 4.5, "café"));
		assertEquals(135.5, sessionService.getExpectedCash(karimSession.getId()), 1e-9, "dépense -4.5");
		assertEquals(50.0, sessionService.getExpectedCash(amiraSession.getId()), 1e-9, "another session untouched");
		assertEquals(-14.5, sessionService.cashMovementNet(karimSession), 1e-9, "the sessions list's system side");
	}

	@Test
	@DisplayName("A movement's slip: its number in the session, the type, the amount to the millime, the note, who and"
			+ " who authorised (ROLE: himself), the POS machine; no balance")
	void slip() throws Exception {
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		rfid.sessionMachineRepository.save(new SessionPosMachine(karimSession, caisse1));
		CashMovementDTO first = movementService.record(karim, request("prelevement", 100.12345, "  banque  "));
		CashMovementDTO second = movementService.record(karim, request("APPORT", 5.0, " "));

		assertEquals("SESSION261114001-M01", first.getNumber());
		assertEquals("SESSION261114001-M02", second.getNumber());
		assertEquals("PRELEVEMENT", first.getType());
		assertEquals("OUT", first.getDirection());
		assertEquals("IN", second.getDirection());
		assertEquals(100.123, first.getAmount(), 1e-9, "to the millime");
		assertEquals("banque", first.getNote());
		assertNull(second.getNote(), "a blank note is none");
		assertEquals("Karim Responsable", first.getCashierName());
		assertEquals("Karim Responsable", first.getAuthorisedByName());
		assertEquals("ROLE", first.getGrantedBy());
		assertEquals("Caisse 1", first.getPosMachineName());
		assertEquals(DAY.atTime(11, 0), first.getMovedAt());
		String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(first);
		assertFalse(json.contains("expected") || json.contains("balance") || json.contains("total"), json);
		// the role's pass is logged like the other till actions
		assertTrue(rights.log.stream().anyMatch(l -> l.getFunctionality() == BadgePermission.CASH_MOVEMENT
				&& l.getGrantedBy() == GrantedBy.ROLE));
	}

	@Test
	@DisplayName("Refused without the right (403, nothing saved); passed with a badge grant, authorised by the badge's owner,"
			+ " the grant used once")
	void rightOrBadge() {
		TillRightRequiredException refused = assertThrows(TillRightRequiredException.class,
				() -> movementService.record(amira, request("PRELEVEMENT", 30.0, null)));
		assertEquals(BadgePermission.CASH_MOVEMENT, refused.getAction());
		assertTrue(movements.isEmpty());

		String token = rights.grant(BadgePermission.CASH_MOVEMENT, amiraSession.getId(), amira, karim);
		CashMovementRequest withGrant = request("PRELEVEMENT", 30.0, null);
		withGrant.setTillGrant(token);
		CashMovementDTO slip = movementService.record(amira, withGrant);
		assertEquals("Amira Caissière", slip.getCashierName());
		assertEquals("Karim Responsable", slip.getAuthorisedByName());
		assertEquals("BADGE", slip.getGrantedBy());
		assertEquals(1, movements.size());
		assertEquals(amira, movements.get(0).getDoneBy());
		assertEquals(karim, movements.get(0).getAuthorisedBy());
		assertNotNull(rights.last().getConsumedAt(), "the grant is used");
		assertThrows(TillRightRequiredException.class, () -> movementService.record(amira, withGrant), "once");

		// a grant of another action is not one
		CashMovementRequest otherAction = request("APPORT", 1.0, null);
		otherAction.setTillGrant(rights.grant(BadgePermission.MAKE_RETURN, amiraSession.getId(), amira, karim));
		assertThrows(TillRightRequiredException.class, () -> movementService.record(amira, otherAction));
		assertEquals(1, movements.size());
	}

	@Test
	@DisplayName("Only on the user's own OPENED session: refused on a closed session, and with another user's session")
	void ownOpenSessionOnly() {
		CashMovementException other = assertThrows(CashMovementException.class, () -> {
			CashMovementRequest request = request("APPORT", 10.0, null);
			request.setSessionId(amiraSession.getId());
			movementService.record(karim, request);
		});
		assertEquals("CASH_MOVEMENT_NOT_YOUR_SESSION", other.getCode());

		karimSession.setStatus(SessionStatus.CLOSED);
		CashMovementException closed = assertThrows(CashMovementException.class,
				() -> movementService.record(karim, request("APPORT", 10.0, null)));
		assertEquals("CASH_MOVEMENT_NO_OPEN_SESSION", closed.getCode());
		assertTrue(movements.isEmpty());
	}

	@Test
	@DisplayName("The request's checks: a type of the three, an amount above 0 (to the millime), a note for a dépense")
	void requestChecks() {
		assertEquals("CASH_MOVEMENT_TYPE_INVALID", assertThrows(CashMovementException.class,
				() -> movementService.record(karim, request("VOL", 10.0, null))).getCode());
		for (Double amount : new Double[] { null, 0.0, -5.0, 0.0004 }) {
			assertEquals("CASH_MOVEMENT_AMOUNT_INVALID", assertThrows(CashMovementException.class,
					() -> movementService.record(karim, request("APPORT", amount, null))).getCode(), "amount " + amount);
		}
		assertEquals("CASH_MOVEMENT_NOTE_REQUIRED", assertThrows(CashMovementException.class,
				() -> movementService.record(karim, request("DEPENSE", 10.0, "  "))).getCode());
		char[] longNote = new char[256];
		Arrays.fill(longNote, 'x');
		assertEquals("CASH_MOVEMENT_NOTE_TOO_LONG", assertThrows(CashMovementException.class,
				() -> movementService.record(karim, request("PRELEVEMENT", 10.0, new String(longNote)))).getCode());
		assertTrue(movements.isEmpty());
	}

	// ─── The closing and the tickets ─────────────────────────────────

	@Test
	@DisplayName("The closing's cash check: counted as expected with the movements passes; counted without them is a"
			+ " difference")
	void closingCheck() throws Exception {
		movementService.record(karim, request("PRELEVEMENT", 30.0, null));
		movementService.record(karim, request("APPORT", 10.0, null));

		CloseSessionRequestDTO wrong = new CloseSessionRequestDTO();
		CloseSessionRequestDTO.CashCountLineDTO line = new CloseSessionRequestDTO.CashCountLineDTO();
		line.setDenominationValue(150.0); // 100 + 50, the movements forgotten
		line.setQuantity(1);
		line.setPaymentMethodId(cash.getId());
		wrong.setCashCountLines(Arrays.asList(line));
		CashDiscrepancyException difference = assertThrows(CashDiscrepancyException.class,
				() -> sessionService.closeSessionWithCashCount(karimSession.getId(), wrong));
		assertEquals(SessionStatus.OPENED, karimSession.getStatus());
		assertNotNull(difference);

		line.setDenominationValue(130.0); // 150 - 30 + 10
		CashierSession closed = sessionService.closeSessionWithCashCount(karimSession.getId(), wrong);
		assertEquals(SessionStatus.CLOSED, closed.getStatus());
		assertEquals(130.0, closed.getRealCash(), 1e-9, "the expected cash stored holds the movements");
	}

	@Test
	@DisplayName("Session ticket X / Z: the block MOUVEMENTS DE CAISSE (number and total per type), absent without any;"
			+ " the cash line of CONTRÔLE holds them")
	void sessionTicket() throws Exception {
		SessionReportTicketDTO none = reports.build(amiraSession.getId());
		assertNull(none.getCashMovements());
		assertFalse(new ObjectMapper().registerModule(new JavaTimeModule()).valueToTree(none).has("cashMovements"));

		movementService.record(karim, request("APPORT", 20.0, null));
		movementService.record(karim, request("PRELEVEMENT", 30.0, null));
		movementService.record(karim, request("PRELEVEMENT", 15.0, null));
		movementService.record(karim, request("DEPENSE", 4.5, "café"));
		SessionReportTicketDTO x = reports.build(karimSession.getId());
		SessionReportTicketDTO.CashMovements block = x.getCashMovements();
		assertEquals(1, block.getApportCount());
		assertEquals(20.0, block.getApportTotal(), 1e-9);
		assertEquals(2, block.getPrelevementCount());
		assertEquals(45.0, block.getPrelevementTotal(), 1e-9);
		assertEquals(1, block.getDepenseCount());
		assertEquals(4.5, block.getDepenseTotal(), 1e-9);
		assertEquals(20.0, block.getInTotal(), 1e-9);
		assertEquals(49.5, block.getOutTotal(), 1e-9);
		assertEquals(120.5, cashLine(x.getControl()).getExpected(), 1e-9, "150 + 20 - 45 - 4.5");
	}

	@Test
	@DisplayName("Store ticket X: the sessions' blocks added up, for the whole store and for one POS machine (and its group)")
	void storeTicket() throws Exception {
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		rfid.sessionMachineRepository.save(new SessionPosMachine(karimSession, caisse1));
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		movementService.record(karim, request("PRELEVEMENT", 30.0, null));
		String token = rights.grant(BadgePermission.CASH_MOVEMENT, amiraSession.getId(), amira, karim);
		CashMovementRequest apport = request("APPORT", 12.0, null);
		apport.setTillGrant(token);
		movementService.record(amira, apport);

		StoreReportService store = new StoreReportService();
		set(store, StoreReportService.class, "cashierSessionRepository", sessionRepo);
		set(store, StoreReportService.class, "sessionReportService", reports);
		set(store, StoreReportService.class, "paymentMethodRepository", proxy(PaymentMethodRepository.class,
				(m, a) -> "findAll".equals(m) ? Arrays.asList(cash) : UNHANDLED));
		set(store, StoreReportService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return karim;
			}
		});
		set(store, StoreReportService.class, "companyInformationService", new CompanyInformationService(null) {
			@Override
			public CompanyInformationDTO get() {
				return new CompanyInformationDTO();
			}
		});
		set(store, StoreReportService.class, "sessionPosMachineService", rfid.sessionMachineService);
		store.clock = Clock.fixed(DAY.atTime(15, 0).atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());

		StoreReportTicketDTO all = store.buildX();
		assertEquals(12.0, all.getCashMovements().getApportTotal(), 1e-9);
		assertEquals(30.0, all.getCashMovements().getPrelevementTotal(), 1e-9);
		assertEquals(1, all.getCashMovements().getApportCount());
		StoreReportTicketDTO.PosMachineGroup group = all.getPosMachines().get(0);
		assertEquals("Caisse 1", group.getPosMachineName());
		assertEquals(30.0, group.getCashMovements().getOutTotal(), 1e-9, "the machine's subtotal");
		assertEquals(12.0, all.getPosMachines().get(1).getCashMovements().getInTotal(), 1e-9, "'—'");

		StoreReportTicketDTO one = store.buildX(caisse1.getId());
		assertEquals(30.0, one.getCashMovements().getPrelevementTotal(), 1e-9);
		assertEquals(0, one.getCashMovements().getApportCount(), "Amira's apport is not on Caisse 1");
	}

	@Test
	@DisplayName("Journal's day: the movements by their own time (not journal lines), their totals, and the Excel sheet"
			+ " 'Mouvements de caisse'")
	void journalDay() throws Exception {
		movementService.record(karim, request("PRELEVEMENT", 30.0, "coffre"));
		movementService.record(karim, request("APPORT", 10.0, null));
		List<CashMovementDTO> day = movementService.linesOfDay(DAY);
		assertEquals(2, day.size());
		assertTrue(movementService.linesOfDay(DAY.minusDays(1)).isEmpty());

		DayJournalDTO journal = new DayJournalDTO();
		journal.setDate(DAY);
		journal.setTotals(new DayJournalDTO.Totals());
		journal.setCashMovements(day);
		journal.setCashMovementTotals(DayJournalService.cashMovementTotals(day));
		assertEquals(10.0, journal.getCashMovementTotals().getInTotal(), 1e-9);
		assertEquals(30.0, journal.getCashMovementTotals().getOutTotal(), 1e-9);
		assertTrue(journal.getLines().isEmpty(), "never journal lines");

		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(JournalExcel.journal(journal, false, null)))) {
			Sheet sheet = book.getSheet("Mouvements de caisse");
			assertNotNull(sheet);
			Row first = sheet.getRow(1);
			assertEquals("SESSION261114001-M01", first.getCell(1).getStringCellValue());
			assertEquals("Prélèvement", first.getCell(5).getStringCellValue());
			assertEquals(-30.0, first.getCell(6).getNumericCellValue(), 1e-9, "out: negative");
			assertEquals("coffre", first.getCell(7).getStringCellValue());
			assertEquals(10.0, sheet.getRow(2).getCell(6).getNumericCellValue(), 1e-9);
			assertEquals(-20.0, sheet.getRow(6).getCell(6).getNumericCellValue(), 1e-9, "net: in - out");
		}
		DayJournalDTO without = new DayJournalDTO();
		without.setDate(DAY);
		without.setTotals(new DayJournalDTO.Totals());
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(JournalExcel.journal(without, false, null)))) {
			assertNull(book.getSheet("Mouvements de caisse"), "no sheet without a movement");
		}
	}

	@Test
	@DisplayName("Sessions list: the differences add the movements to the system side (a right count reads 0)")
	void sessionsListDifference() {
		movementService.record(karim, request("PRELEVEMENT", 30.0, null));
		karimSession.setPosUserClosureCash(120.0); // 150 - 30
		SessionDashboardDTO row = SessionDashboardDTO.fromEntity(karimSession, 1L, 50.0, 0L, 0.0, 0.0, 0.0, 120.0,
				0.0 + sessionService.cashMovementNet(karimSession));
		assertEquals(0.0, row.getCashDifference(), 1e-9);
	}
}
