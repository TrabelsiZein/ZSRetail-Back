package com.digithink.zsretail.store.controller;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO;
import com.digithink.zsretail.store.cashmovement.dto.OpeningFloatDTO;
import com.digithink.zsretail.store.dto.DayJournalDTO;
import com.digithink.zsretail.store.dto.DayRowDTO;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.dto.StoreReportTicketDTO;
import com.digithink.zsretail.store.service.CashierSessionService;
import com.digithink.zsretail.store.service.DayJournalService;
import com.digithink.zsretail.store.service.SessionAmounts;
import com.digithink.zsretail.store.service.SessionReportService;
import com.digithink.zsretail.store.service.StoreDaysService;
import com.digithink.zsretail.store.service.StoreReportService;
import com.digithink.zsretail.store.support.InMemorySessionFigures;

/**
 * 3.2.0: "Voir montants session" on the server (SessionAmounts) for the Sessions list, the session details and the session
 * ticket X / Z, and the Journal's own right (read:day-journal). Three sessions of the same day, each opened with 100 and
 * selling 50: S1 OPENED, S2 CLOSED (the cashier counted 148), S3 TERMINATED (148, the responsible 150). A responsible
 * without the right gets S1 and S2 without their amounts and S3 whole; with the right, every answer as before.
 */
class SessionAmountsTest {

	private static final String SESSIONS = "read:admin-sessions-history";

	private final UserAccount amira = user(1L, "amira");
	private final UserAccount responsible = user(2L, "resp");

	private InMemorySessionFigures db;
	private CashierSession opened;
	private CashierSession closed;
	private CashierSession terminated;
	private long nextId = 100;
	private final List<String> reached = new ArrayList<>();

	@BeforeEach
	void setUp() {
		db = new InMemorySessionFigures();
		opened = session(SessionStatus.OPENED, null, null);
		closed = session(SessionStatus.CLOSED, 148.0, null);
		terminated = session(SessionStatus.TERMINATED, 148.0, 150.0);
	}

	// ─── The Sessions list ───────────────────────────────────────────

	@Test
	@DisplayName("Sessions list without the right: OPENED and CLOSED without their amounts (counts kept), TERMINATED whole")
	void historyMaskedWithoutTheRight() throws Exception {
		Map<Long, SessionDashboardDTO> rows = history(responsible, SESSIONS);
		for (CashierSession hidden : new CashierSession[] { opened, closed }) {
			SessionDashboardDTO row = rows.get(hidden.getId());
			assertEquals(Boolean.TRUE, row.getAmountsHidden(), hidden.getStatus().name());
			assertNull(row.getTotalSalesAmount());
			assertNull(row.getOpeningCash(), "the opening float enters the expected cash");
			assertNull(row.getPosUserClosureCash(), "the cashier's count: the responsible's stays blind");
			assertNull(row.getCashDifference());
			assertNull(row.getTotalReturnsAmount());
			assertNull(row.getOpeningGap());
			assertEquals(1L, row.getSalesCount(), "the counts stay");
			assertEquals(0L, row.getReturnsCount());
		}
		SessionDashboardDTO whole = rows.get(terminated.getId());
		assertNull(whole.getAmountsHidden());
		assertEquals(50.0, whole.getTotalSalesAmount(), 1e-9);
		assertEquals(100.0, whole.getOpeningCash(), 1e-9);
		assertEquals(148.0, whole.getPosUserClosureCash(), 1e-9);
		assertEquals(-2.0, whole.getCashDifference(), 1e-9);
	}

	@Test
	@DisplayName("Sessions list with the right: every line as before, no flag sent")
	void historyUnchangedWithTheRight() throws Exception {
		Map<Long, SessionDashboardDTO> rows = history(responsible, SESSIONS, SessionAmounts.PERMISSION);
		Map<Long, SessionDashboardDTO> before = new LinkedHashMap<>();
		for (SessionDashboardDTO row : db.service.dashboards(db.sessions)) {
			before.put(row.getId(), row);
		}
		assertEquals(before, rows, "the same lines as the shared loader's");
		assertEquals(50.0, rows.get(opened.getId()).getTotalSalesAmount(), 1e-9);
		assertEquals(-2.0, rows.get(closed.getId()).getCashDifference(), 1e-9);
		rows.values().forEach(row -> assertNull(row.getAmountsHidden()));
	}

	@Test
	@DisplayName("Sessions list: without the right an amount filter finds only finally closed sessions; counts filter as before")
	void amountFiltersOnlyFinallyClosedWithoutTheRight() throws Exception {
		Method rule = CashierSessionService.class.getDeclaredMethod("amountFilterOnlyFinallyClosed", boolean.class,
				Double.class, Double.class, String.class);
		rule.setAccessible(true);
		assertTrue((Boolean) rule.invoke(null, false, 10.0, null, null), "min total sales");
		assertTrue((Boolean) rule.invoke(null, false, null, 10.0, null), "max total sales");
		assertTrue((Boolean) rule.invoke(null, false, null, null, "NEGATIVE"), "the difference's sign");
		assertFalse((Boolean) rule.invoke(null, false, null, null, null), "no amount filter (the counts' filters)");
		assertFalse((Boolean) rule.invoke(null, true, 10.0, 20.0, "NEGATIVE"), "with the right: as before");
	}

	// ─── The session details ─────────────────────────────────────────

	@Test
	@DisplayName("Details without the right: OPENED and CLOSED sent without amounts (counts, names, statuses kept)")
	void detailsMaskedWithoutTheRight() throws Exception {
		for (CashierSession hidden : new CashierSession[] { opened, closed }) {
			Map<String, Object> details = details(hidden, responsible, SESSIONS);
			assertEquals(Boolean.TRUE, details.get("amountsHidden"));
			SessionDashboardDTO session = (SessionDashboardDTO) details.get("session");
			assertNull(session.getTotalSalesAmount());
			assertNull(session.getPosUserClosureCash());
			assertEquals(hidden.getStatus(), session.getStatus());
			assertNull(details.get("totalSalesAmount"));
			assertNull(details.get("totalReturnsAmount"));
			assertEquals(1L, details.get("salesCount"), "the counts stay");
			assertTrue(((List<?>) details.get("sales")).isEmpty(), "the tickets (entities with amounts) not sent");
			assertTrue(((List<?>) details.get("paymentHeaders")).isEmpty());
			Map<?, ?> cash = (Map<?, ?>) ((List<?>) details.get("paymentSummary")).get(0);
			assertNull(cash.get("systemAmount"), "the expected cash");
			assertNull(cash.get("posClosureAmount"), "the cashier's count");
			assertNull(cash.get("deltaPOS"));
			assertEquals("ESPECE", cash.get("code"));
			assertEquals(1L, cash.get("ticketCount"));
			CashMovementDTO movement = (CashMovementDTO) ((List<?>) details.get("cashMovements")).get(0);
			assertNull(movement.getAmount());
			assertEquals("DEPENSE", movement.getType(), "the movement's kind and number stay");
			OpeningFloatDTO opening = (OpeningFloatDTO) details.get("openingFloat");
			assertNull(opening.getOpeningCash());
			assertNull(opening.getProposedFloat());
			assertNull(opening.getOpeningGap());
			assertEquals("SESSION1", opening.getSourceSessionNumber());
		}
	}

	@Test
	@DisplayName("Details: a TERMINATED session whole without the right; every session unchanged with it")
	void detailsWholeWhenFinallyClosedOrWithTheRight() throws Exception {
		Map<String, Object> whole = details(terminated, responsible, SESSIONS);
		assertFalse(whole.containsKey("amountsHidden"));
		assertEquals(50.0, (Double) whole.get("totalSalesAmount"), 1e-9);
		assertEquals(150.0, (Double) ((Map<?, ?>) ((List<?>) whole.get("paymentSummary")).get(0)).get("systemAmount"),
				1e-9);
		for (CashierSession any : new CashierSession[] { opened, closed, terminated }) {
			Map<String, Object> expected = detailsMap(any);
			Map<String, Object> details = details(any, responsible, SESSIONS, SessionAmounts.PERMISSION);
			String status = any.getStatus().name();
			assertEquals(expected.keySet(), details.keySet(), status + ": no key added or removed");
			assertEquals(expected.get("session"), details.get("session"), status);
			assertEquals(expected.get("paymentSummary"), details.get("paymentSummary"), status);
			for (String key : new String[] { "totalSalesAmount", "totalReturnsAmount", "salesCount" }) {
				assertEquals(expected.get(key), details.get(key), status + " " + key);
			}
			assertEquals(1, ((List<?>) details.get("sales")).size(), status);
			assertEquals(1, ((List<?>) details.get("paymentHeaders")).size(), status);
			assertEquals(5.0, ((CashMovementDTO) ((List<?>) details.get("cashMovements")).get(0)).getAmount(), 1e-9);
			assertEquals(expected.get("openingFloat"), details.get("openingFloat"), status);
		}
	}

	// ─── The session ticket X / Z ────────────────────────────────────

	@Test
	@DisplayName("Session ticket without the right: 403 for OPENED and CLOSED, 200 for TERMINATED; the session's own"
			+ " cashier gets their ticket Z at the till; 200 everywhere with the right")
	void sessionTicket() throws Exception {
		assertEquals(403, ticket(opened, responsible, SESSIONS).getStatusCodeValue());
		assertEquals(403, ticket(closed, responsible, SESSIONS).getStatusCodeValue());
		assertEquals(200, ticket(terminated, responsible, SESSIONS).getStatusCodeValue());
		assertEquals(200, ticket(closed, amira, "read:cashier-interface").getStatusCodeValue(),
				"the till's ticket Z at the cashier's fermeture (CASHIER_CLOSING_TICKET)");
		assertEquals(200, ticket(opened, amira, "read:cashier-interface").getStatusCodeValue());
		reached.clear();
		for (CashierSession any : new CashierSession[] { opened, closed, terminated }) {
			assertEquals(200, ticket(any, responsible, SESSIONS, SessionAmounts.PERMISSION).getStatusCodeValue());
		}
		assertEquals(3, reached.size(), "built for each: " + reached);
		CashierSessionAPI api = api(responsible, SESSIONS, SessionAmounts.PERMISSION);
		assertEquals(404, api.getReportTicket(999L).getStatusCodeValue());
	}

	// ─── The Journal's own right ─────────────────────────────────────

	@Test
	@DisplayName("The Journal: 403 on its six endpoints without read:day-journal (even with the Sessions' right)")
	void journalForbiddenWithoutItsRight() throws Exception {
		CashierSessionAPI api = api(responsible, SESSIONS, SessionAmounts.PERMISSION);
		assertEquals(403, api.getStoreDays("2026-11").getStatusCodeValue());
		assertEquals(403, api.getStoreDaysExcel("2026-11").getStatusCodeValue());
		assertEquals(403, api.getDayJournal("2026-11-03").getStatusCodeValue());
		assertEquals(403, api.getDayJournalExcel("2026-11-03").getStatusCodeValue());
		assertEquals(403, api.getStoreReportTicketX(null).getStatusCodeValue());
		assertEquals(403, api.getStoreReportTicketZ("2026-11-03", null).getStatusCodeValue());
		assertTrue(reached.isEmpty(), "nothing read: " + reached);
	}

	@Test
	@DisplayName("The Journal with read:day-journal: 200, unmasked whatever the session amounts' right")
	void journalWithItsRight() throws Exception {
		CashierSessionAPI api = api(responsible, CashierSessionAPI.JOURNAL_PERMISSION);
		assertEquals(200, api.getStoreDays("2026-11").getStatusCodeValue());
		assertEquals(200, api.getDayJournal("2026-11-03").getStatusCodeValue());
		assertEquals(200, api.getStoreReportTicketX(null).getStatusCodeValue());
		assertEquals(200, api.getStoreReportTicketZ("2026-11-03", null).getStatusCodeValue());
		assertNotEquals(403, api.getStoreDaysExcel("2026-11").getStatusCodeValue());
		assertNotEquals(403, api.getDayJournalExcel("2026-11-03").getStatusCodeValue());
		assertTrue(reached.containsAll(Arrays.asList("buildMonth", "buildDay", "buildX", "buildZ")), "" + reached);
		assertEquals(400, api.getStoreDays("nope").getStatusCodeValue(), "a bad month still 400 with the right");
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	/** The list's lines by session id, through the API and the real service over the in-memory tables. */
	private Map<Long, SessionDashboardDTO> history(UserAccount user, String... permissions) throws Exception {
		CashierSessionService service = new CashierSessionService();
		set(service, CashierSessionService.class, "sessionFiguresService", db.service);
		set(service, CashierSessionService.class, "cashierSessionRepository",
				proxy(CashierSessionRepository.class, (m, a) -> "findAll".equals(m) && a.length == 2
						? new PageImpl<>(new ArrayList<>(db.sessions))
						: UNHANDLED));
		CashierSessionAPI api = api(user, permissions);
		set(api, _BaseController.class, "service", service);
		ResponseEntity<?> response = api.getSessionHistory(null, null, null, null, null, null, null, null, null, null,
				null, null, 0, 10);
		assertEquals(200, response.getStatusCodeValue());
		@SuppressWarnings("unchecked")
		List<SessionDashboardDTO> content = (List<SessionDashboardDTO>) ((Map<String, Object>) response.getBody())
				.get("content");
		Map<Long, SessionDashboardDTO> rows = new LinkedHashMap<>();
		content.forEach(row -> rows.put(row.getId(), row));
		assertEquals(3, rows.size());
		return rows;
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> details(CashierSession session, UserAccount user, String... permissions)
			throws Exception {
		ResponseEntity<?> response = api(user, permissions).getSessionDetails(session.getId());
		assertEquals(200, response.getStatusCodeValue());
		return (Map<String, Object>) response.getBody();
	}

	private ResponseEntity<?> ticket(CashierSession session, UserAccount user, String... permissions) throws Exception {
		return api(user, permissions).getReportTicket(session.getId());
	}

	/** The details of a session as CashierSessionService.getSessionDetails shapes them (a fresh map on each call). */
	private Map<String, Object> detailsMap(CashierSession session) {
		SessionDashboardDTO dto = db.service.dashboards(java.util.Collections.singletonList(session)).get(0);
		Map<String, Object> details = new HashMap<>();
		details.put("session", dto);
		details.put("salesCount", 1L);
		details.put("totalSalesAmount", 50.0);
		details.put("returnsCount", 0L);
		details.put("totalReturnsAmount", 0.0);
		details.put("simpleReturnsAmount", 0.0);
		details.put("voucherReturnsAmount", 0.0);
		details.put("sales", new ArrayList<>(Arrays.asList(db.sales.stream()
				.filter(s -> s.getCashierSession() == session).findFirst().get())));
		details.put("returns", new ArrayList<>());
		details.put("paymentHeaders", new ArrayList<>(Arrays.asList("a payment document")));
		Map<String, Object> cash = new HashMap<>();
		cash.put("code", "ESPECE");
		cash.put("name", "Espèces");
		cash.put("ticketCount", 1L);
		cash.put("systemAmount", 150.0);
		cash.put("posClosureAmount", session.getPosUserClosureCash());
		cash.put("deltaPOS", session.getPosUserClosureCash() == null ? null : session.getPosUserClosureCash() - 150.0);
		cash.put("respClosureAmount", session.getResponsibleClosureCash());
		cash.put("deltaResp", null);
		details.put("paymentSummary", new ArrayList<>(Arrays.asList(cash)));
		CashMovementDTO movement = new CashMovementDTO();
		movement.setType("DEPENSE");
		movement.setDirection("OUT");
		movement.setAmount(5.0);
		details.put("cashMovements", new ArrayList<>(Arrays.asList(movement)));
		OpeningFloatDTO opening = new OpeningFloatDTO();
		opening.setProposedFloat(100.0);
		opening.setOpeningCash(100.0);
		opening.setOpeningGap(0.0);
		opening.setSourceSessionNumber("SESSION1");
		details.put("openingFloat", opening);
		details.put("closingWithdrawal", null);
		details.put("openingUnverified", null);
		details.put("hasPerMethodRespClosure", false);
		return details;
	}

	private CashierSessionAPI api(UserAccount user, String... permissions) throws Exception {
		AppRole role = new AppRole();
		role.setPermissions(new HashSet<>(Arrays.asList(permissions)));
		user.setAppRole(role);
		CashierSessionAPI api = new CashierSessionAPI();
		set(api, CashierSessionAPI.class, "applicationModeService", TestModes.standalone());
		set(api, CashierSessionAPI.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return user;
			}
		});
		set(api, _BaseController.class, "service", new CashierSessionService() {
			@Override
			public Optional<CashierSession> findById(Long id) {
				return db.sessions.stream().filter(s -> s.getId().equals(id)).findFirst();
			}

			@Override
			public Map<String, Object> getSessionDetails(Long sessionId) {
				return detailsMap(findById(sessionId).get());
			}
		});
		set(api, CashierSessionAPI.class, "sessionReportService", new SessionReportService() {
			@Override
			public SessionReportTicketDTO build(Long sessionId) {
				reached.add("ticket " + sessionId);
				return new SessionReportTicketDTO();
			}
		});
		set(api, CashierSessionAPI.class, "storeReportService", new StoreReportService() {
			@Override
			public StoreReportTicketDTO buildX(Long posMachineId) {
				reached.add("buildX");
				return new StoreReportTicketDTO();
			}

			@Override
			public StoreReportTicketDTO buildZ(LocalDate date, Long posMachineId) {
				reached.add("buildZ");
				return new StoreReportTicketDTO();
			}

			@Override
			public StoreReportTicketDTO dayTicketByMachine(LocalDate date) {
				reached.add("byMachine");
				return null;
			}
		});
		set(api, CashierSessionAPI.class, "storeDaysService", new StoreDaysService() {
			@Override
			public List<DayRowDTO> buildMonth(YearMonth month, boolean withMargins) {
				reached.add("buildMonth");
				return new ArrayList<>();
			}
		});
		set(api, CashierSessionAPI.class, "dayJournalService", new DayJournalService() {
			@Override
			public DayJournalDTO build(LocalDate date, boolean withMargins) {
				reached.add("buildDay");
				return new DayJournalDTO();
			}
		});
		return api;
	}

	/** A session of 2026-11-03 opened with 100, one sale of 50 (its system amount 150), Amira's. */
	private CashierSession session(SessionStatus status, Double posClosure, Double respClosure) {
		CashierSession session = new CashierSession();
		session.setId(nextId++);
		session.setSessionNumber("SESSION" + session.getId());
		session.setCashier(amira);
		session.setOpeningCash(100.0);
		session.setOpenedAt(LocalDate.of(2026, 11, 3).atTime(8, 0));
		session.setStatus(status);
		if (status != SessionStatus.OPENED) {
			session.setClosedAt(session.getOpenedAt().plusHours(8));
		}
		session.setPosUserClosureCash(posClosure);
		session.setResponsibleClosureCash(respClosure);
		db.sessions.add(session);
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setCashierSession(session);
		sale.setStatus(TransactionStatus.COMPLETED);
		sale.setTotalAmount(50.0);
		db.sales.add(sale);
		return session;
	}

	private static UserAccount user(Long id, String name) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(name);
		user.setFullName(name);
		return user;
	}

	private static void set(Object target, Class<?> declaringClass, String name, Object value) throws Exception {
		Field field = declaringClass.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

}
