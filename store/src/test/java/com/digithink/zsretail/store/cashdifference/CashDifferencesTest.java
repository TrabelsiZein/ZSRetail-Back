package com.digithink.zsretail.store.cashdifference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.ReturnHeader;
import com.digithink.zsretail.core.model.SalesHeader;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.CashierRow;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.Line;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;
import com.digithink.zsretail.store.posmachine.model.PosMachine;
import com.digithink.zsretail.store.support.InMemorySessionFigures;

/**
 * 3.2.0, the differences, step B: the page "Écarts de caisse" over in-memory tables (InMemorySessionFigures). Two
 * cashiers (Amira, Bilel), two POS machines, November 2026. Each session opens with 100 and sells 50 (one cancelled
 * sale of 30 left out): its system amount is 150, so a count of 145 is -5.
 */
class CashDifferencesTest {

	private static final LocalDate FROM = LocalDate.of(2026, 11, 1);
	private static final LocalDate TO = LocalDate.of(2026, 11, 30);

	private final UserAccount amira = user(1L, "Amira");
	private final UserAccount bilel = user(2L, "Bilel");
	private final PosMachine m1 = machine(10L, "Caisse 1");
	private final PosMachine m2 = machine(20L, "Caisse 2");

	private InMemorySessionFigures db;
	private CashDifferencesService service;
	private long nextId = 1000;

	@BeforeEach
	void setUp() {
		db = new InMemorySessionFigures();
		service = new CashDifferencesService();
		InMemorySessionFigures.set(service, "figures", db.repository);
		InMemorySessionFigures.set(service, "sessionFigures", db.service);
		InMemorySessionFigures.set(service, "drawerHandoverService", db.handover);
	}

	// ─── The five reasons, each alone ────────────────────────────────

	@Test
	@DisplayName("A cashier's difference at the fermeture alone: listed, the clôture's 0 not a difference")
	void closingDifferenceAlone() {
		CashierSession s = terminated(amira, 3, 145.0, 150.0);
		Line line = only(s);
		assertEquals(-5.0, line.getClosingDifference(), 1e-9);
		assertFalse(CashDifferencesService.nonZero(line.getFinalClosingDifference()));
		assertNull(line.getOpenedWithoutFinalClosing());
		assertFalse(line.isPendingFinalClosing());
	}

	@Test
	@DisplayName("A responsible's difference at the clôture alone: listed")
	void finalClosingDifferenceAlone() {
		Line line = only(terminated(amira, 3, 150.0, 160.0));
		assertEquals(10.0, line.getFinalClosingDifference(), 1e-9);
		assertFalse(CashDifferencesService.nonZero(line.getClosingDifference()));
	}

	@Test
	@DisplayName("An écart d'ouverture alone: listed with the list's écart")
	void openingGapAlone() {
		CashierSession s = terminated(amira, 3, 150.0, 150.0);
		db.openingGap(s, 2.0);
		assertEquals(2.0, only(s).getOpeningGap(), 1e-9);
	}

	@Test
	@DisplayName("Opened without the previous session clôturée alone: listed, the previous session named")
	void openedWithoutFinalClosingAlone() {
		CashierSession previous = terminated(amira, 2, 150.0, 150.0);
		CashierSession s = terminated(amira, 3, 150.0, 150.0);
		db.openedWithoutFinalClosing(s, previous);
		CashDifferencesDTO result = all(true);
		assertEquals(1, result.getListedSessions(), "the previous session has no reason of its own");
		Line line = result.getContent().get(0);
		assertEquals(s.getId(), line.getSessionId());
		assertEquals(previous.getSessionNumber(), line.getOpenedWithoutFinalClosing().getPreviousSessionNumber());
	}

	@Test
	@DisplayName("Fermée and not clôturée alone: listed, 'En attente de clôture'")
	void pendingAlone() {
		CashierSession s = session(amira, 3, SessionStatus.CLOSED, 150.0, null);
		Line line = only(s);
		assertTrue(line.isPendingFinalClosing());
		assertNull(line.getFinalClosingDifference());
		assertEquals("En attente de clôture", CashDifferencesExcel.remarks(line));
	}

	@Test
	@DisplayName("A session with none of the five (clôturée with 0 and 0, or still open) is not listed")
	void noneNotListed() {
		terminated(amira, 3, 150.0, 150.0);
		session(bilel, 4, SessionStatus.OPENED, null, null);
		CashDifferencesDTO result = all(true);
		assertEquals(2, result.getPeriodSessions());
		assertEquals(0, result.getListedSessions());
		assertTrue(result.getContent().isEmpty());
		assertEquals(0, result.getTotal().getSessions());
		assertTrue(result.getCashiers().isEmpty());
	}

	@Test
	@DisplayName("A session with several reasons: one line holding them all")
	void several() {
		CashierSession previous = session(amira, 2, SessionStatus.CLOSED, 150.0, null);
		CashierSession s = terminated(amira, 3, 140.0, 145.0);
		db.openingGap(s, -3.0);
		db.openedWithoutFinalClosing(s, previous);
		Line line = all(true).getContent().stream().filter(l -> l.getSessionId().equals(s.getId())).findFirst().get();
		assertEquals(-10.0, line.getClosingDifference(), 1e-9);
		assertEquals(-5.0, line.getFinalClosingDifference(), 1e-9);
		assertEquals(-3.0, line.getOpeningGap(), 1e-9);
		assertNotNull(line.getOpenedWithoutFinalClosing());
		assertFalse(line.isPendingFinalClosing());
	}

	// ─── Filters, order, paging ──────────────────────────────────────

	@Test
	@DisplayName("Filters: the period (both days included), the cashier, the POS machine; newest first")
	void filters() {
		CashierSession a1 = terminated(amira, 1, 145.0, 150.0);
		CashierSession a30 = terminated(amira, 30, 145.0, 150.0);
		CashierSession b15 = terminated(bilel, 15, 145.0, 150.0);
		CashierSession october = session(amira, 0, SessionStatus.TERMINATED, 145.0, 150.0);
		october.setOpenedAt(LocalDate.of(2026, 10, 31).atTime(20, 0));
		db.machineOf.put(a1.getId(), m1);
		db.machineOf.put(b15.getId(), m2);

		CashDifferencesDTO all = all(true);
		assertEquals(ids(a30, b15, a1), ids(all.getContent()), "newest first, October left out");
		assertEquals(3, all.getPeriodSessions());
		assertEquals("Caisse 1", all.getContent().get(2).getPosMachineName());
		assertEquals(ids(a30, a1), ids(read(new CashDifferencesService.Filter(FROM, TO, null, 1L)).getContent()));
		assertEquals(ids(b15), ids(read(new CashDifferencesService.Filter(FROM, TO, 20L, null)).getContent()));
		assertEquals(ids(a1), ids(read(new CashDifferencesService.Filter(FROM, FROM, null, null)).getContent()),
				"one day, its 23:59:59 included");
	}

	@Test
	@DisplayName("Light: one query per kind for the whole period (never one per session)")
	void oneQueryPerKind() {
		for (int day = 1; day <= 20; day++) {
			terminated(day % 2 == 0 ? amira : bilel, day, 145.0, 150.0);
		}
		assertEquals(20, all(true).getListedSessions());
		for (String query : new String[] { "openedBetween", "saleAmounts", "returnAmounts", "scannedTickets",
				"cashMovements" }) {
			assertEquals(1, db.calls.get(query), query);
		}
	}

	// ─── The summary ─────────────────────────────────────────────────

	@Test
	@DisplayName("Summary: per cashier the signed sums and the shortages alone (-20 and +5 = -15, shortage -20); equal"
			+ " to the sum of the lines over every page")
	void summaryOverPages() {
		terminated(amira, 2, 130.0, 150.0); // fermeture -20
		terminated(amira, 3, 155.0, 150.0); // fermeture +5
		CashierSession gapped = terminated(amira, 4, 150.0, 147.5); // clôture -2.5
		db.openingGap(gapped, 1.25);
		CashierSession b = terminated(bilel, 5, 150.1, 149.9); // +0.1, -0.1
		db.openedWithoutFinalClosing(b, gapped);
		session(bilel, 6, SessionStatus.CLOSED, 151.0, null); // pending, fermeture +1
		terminated(bilel, 7, 150.0, 150.0); // none

		CashDifferencesDTO first = read(new CashDifferencesService.Filter(FROM, TO, null, null), 0, 2);
		assertEquals(6, first.getPeriodSessions());
		assertEquals(5, first.getListedSessions());
		assertEquals(5, first.getTotalElements());
		assertEquals(3, first.getTotalPages());
		assertEquals(2, first.getContent().size());

		List<Line> everyPage = new ArrayList<>();
		for (int page = 0; page < first.getTotalPages(); page++) {
			CashDifferencesDTO result = read(new CashDifferencesService.Filter(FROM, TO, null, null), page, 2);
			assertEquals(first.getTotal(), result.getTotal(), "the summary is the period's, whatever the page");
			everyPage.addAll(result.getContent());
		}
		assertEquals(5, everyPage.size());
		CashierRow total = first.getTotal();
		assertEquals(sum(everyPage, Line::getClosingDifference), total.getClosingTotal(), 1e-9);
		assertEquals(sum(everyPage, Line::getFinalClosingDifference), total.getFinalClosingTotal(), 1e-9);
		assertEquals(sum(everyPage, Line::getOpeningGap), total.getOpeningGapTotal(), 1e-9);
		assertEquals(-13.9, total.getClosingTotal(), 1e-9, "-20 + 5 + 0.1 + 1");
		assertEquals(-20.0, total.getClosingShortage(), 1e-9);
		assertEquals(-2.6, total.getFinalClosingTotal(), 1e-9);
		assertEquals(-2.6, total.getFinalClosingShortage(), 1e-9);
		assertEquals(1.25, total.getOpeningGapTotal(), 1e-9);
		assertEquals(0.0, total.getOpeningGapShortage(), 1e-9);
		assertEquals(1, total.getOpenedWithoutFinalClosing());
		assertEquals(1, total.getPendingFinalClosing());

		Map<String, CashierRow> byName = first.getCashiers().stream()
				.collect(Collectors.toMap(CashierRow::getCashierName, r -> r));
		assertEquals(2, first.getCashiers().size());
		assertEquals("Amira", first.getCashiers().get(0).getCashierName(), "by name");
		CashierRow a = byName.get("Amira");
		assertEquals(3, a.getSessions());
		assertEquals(-15.0, a.getClosingTotal(), 1e-9);
		assertEquals(-20.0, a.getClosingShortage(), 1e-9);
		assertEquals(-2.5, a.getFinalClosingTotal(), 1e-9);
		CashierRow bRow = byName.get("Bilel");
		assertEquals(2, bRow.getSessions());
		assertEquals(1.1, bRow.getClosingTotal(), 1e-9);
		assertEquals(0.0, bRow.getClosingShortage(), 1e-9);
		assertEquals(-0.1, bRow.getFinalClosingShortage(), 1e-9);
		assertEquals(1, bRow.getOpenedWithoutFinalClosing());
		assertEquals(1, bRow.getPendingFinalClosing());
	}

	// ─── The Sessions list's numbers ─────────────────────────────────

	@Test
	@DisplayName("Each line's numbers are the Sessions list's line (SessionFiguresService.dashboards), returns included")
	void numbersOfTheList() {
		CashierSession s = terminated(amira, 3, 120.0, 125.0);
		ReturnHeader simple = new ReturnHeader();
		simple.setId(nextId++);
		simple.setCashierSession(s);
		simple.setReturnType(ReturnType.SIMPLE_RETURN);
		simple.setTotalReturnAmount(20.0);
		db.returns.add(simple);
		ReturnHeader voucher = new ReturnHeader();
		voucher.setId(nextId++);
		voucher.setCashierSession(s);
		voucher.setReturnType(ReturnType.RETURN_VOUCHER);
		voucher.setTotalReturnAmount(40.0);
		db.returns.add(voucher);
		db.openingGap(s, -0.5);
		SessionDashboardDTO list = db.service.dashboards(java.util.Arrays.asList(s)).get(0);
		Line line = only(s);
		assertEquals(-10.0, list.getCashDifference(), 1e-9, "100 + 50 - 20 (simple return only) = 130");
		assertEquals(list.getCashDifference(), line.getClosingDifference());
		assertEquals(list.getResponsibleDifference(), line.getFinalClosingDifference());
		assertEquals(list.getOpeningGap(), line.getOpeningGap());
	}

	// ─── "Voir montants session" ─────────────────────────────────────

	@Test
	@DisplayName("Without 'Voir montants session': the fermeture's difference of a session not clôturée is hidden and not"
			+ " summed; a clôturée one shows")
	void amountsHidden() {
		session(amira, 2, SessionStatus.CLOSED, 140.0, null); // -10, hidden
		terminated(amira, 3, 145.0, 150.0); // -5, shown
		CashDifferencesDTO result = all(false);
		assertTrue(result.isAmountsHidden());
		assertEquals(1, result.getHiddenClosingDifferences());
		Line closed = result.getContent().get(1);
		assertTrue(closed.isClosingDifferenceHidden());
		assertNull(closed.getClosingDifference());
		assertEquals(2, result.getListedSessions(), "still listed: en attente de clôture");
		assertEquals(-5.0, result.getTotal().getClosingTotal(), 1e-9);
		CashDifferencesDTO visible = all(true);
		assertEquals(-15.0, visible.getTotal().getClosingTotal(), 1e-9);
		assertEquals(0, visible.getHiddenClosingDifferences());
	}

	// ─── The period read by the endpoint ─────────────────────────────

	@Test
	@DisplayName("The endpoint's period: blank = the current month; a date that does not parse or to before from = 400")
	void period() {
		CashDifferencesService.Filter month = CashDifferencesAPI.filter(null, " ", null, null);
		assertEquals(YearMonth.now().atDay(1), month.from);
		assertEquals(YearMonth.now().atEndOfMonth(), month.to);
		assertNull(CashDifferencesAPI.filter("2026-13-01", null, null, null));
		assertNull(CashDifferencesAPI.filter("2026-11-10", "2026-11-09", null, null));
		assertNotNull(CashDifferencesAPI.filter("2026-11-10", "2026-11-10", 10L, 1L));
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private CashDifferencesDTO all(boolean amountsVisible) {
		return service.all(new CashDifferencesService.Filter(FROM, TO, null, null), amountsVisible);
	}

	private CashDifferencesDTO read(CashDifferencesService.Filter filter) {
		return service.all(filter, true);
	}

	private CashDifferencesDTO read(CashDifferencesService.Filter filter, int page, int size) {
		return service.page(filter, true, page, size);
	}

	private Line only(CashierSession session) {
		CashDifferencesDTO result = all(true);
		assertEquals(1, result.getListedSessions());
		Line line = result.getContent().get(0);
		assertEquals(session.getId(), line.getSessionId());
		return line;
	}

	private CashierSession terminated(UserAccount cashier, int day, Double posClosure, Double respClosure) {
		return session(cashier, day, SessionStatus.TERMINATED, posClosure, respClosure);
	}

	/** A session opened that day of November at 8:00 with 100, a sale of 50 and a cancelled one of 30. */
	private CashierSession session(UserAccount cashier, int day, SessionStatus status, Double posClosure,
			Double respClosure) {
		CashierSession session = new CashierSession();
		session.setId(nextId++);
		session.setSessionNumber("SESSION" + session.getId());
		session.setCashier(cashier);
		session.setOpeningCash(100.0);
		session.setOpenedAt(LocalDate.of(2026, 11, Math.max(1, day)).atTime(day == 1 ? 0 : 8, 0));
		if (day == 30) {
			session.setOpenedAt(LocalDate.of(2026, 11, 30).atTime(23, 59, 59));
		}
		session.setStatus(status);
		if (status != SessionStatus.OPENED) {
			session.setClosedAt(session.getOpenedAt().plusHours(1));
		}
		session.setPosUserClosureCash(posClosure);
		session.setResponsibleClosureCash(respClosure);
		db.sessions.add(session);
		sale(session, TransactionStatus.COMPLETED, 50.0);
		sale(session, TransactionStatus.CANCELLED, 30.0);
		return session;
	}

	private void sale(CashierSession session, TransactionStatus status, double total) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setCashierSession(session);
		sale.setStatus(status);
		sale.setTotalAmount(total);
		db.sales.add(sale);
	}

	private static double sum(List<Line> lines, Function<Line, Double> field) {
		BigDecimal sum = BigDecimal.ZERO;
		for (Line line : lines) {
			Double value = field.apply(line);
			if (value != null) {
				sum = sum.add(BigDecimal.valueOf(value));
			}
		}
		return sum.doubleValue();
	}

	private static List<Long> ids(CashierSession... sessions) {
		List<Long> ids = new ArrayList<>();
		for (CashierSession s : sessions) {
			ids.add(s.getId());
		}
		return ids;
	}

	private static List<Long> ids(List<Line> lines) {
		return lines.stream().map(Line::getSessionId).collect(Collectors.toList());
	}

	private static UserAccount user(Long id, String name) {
		UserAccount user = new UserAccount();
		user.setId(id);
		user.setUsername(name.toLowerCase());
		user.setFullName(name);
		return user;
	}

	private static PosMachine machine(Long id, String name) {
		PosMachine machine = new PosMachine();
		machine.setId(id);
		machine.setName(name);
		return machine;
	}
}
