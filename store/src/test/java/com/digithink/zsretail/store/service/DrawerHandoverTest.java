package com.digithink.zsretail.store.service;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.PaymentMethod;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.PaymentMethodType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.PaymentMethodRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.store.cashmovement.CashMovementException;
import com.digithink.zsretail.store.cashmovement.dto.CashMovementDTO;
import com.digithink.zsretail.store.cashmovement.dto.OpeningFloatDTO;
import com.digithink.zsretail.store.cashmovement.model.ClosingChoice;
import com.digithink.zsretail.store.cashmovement.model.ClosingWithdrawal;
import com.digithink.zsretail.store.cashmovement.model.OpeningFloat;
import com.digithink.zsretail.store.cashmovement.repository.ClosingWithdrawalRepository;
import com.digithink.zsretail.store.cashmovement.repository.OpeningFloatRepository;
import com.digithink.zsretail.store.cashmovement.service.DrawerHandoverService;
import com.digithink.zsretail.store.model.SessionCashCount;
import com.digithink.zsretail.store.posmachine.DrawerOwner;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.posmachine.model.SessionPosMachine;
import com.digithink.zsretail.store.repository.SessionCashCountRepository;
import com.digithink.zsretail.store.support.InMemoryRfid;

/**
 * 3.2.0, the opening amount: the responsible's required "Retrait du tiroir" at the verification of a closed session
 * (three choices, the amount's limits), the float proposed at the next opening on the same drawer (the POS machine or
 * the cashier, the most recent closed session only) and the écart d'ouverture; the sessions verified before this step
 * propose nothing. The real CashierSessionService (openSession, verifySession) and DrawerHandoverService over in-memory
 * tables.
 */
class DrawerHandoverTest {

	private final InMemoryRfid rfid = new InMemoryRfid();
	private final List<CashierSession> sessions = new ArrayList<>();
	private final Map<Long, ClosingWithdrawal> withdrawals = new LinkedHashMap<>();
	private final Map<Long, OpeningFloat> openings = new LinkedHashMap<>();
	/** Step A: the openings made while the previous session was not verified, and the store's settings. */
	private final Map<Long, com.digithink.zsretail.store.cashmovement.model.OpeningUnverified> unverifiedRows = new LinkedHashMap<>();
	private final Map<String, String> settings = new HashMap<>();
	private final List<SessionCashCount> counts = new ArrayList<>();
	private final PaymentMethod cash = new PaymentMethod();
	private final PaymentMethod meal = new PaymentMethod();
	private final UserAccount amira = user(1L, "amira");
	private final UserAccount sami = user(2L, "sami");
	private final UserAccount karim = user(3L, "karim");
	private CashierSessionService service;
	private DrawerHandoverService handover;
	private long nextId = 100;

	@BeforeEach
	void setUp() throws Exception {
		cash.setId(1L);
		cash.setCode("ESP");
		cash.setType(PaymentMethodType.CLIENT_ESPECES);
		meal.setId(2L);
		meal.setCode("TR");

		CashierSessionRepository sessionRepo = proxy(CashierSessionRepository.class, (m, a) -> {
			switch (m) {
				case "findByCashierAndStatus":
					return sessions.stream().filter(s -> s.getCashier() == a[0] && s.getStatus() == a[1]).findFirst();
				case "countByOpenedAtGreaterThanEqual":
					return (long) sessions.size();
				case "findById":
				case "findByIdForUpdate":
					return sessions.stream().filter(s -> s.getId().equals(a[0])).findFirst();
				case "save": {
					CashierSession s = (CashierSession) a[0];
					if (s.getId() == null) {
						s.setId(nextId++);
						sessions.add(s);
					}
					return s;
				}
				default:
					return UNHANDLED;
			}
		});
		ClosingWithdrawalRepository withdrawalRepo = proxy(ClosingWithdrawalRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					ClosingWithdrawal w = (ClosingWithdrawal) a[0];
					w.setSessionId(w.getSession().getId());
					withdrawals.put(w.getSessionId(), w);
					return w;
				}
				case "findById":
					return Optional.ofNullable(withdrawals.get(a[0]));
				case "findByDoneAtGreaterThanEqualAndDoneAtLessThanOrderByDoneAtAsc":
					return withdrawals.values().stream().filter(w -> !w.getDoneAt().isBefore((LocalDateTime) a[0])
							&& w.getDoneAt().isBefore((LocalDateTime) a[1])).collect(Collectors.toList());
				case "lastClosedOfCashier":
					return last(sessions.stream().filter(s -> s.getCashier() == a[0]), (Collection<?>) a[1], (Pageable) a[2]);
				case "lastClosedOfMachine":
					return last(rfid.sessionMachines.values().stream()
							.filter(l -> l.getPosMachine().getId().equals(a[0])).map(SessionPosMachine::getSession),
							(Collection<?>) a[1], (Pageable) a[2]);
				default:
					return UNHANDLED;
			}
		});
		OpeningFloatRepository openingRepo = proxy(OpeningFloatRepository.class, (m, a) -> {
			switch (m) {
				case "save": {
					OpeningFloat o = (OpeningFloat) a[0];
					o.setSessionId(o.getSession().getId());
					openings.put(o.getSessionId(), o);
					return o;
				}
				case "findById":
					return Optional.ofNullable(openings.get(a[0]));
				case "findBySessionIdIn":
					return openings.values().stream().filter(o -> ((Collection<?>) a[0]).contains(o.getSessionId()))
							.collect(Collectors.toList());
				default:
					return UNHANDLED;
			}
		});
		com.digithink.zsretail.store.cashmovement.repository.OpeningUnverifiedRepository unverifiedRepo = proxy(
				com.digithink.zsretail.store.cashmovement.repository.OpeningUnverifiedRepository.class, (m, a) -> {
					if ("save".equals(m)) {
						com.digithink.zsretail.store.cashmovement.model.OpeningUnverified row =
								(com.digithink.zsretail.store.cashmovement.model.OpeningUnverified) a[0];
						row.setSessionId(row.getSession().getId());
						unverifiedRows.put(row.getSessionId(), row);
						return row;
					}
					return "findById".equals(m) ? Optional.ofNullable(unverifiedRows.get(a[0])) : UNHANDLED;
				});
		handover = new DrawerHandoverService(withdrawalRepo, openingRepo, rfid.sessionMachineService, unverifiedRepo);

		ApplicationModeService mode = new ApplicationModeService();
		inject(mode, ApplicationModeService.class, "environment", new MockEnvironment());
		Method initOwnership = ApplicationModeService.class.getDeclaredMethod("initOwnership");
		initOwnership.setAccessible(true);
		initOwnership.invoke(mode);

		service = new CashierSessionService();
		inject(service, CashierSessionService.class, "cashierSessionRepository", sessionRepo);
		inject(service, CashierSessionService.class, "applicationModeService", mode);
		inject(service, CashierSessionService.class, "sessionPosMachineService", rfid.sessionMachineService);
		inject(service, CashierSessionService.class, "drawerHandoverService", handover);
		inject(service, CashierSessionService.class, "paymentMethodRepository", proxy(PaymentMethodRepository.class,
				(m, a) -> {
					if ("findByType".equals(m)) return a[0] == PaymentMethodType.CLIENT_ESPECES ? Optional.of(cash) : Optional.empty();
					if ("findByCode".equals(m)) return Arrays.asList(cash, meal).stream().filter(p -> p.getCode().equals(a[0])).findFirst();
					return UNHANDLED;
				}));
		inject(service, CashierSessionService.class, "sessionCashCountRepository", proxy(SessionCashCountRepository.class,
				(m, a) -> {
					if ("findByCashierSession".equals(m)) {
						return counts.stream().filter(c -> c.getCashierSession() == a[0]).collect(Collectors.toList());
					}
					if ("save".equals(m)) {
						counts.add((SessionCashCount) a[0]);
						return a[0];
					}
					return UNHANDLED;
				}));
		inject(service, CashierSessionService.class, "generalSetupRepository", proxy(
				com.digithink.zsretail.core.repository.GeneralSetupRepository.class, (m, a) -> {
					if (!"findByCode".equals(m)) return UNHANDLED;
					if (!settings.containsKey(a[0])) return Optional.empty();
					com.digithink.zsretail.core.model.GeneralSetup row = new com.digithink.zsretail.core.model.GeneralSetup();
					row.setCode((String) a[0]);
					row.setValeur(settings.get(a[0]));
					return Optional.of(row);
				}));
		inject(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "tester";
			}
		});
	}

	private static List<CashierSession> last(java.util.stream.Stream<CashierSession> of, Collection<?> statuses,
			Pageable page) {
		return of.filter(s -> statuses.contains(s.getStatus()) && s.getClosedAt() != null)
				.sorted(Comparator.comparing(CashierSession::getClosedAt).reversed()).limit(page.getPageSize())
				.collect(Collectors.toList());
	}

	private static UserAccount user(Long id, String name) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(name);
		user.setFullName(name);
		return user;
	}

	private static void inject(Object target, Class<?> owner, String field, Object value) throws Exception {
		Field f = owner.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	/** A closed session of this cashier, closed at this hour of 2026-11-14 (on this machine, or none). */
	private CashierSession closed(UserAccount cashier, int hour, PosMachine machine) {
		CashierSession session = new CashierSession();
		session.setId(nextId++);
		session.setSessionNumber("SESSION" + session.getId());
		session.setCashier(cashier);
		session.setOpeningCash(50.0);
		session.setOpenedAt(LocalDateTime.of(2026, 11, 14, hour - 1, 0));
		session.setClosedAt(LocalDateTime.of(2026, 11, 14, hour, 0));
		session.setStatus(SessionStatus.CLOSED);
		sessions.add(session);
		if (machine != null) {
			rfid.sessionMachineRepository.save(new SessionPosMachine(session, machine));
		}
		return session;
	}

	private static Map<String, Object> line(String code, double amount) {
		Map<String, Object> line = new HashMap<>();
		line.put("code", code);
		line.put("amount", amount);
		return line;
	}

	/** The responsible verifies: 100 in cash and 15 of meal tickets counted, with this answer. */
	private CashierSession verify(CashierSession session, String choice, Double amount) throws Exception {
		return service.verifySession(session.getId(), 115.0, null, karim, Arrays.asList(line("ESP", 100.0),
				line("TR", 15.0)), choice, amount);
	}

	// ─── At the verification ─────────────────────────────────────────

	@Test
	@DisplayName("The three choices: everything (left 0), nothing (left the cash counted), an amount; on the drawer's cash only")
	void threeChoices() throws Exception {
		CashierSession all = closed(amira, 10, null);
		verify(all, "ALL", null);
		assertEquals(ClosingChoice.ALL, withdrawals.get(all.getId()).getChoice());
		assertEquals(100.0, withdrawals.get(all.getId()).getCashCounted(), 1e-9, "the cash line, not the meal tickets");
		assertEquals(100.0, withdrawals.get(all.getId()).getAmountTaken(), 1e-9);
		assertEquals(0.0, withdrawals.get(all.getId()).getAmountLeft(), 1e-9);
		assertEquals(all.getSessionNumber() + "-PC", withdrawals.get(all.getId()).getSlipNumber());
		assertEquals(karim, withdrawals.get(all.getId()).getDoneBy());

		CashierSession none = closed(sami, 11, null);
		verify(none, "NONE", null);
		assertEquals(0.0, withdrawals.get(none.getId()).getAmountTaken(), 1e-9);
		assertEquals(100.0, withdrawals.get(none.getId()).getAmountLeft(), 1e-9);
		assertNull(withdrawals.get(none.getId()).getSlipNumber(), "nothing taken: no slip");

		CashierSession some = closed(amira, 12, null);
		verify(some, "amount", 60.1234);
		assertEquals(60.123, withdrawals.get(some.getId()).getAmountTaken(), 1e-9, "to the millime");
		assertEquals(39.877, withdrawals.get(some.getId()).getAmountLeft(), 1e-9);
		assertEquals(SessionStatus.TERMINATED, some.getStatus());
		assertEquals(115.0, some.getResponsibleClosureCash(), 1e-9, "the verification's own amounts do not move");
	}

	@Test
	@DisplayName("The amount: above 0, not above the cash counted (equal passes); a session with no cash takes nothing")
	void amountLimits() {
		for (Double amount : new Double[] { null, 0.0, -1.0, 0.0004, 100.001 }) {
			CashMovementException refused = assertThrows(CashMovementException.class,
					() -> DrawerHandoverService.check("AMOUNT", amount, 100.0), "amount " + amount);
			assertEquals("CLOSING_WITHDRAWAL_AMOUNT_INVALID", refused.getCode());
			assertEquals("100.000", refused.getParams().get("max"));
		}
		assertEquals(0.0, DrawerHandoverService.check("AMOUNT", 100.0, 100.0).left.doubleValue(), 1e-9, "all of it");
		assertThrows(CashMovementException.class, () -> DrawerHandoverService.check("AMOUNT", 1.0, 0.0));
		assertEquals(0.0, DrawerHandoverService.check("ALL", null, 0.0).taken.doubleValue(), 1e-9);
	}

	@Test
	@DisplayName("The answer is required: a verification without it (or with an unknown choice) is refused, nothing changed")
	void requiredAnswer() throws Exception {
		CashierSession session = closed(amira, 10, null);
		CashMovementException missing = assertThrows(CashMovementException.class, () -> verify(session, null, null));
		assertEquals("CLOSING_WITHDRAWAL_REQUIRED", missing.getCode());
		assertEquals("CLOSING_WITHDRAWAL_CHOICE_INVALID",
				assertThrows(CashMovementException.class, () -> verify(session, "SOME", null)).getCode());
		assertEquals(SessionStatus.CLOSED, session.getStatus(), "not verified");
		assertNull(session.getVerifiedBy());
		assertTrue(counts.isEmpty() && withdrawals.isEmpty());
		// and a verification is never redone: a TERMINATED session is refused as before
		verify(session, "NONE", null);
		assertThrows(IllegalStateException.class, () -> verify(session, "ALL", null));
		assertEquals(ClosingChoice.NONE, withdrawals.get(session.getId()).getChoice(), "the first answer stays");
	}

	@Test
	@DisplayName("3.4.0, event log step 2: the verification (SESSION_VERIFIED by the responsible, the withdrawal), the next"
			+ " opening (SESSION_OPENED) and its gap (SESSION_OPENING_GAP); an opening without a proposal has no gap")
	void events() throws Exception {
		com.digithink.zsretail.core.support.InMemoryEventLog log = new com.digithink.zsretail.core.support.InMemoryEventLog()
				.logged(karim);
		inject(service, CashierSessionService.class, "tillEvents",
				new com.digithink.zsretail.store.eventlog.TillEvents(log.eventLog()));
		rfid.drawerOwner = DrawerOwner.CASHIER;
		CashierSession closed = closed(amira, 10, null);
		verify(closed, "AMOUNT", 60.0); // 40 left
		com.digithink.zsretail.core.eventlog.model.EventLogEntry verified = log.only("SESSION_VERIFIED");
		assertEquals("karim", verified.getUserLogin());
		assertEquals(closed.getId(), verified.getSessionId());
		assertTrue(verified.getDetails().contains("Retrait du tiroir : 60,000 retiré, 40,000 laissé"), verified.getDetails());

		log.logged(amira);
		CashierSession next = service.openSession(amira, 45.5, "192.168.1.99");
		com.digithink.zsretail.core.eventlog.model.EventLogEntry opened = log.only("SESSION_OPENED");
		assertEquals(next.getId(), opened.getSessionId());
		assertEquals(45.5, opened.getAmount(), 1e-9);
		com.digithink.zsretail.core.eventlog.model.EventLogEntry gap = log.only("SESSION_OPENING_GAP");
		assertEquals(5.5, gap.getAmount(), 1e-9);
		assertEquals("40,000", gap.getOldValue());
		assertEquals("45,500", gap.getNewValue());

		service.openSession(sami, 30.0, "192.168.1.98");
		assertEquals(java.util.Arrays.asList("SESSION_VERIFIED", "SESSION_OPENED", "SESSION_OPENING_GAP", "SESSION_OPENED"),
				log.codes(), "no proposal: no gap");
	}

	// ─── At the next opening ─────────────────────────────────────────

	@Test
	@DisplayName("The drawer of the cashier: the float left at his last verification is proposed to his next opening, from"
			+ " any PC; not to another cashier; the écart is opening - proposed")
	void proposalByCashier() throws Exception {
		rfid.drawerOwner = DrawerOwner.CASHIER;
		verify(closed(amira, 10, null), "AMOUNT", 60.0); // 40 left
		Optional<OpeningFloatDTO> proposal = service.openingProposal(amira, "192.168.1.99");
		assertEquals(40.0, proposal.get().getProposedFloat(), 1e-9);
		assertFalse(service.openingProposal(sami, "192.168.1.99").isPresent(), "another cashier's drawer");

		CashierSession next = service.openSession(amira, 45.5, "192.168.1.99");
		OpeningFloat opening = openings.get(next.getId());
		assertEquals(40.0, opening.getProposedFloat(), 1e-9);
		assertEquals(5.5, opening.getOpeningGap(), 1e-9, "45.5 typed - 40 proposed");
		assertEquals(next.getId() - 1, opening.getSourceSession().getId());
		assertEquals(5.5, handover.openingGapsBySession(Arrays.asList(next.getId())).get(next.getId()), 1e-9);
		assertEquals(40.0, handover.openingOf(next).getProposedFloat(), 1e-9);

		CashierSession samiSession = service.openSession(sami, 30.0, "192.168.1.98");
		assertFalse(openings.containsKey(samiSession.getId()), "nothing proposed: no écart");
	}

	@Test
	@DisplayName("The drawer of the POS machine: the float of the machine's last verified session, whoever opens on it")
	void proposalByMachine() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		rfid.machine("Caisse 2", "192.168.1.12", null);
		verify(closed(amira, 10, caisse1), "NONE", null); // 100 left in Caisse 1
		assertEquals(100.0, service.openingProposal(sami, "192.168.1.11").get().getProposedFloat(), 1e-9);
		assertFalse(service.openingProposal(amira, "192.168.1.12").isPresent(), "Caisse 2's drawer");
		assertFalse(service.openingProposal(amira, "192.168.1.99").isPresent(), "a PC not registered");

		CashierSession next = service.openSession(sami, 100.0, "192.168.1.11");
		assertEquals(0.0, openings.get(next.getId()).getOpeningGap(), 1e-9, "kept: écart 0");
	}

	@Test
	@DisplayName("Only the most recent closed session of the drawer: not verified, or verified with nothing left, proposes"
			+ " nothing; never an older one")
	void mostRecentOnly() throws Exception {
		rfid.drawerOwner = DrawerOwner.CASHIER;
		verify(closed(amira, 9, null), "NONE", null); // an older session left 100
		CashierSession recent = closed(amira, 11, null); // closed, not verified
		assertNull(service.openingProposal(amira, null).get().getProposedFloat(), "the most recent is not verified");
		assertEquals(recent.getSessionNumber(), service.openingProposal(amira, null).get().getUnverifiedPrevious()
				.getPreviousSessionNumber(), "step A: it says so");
		verify(recent, "ALL", null); // verified, nothing left
		assertFalse(service.openingProposal(amira, null).isPresent(), "nothing left in the drawer");
	}

	@Test
	@DisplayName("A session verified before this step (no answer) proposes nothing; the next session already open before"
			+ " the verification gets no proposal and no écart")
	void beforeThisStepAndAlreadyOpen() throws Exception {
		rfid.drawerOwner = DrawerOwner.CASHIER;
		CashierSession old = closed(amira, 9, null);
		old.setStatus(SessionStatus.TERMINATED); // verified before 3.2.0: no row
		assertFalse(service.openingProposal(amira, null).isPresent());

		CashierSession first = closed(sami, 10, null);
		CashierSession second = service.openSession(sami, 20.0, null); // opened before the first is verified
		verify(first, "NONE", null);
		assertFalse(openings.containsKey(second.getId()), "no proposal, no écart");
		assertEquals(SessionStatus.OPENED, second.getStatus());
	}

	@Test
	@DisplayName("Journal's day: a closing withdrawal that took something is a line of its own type (its slip, the"
			+ " responsible); 'Rien retirer' is none")
	void journalLines() throws Exception {
		CashierSession all = closed(amira, 10, null);
		verify(all, "AMOUNT", 30.0);
		verify(closed(sami, 11, null), "NONE", null);
		List<CashMovementDTO> lines = handover.closingLinesOfDay(withdrawals.get(all.getId()).getDoneAt().toLocalDate());
		assertEquals(1, lines.size());
		assertEquals("PRELEVEMENT_CLOTURE", lines.get(0).getType());
		assertEquals(30.0, lines.get(0).getAmount(), 1e-9);
		assertEquals(all.getSessionNumber() + "-PC", lines.get(0).getNumber());
		assertEquals("karim", lines.get(0).getAuthorisedByName());
		// never in the drawer's in / out totals
		assertNull(DayJournalService.cashMovementTotals(lines));
		assertEquals(Collections.emptyList(), handover.closingLinesOfDay(all.getClosedAt().toLocalDate().minusDays(1)));
	}

	@Test
	@DisplayName("A session without any payment (opened 100, apport 500, no sale): the responsible counts 600 in cash, takes"
			+ " 500, 100 stays")
	void sessionWithoutPayment() throws Exception {
		CashierSession session = closed(amira, 10, null);
		session.setOpeningCash(100.0);
		service.verifySession(session.getId(), 600.0, null, karim, Arrays.asList(line("ESP", 600.0)), "AMOUNT", 500.0);
		ClosingWithdrawal answer = withdrawals.get(session.getId());
		assertEquals(600.0, answer.getCashCounted(), 1e-9, "the cash line, though the session took no cash payment");
		assertEquals(500.0, answer.getAmountTaken(), 1e-9);
		assertEquals(100.0, answer.getAmountLeft(), 1e-9);
	}

	@Test
	@DisplayName("Day level: 'Laissé dans le tiroir' is what is in the drawers at the end (each drawer's last answer), 'Remis"
			+ " à la clôture' a sum")
	void leftPerDrawerAtTheEnd() {
		List<com.digithink.zsretail.store.dto.SessionReportTicketDTO> reports = Arrays.asList(
				report(1L, 10, "C1", 60.0, 200.0), // the drawer C1 at 10:00: 200 left
				report(2L, 12, "C1", 190.0, 10.0), // C1 again at 12:00: 10 left (the last)
				report(3L, 11, "C2", 0.0, 5.0)); // another drawer: 5 left
		com.digithink.zsretail.store.dto.SessionReportTicketDTO.DrawerHandover day = StoreReportService.drawerHandover(reports);
		assertEquals(250.0, day.getClosingTaken(), 1e-9, "a sum");
		assertEquals(15.0, day.getClosingLeft(), 1e-9, "10 (C1's last) + 5 (C2), not 215");
		assertNull(day.getOpeningGap());
	}

	private static com.digithink.zsretail.store.dto.SessionReportTicketDTO report(Long id, int hour, String drawer,
			double taken, double left) {
		com.digithink.zsretail.store.dto.SessionReportTicketDTO report = new com.digithink.zsretail.store.dto.SessionReportTicketDTO();
		report.setSessionId(id);
		report.setClosedAt(LocalDateTime.of(2026, 11, 14, hour, 0));
		com.digithink.zsretail.store.cashmovement.dto.ClosingWithdrawalDTO w =
				new com.digithink.zsretail.store.cashmovement.dto.ClosingWithdrawalDTO();
		w.setAmountTaken(taken);
		w.setAmountLeft(left);
		w.setDrawerKey(drawer);
		w.setDoneAt(LocalDateTime.of(2026, 11, 14, hour, 30));
		report.setClosingWithdrawal(w);
		return report;
	}

	// ─── Step A of the differences: the previous session not verified ──

	private static final String VERIFY_PREVIOUS = "VERIFY_PREVIOUS_SESSION_BEFORE_OPENING";

	@Test
	@DisplayName("Step A, setting off: the opening goes on, the unverified previous session is kept on the new one and the"
			+ " proposal says it (nothing proposed)")
	void unverifiedPreviousSettingOff() throws Exception {
		rfid.drawerOwner = DrawerOwner.CASHIER;
		CashierSession previous = closed(amira, 10, null); // closed, not verified
		OpeningFloatDTO proposal = service.openingProposal(amira, null).get();
		assertNull(proposal.getProposedFloat(), "nothing proposed");
		assertEquals(previous.getSessionNumber(), proposal.getUnverifiedPrevious().getPreviousSessionNumber());
		assertEquals(previous.getClosedAt(), proposal.getUnverifiedPrevious().getPreviousClosedAt());
		assertEquals(Boolean.FALSE, proposal.getVerifyPreviousRequired());

		CashierSession next = service.openSession(amira, 50.0, null);
		assertEquals(SessionStatus.OPENED, next.getStatus());
		assertEquals(previous, unverifiedRows.get(next.getId()).getPreviousSession(), "the fact kept");
		assertEquals(previous.getSessionNumber(), handover.unverifiedOf(next).getPreviousSessionNumber());
		assertFalse(openings.containsKey(next.getId()), "no float proposed, no écart");
	}

	@Test
	@DisplayName("3.4.0, event log step 3: the opening over an unverified previous session writes"
			+ " SESSION_OPENED_PREVIOUS_UNVERIFIED; refused (setting on), it writes none; a verified previous one, none")
	void unverifiedPreviousEvent() throws Exception {
		com.digithink.zsretail.core.support.InMemoryEventLog log = new com.digithink.zsretail.core.support.InMemoryEventLog()
				.logged(amira);
		inject(service, CashierSessionService.class, "tillEvents",
				new com.digithink.zsretail.store.eventlog.TillEvents(log.eventLog()));
		rfid.drawerOwner = DrawerOwner.CASHIER;
		CashierSession previous = closed(amira, 10, null); // closed, not verified
		settings.put(VERIFY_PREVIOUS, "true");
		assertThrows(com.digithink.zsretail.store.posmachine.DrawerRuleException.class,
				() -> service.openSession(amira, 50.0, null));
		assertTrue(log.rows.isEmpty(), "refused: no event");

		settings.put(VERIFY_PREVIOUS, "false");
		CashierSession next = service.openSession(amira, 50.0, null);
		com.digithink.zsretail.core.eventlog.model.EventLogEntry row = log.only("SESSION_OPENED_PREVIOUS_UNVERIFIED");
		assertEquals(next.getId(), row.getSessionId());
		assertEquals(previous.getSessionNumber(), row.getOldValue());
		assertTrue(row.getDetails().contains("la session précédente du tiroir, " + previous.getSessionNumber()),
				row.getDetails());

		service.openSession(sami, 30.0, null); // no previous session of his drawer
		assertEquals(1, log.codes().stream().filter("SESSION_OPENED_PREVIOUS_UNVERIFIED"::equals).count());
	}

	@Test
	@DisplayName("Step A, setting on: refused (409 code PREVIOUS_SESSION_NOT_VERIFIED naming the session); opened once it"
			+ " is verified")
	void unverifiedPreviousSettingOn() throws Exception {
		rfid.drawerOwner = DrawerOwner.CASHIER;
		settings.put(VERIFY_PREVIOUS, "true");
		CashierSession previous = closed(amira, 10, null);
		assertEquals(Boolean.TRUE, service.openingProposal(amira, null).get().getVerifyPreviousRequired());
		com.digithink.zsretail.store.posmachine.DrawerRuleException refused = assertThrows(
				com.digithink.zsretail.store.posmachine.DrawerRuleException.class, () -> service.openSession(amira, 50.0, null));
		assertEquals("PREVIOUS_SESSION_NOT_VERIFIED", refused.getCode());
		assertEquals(previous.getSessionNumber(), refused.getParams().get("session"));
		assertEquals("—", refused.getParams().get("posMachine"), "no POS machine known");
		assertTrue(refused.getMessage().contains(previous.getSessionNumber()), refused.getMessage());
		assertEquals(1, sessions.size(), "nothing opened");

		verify(previous, "NONE", null);
		CashierSession next = service.openSession(amira, 100.0, null);
		assertEquals(SessionStatus.OPENED, next.getStatus());
		assertFalse(unverifiedRows.containsKey(next.getId()));
		assertEquals(100.0, openings.get(next.getId()).getProposedFloat(), 1e-9, "the proposal works again");
	}

	@Test
	@DisplayName("Step A, the drawer of the POS machine: the machine's previous session counts, whoever opens; the message"
			+ " names the machine; another machine is free")
	void unverifiedPreviousOnMachine() throws Exception {
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		settings.put(VERIFY_PREVIOUS, "true");
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		rfid.machine("Caisse 2", "192.168.1.12", null);
		CashierSession previous = closed(amira, 10, caisse1);
		com.digithink.zsretail.store.posmachine.DrawerRuleException refused = assertThrows(
				com.digithink.zsretail.store.posmachine.DrawerRuleException.class,
				() -> service.openSession(sami, 50.0, "192.168.1.11"));
		assertEquals("Caisse 1", refused.getParams().get("posMachine"));
		assertEquals(previous.getSessionNumber(), refused.getParams().get("session"));
		assertEquals("Caisse 1", service.openingProposal(sami, "192.168.1.11").get().getUnverifiedPrevious()
				.getPreviousPosMachineName());
		CashierSession other = service.openSession(amira, 20.0, "192.168.1.12");
		assertEquals(SessionStatus.OPENED, other.getStatus(), "Caisse 2's drawer has no previous session");
	}

	@Test
	@DisplayName("Step A: the first session of a drawer, a verified previous session, and an older unverified one behind a"
			+ " verified one: nothing said, nothing refused")
	void nothingToSay() throws Exception {
		rfid.drawerOwner = DrawerOwner.CASHIER;
		settings.put(VERIFY_PREVIOUS, "true");
		assertFalse(service.openingProposal(amira, null).isPresent(), "the first session of the drawer");
		closed(sami, 9, null); // an older one, never verified
		verify(closed(sami, 11, null), "ALL", null); // the most recent, verified, nothing left
		assertFalse(service.openingProposal(sami, null).isPresent(), "the older unverified one does not count");
		CashierSession next = service.openSession(sami, 30.0, null);
		assertFalse(unverifiedRows.containsKey(next.getId()));
	}

	@Test
	@DisplayName("The drawer of an answer: the POS machine when the drawer belongs to it, else the cashier")
	void drawerOfAnAnswer() throws Exception {
		PosMachine caisse1 = rfid.machine("Caisse 1", "192.168.1.11", null);
		CashierSession session = closed(amira, 10, caisse1);
		verify(session, "NONE", null);
		rfid.drawerOwner = DrawerOwner.CASHIER;
		assertEquals("C1", handover.withdrawalOf(session).getDrawerKey());
		rfid.drawerOwner = DrawerOwner.POS_MACHINE;
		assertEquals("M" + caisse1.getId(), handover.withdrawalOf(session).getDrawerKey());
	}

	@Test
	@DisplayName("Fixes after the test round: the real opening after a clôture that took everything says the drawer was left at"
			+ " 0; no SESSION_OPENING_GAP (the 3.2.0 écart unchanged: nothing proposed)")
	void openingAfterAllTaken() throws Exception {
		com.digithink.zsretail.core.support.InMemoryEventLog log = new com.digithink.zsretail.core.support.InMemoryEventLog()
				.logged(amira);
		inject(service, CashierSessionService.class, "tillEvents",
				new com.digithink.zsretail.store.eventlog.TillEvents(log.eventLog()));
		rfid.drawerOwner = DrawerOwner.CASHIER;
		CashierSession closed = closed(amira, 10, null);
		verify(closed, "ALL", null);
		service.openSession(amira, 20.0, null);
		com.digithink.zsretail.core.eventlog.model.EventLogEntry opened = log.only("SESSION_OPENED");
		assertEquals("Session ouverte avec un fond de caisse de 20,000 (tiroir laissé à 0,000 à la clôture de "
				+ closed.getSessionNumber() + ").", opened.getDetails());
		assertTrue(!log.codes().contains("SESSION_OPENING_GAP"));
	}
}
