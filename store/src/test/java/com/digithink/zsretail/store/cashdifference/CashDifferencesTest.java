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

import com.digithink.zsretail.core.model.BadgeScanLog;
import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.WatchRow;
import com.digithink.zsretail.store.cashmovement.model.CashMovement;
import com.digithink.zsretail.store.cashmovement.model.CashMovementType;
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
		InMemorySessionFigures.set(service, "watchRepository", db.watchRepository);
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
		// step C: every cashier of the period has a row of "Par caissier", zeros when nothing
		assertEquals(2, result.getCashiers().size());
		assertTrue(result.getCashiers().stream().allMatch(r -> r.getSessions() == 0 && r.getClosingTotal() == 0.0));
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
		assertEquals(ids(a30, a1), ids(read(new CashDifferencesService.Filter(FROM, TO, null, 1L, true)).getContent()));
		assertEquals(ids(b15), ids(read(new CashDifferencesService.Filter(FROM, TO, 20L, null, true)).getContent()));
		assertEquals(ids(a1), ids(read(new CashDifferencesService.Filter(FROM, FROM, null, null, true)).getContent()),
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

		CashDifferencesDTO first = read(new CashDifferencesService.Filter(FROM, TO, null, null, true), 0, 2);
		assertEquals(6, first.getPeriodSessions());
		assertEquals(5, first.getListedSessions());
		assertEquals(5, first.getTotalElements());
		assertEquals(3, first.getTotalPages());
		assertEquals(2, first.getContent().size());

		List<Line> everyPage = new ArrayList<>();
		for (int page = 0; page < first.getTotalPages(); page++) {
			CashDifferencesDTO result = read(new CashDifferencesService.Filter(FROM, TO, null, null, true), page, 2);
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
		assertFalse(month.showPending, "the switch off by default");
		assertTrue(CashDifferencesAPI.filter(null, null, null, null, true).showPending);
	}

	// ─── The switch "Afficher les sessions en attente de clôture" ──────

	@Test
	@DisplayName("Switch off (the default): a session only waiting for its clôture is not listed; one with a difference,"
			+ " an écart or an opening without clôture stays, with its remark; the counts and the sums do not change")
	void switchOff() throws Exception {
		CashierSession onlyWaiting = session(amira, 2, SessionStatus.CLOSED, 150.0, null);
		CashierSession waitingShort = session(amira, 3, SessionStatus.CLOSED, 140.0, null); // fermeture -10
		CashierSession waitingGap = session(bilel, 4, SessionStatus.CLOSED, 150.0, null);
		db.openingGap(waitingGap, -2.0);
		CashierSession waitingUnverified = session(bilel, 5, SessionStatus.CLOSED, 150.0, null);
		db.openedWithoutFinalClosing(waitingUnverified, onlyWaiting);
		session(bilel, 6, SessionStatus.CLOSED, 150.0, null); // only waiting, Bilel
		CashierSession closed = terminated(amira, 7, 145.0, 147.0);

		CashDifferencesDTO on = service.all(new CashDifferencesService.Filter(FROM, TO, null, null, true), true);
		CashDifferencesDTO off = service.all(new CashDifferencesService.Filter(FROM, TO, null, null), true);
		assertTrue(on.isShowPending());
		assertFalse(off.isShowPending());
		assertEquals(6, on.getListedSessions());
		assertEquals(ids(closed, waitingUnverified, waitingGap, waitingShort), ids(off.getContent()));
		assertEquals(4, off.getListedSessions());
		assertEquals(4, off.getTotalElements());
		assertEquals(6, off.getPeriodSessions());
		Line kept = off.getContent().stream().filter(l -> l.getSessionId().equals(waitingShort.getId())).findFirst().get();
		assertTrue(kept.isPendingFinalClosing(), "the remark stays");
		assertEquals("En attente de clôture", CashDifferencesExcel.remarks(kept));

		// the counts of the period, whatever the switch; the listed sessions follow it
		assertEquals(5, off.getTotal().getPendingFinalClosing());
		assertEquals(on.getTotal().getPendingFinalClosing(), off.getTotal().getPendingFinalClosing());
		assertEquals(on.getTotal().getOpenedWithoutFinalClosing(), off.getTotal().getOpenedWithoutFinalClosing());
		assertEquals(4, off.getTotal().getSessions());
		assertEquals(6, on.getTotal().getSessions());
		Map<String, CashierRow> offRows = off.getCashiers().stream().collect(Collectors.toMap(CashierRow::getCashierName, r -> r));
		Map<String, CashierRow> onRows = on.getCashiers().stream().collect(Collectors.toMap(CashierRow::getCashierName, r -> r));
		assertEquals(3, offRows.get("Bilel").getPendingFinalClosing());
		assertEquals(onRows.get("Bilel").getPendingFinalClosing(), offRows.get("Bilel").getPendingFinalClosing());
		assertEquals(2, offRows.get("Bilel").getSessions());
		assertEquals(2, offRows.get("Amira").getSessions());
		assertEquals(3, onRows.get("Amira").getSessions());
		// the sums: a session only waiting has no difference and no écart
		assertEquals(on.getTotal().getClosingTotal(), off.getTotal().getClosingTotal(), 1e-9);
		assertEquals(-15.0, off.getTotal().getClosingTotal(), 1e-9);
		assertEquals(on.getTotal().getClosingShortage(), off.getTotal().getClosingShortage(), 1e-9);
		assertEquals(on.getTotal().getFinalClosingTotal(), off.getTotal().getFinalClosingTotal(), 1e-9);
		assertEquals(on.getTotal().getOpeningGapTotal(), off.getTotal().getOpeningGapTotal(), 1e-9);
		assertEquals(on.getTotal().getOpeningGapShortage(), off.getTotal().getOpeningGapShortage(), 1e-9);

		// paging follows what is listed
		CashDifferencesDTO page = service.page(new CashDifferencesService.Filter(FROM, TO, null, null), true, 1, 3);
		assertEquals(2, page.getTotalPages());
		assertEquals(1, page.getContent().size());
	}

	@Test
	@DisplayName("Switch off without 'Voir montants session': a hidden fermeture difference is no reason (the list would"
			+ " tell which drawers are off before the blind count)")
	void switchOffHiddenDifference() {
		session(amira, 2, SessionStatus.CLOSED, 140.0, null); // -10, hidden
		session(amira, 3, SessionStatus.CLOSED, 150.0, null);
		CashDifferencesDTO off = service.all(new CashDifferencesService.Filter(FROM, TO, null, null), false);
		assertEquals(0, off.getListedSessions());
		assertEquals(2, off.getTotal().getPendingFinalClosing());
		assertEquals(2, off.getHiddenClosingDifferences(), "a 0 is hidden too: \"—\" would tell the drawer is right");
		CashDifferencesDTO visible = service.all(new CashDifferencesService.Filter(FROM, TO, null, null), true);
		assertEquals(1, visible.getListedSessions(), "with the right, the -10 is a reason");
	}

	@Test
	@DisplayName("The Excel follows the switch: off, the only-waiting sessions are not in the lines' sheet")
	void excelFollowsTheSwitch() throws Exception {
		session(amira, 2, SessionStatus.CLOSED, 150.0, null);
		CashierSession waitingShort = session(amira, 3, SessionStatus.CLOSED, 140.0, null);
		for (boolean show : new boolean[] { false, true }) {
			byte[] file = CashDifferencesExcel.of(service.all(new CashDifferencesService.Filter(FROM, TO, null, null, show), true));
			try (org.apache.poi.xssf.usermodel.XSSFWorkbook book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
					new java.io.ByteArrayInputStream(file))) {
				org.apache.poi.ss.usermodel.Sheet lines = book.getSheet("Écarts de caisse");
				int dataRows = lines.getLastRowNum() - 2; // title, blank, header
				assertEquals(show ? 2 : 1, dataRows, "show = " + show);
				assertEquals(waitingShort.getSessionNumber(), lines.getRow(3).getCell(0).getStringCellValue());
				String summary = book.getSheet("Résumé").getRow(2) == null ? ""
						: book.getSheet("Résumé").getRow(2).getCell(0).getStringCellValue();
				assertEquals(!show, summary.startsWith("Sessions en attente de clôture : 2"), summary);
			}
		}
	}

	// ─── Step C, the block "À surveiller" ────────────────────────────

	@Test
	@DisplayName("À surveiller: each column on a known session (tickets, cancelled, returns, discounts and their manual"
			+ " part, dépenses, prélèvements, refused authorisations)")
	void watchColumns() {
		knownSession(amira, 3);
		CashDifferencesDTO result = all(true);
		WatchRow row = result.getWatch().get(0);
		assertEquals("Amira", row.getCashierName());
		assertEquals(4, row.getTickets(), "the fixture's ticket and T2, T3, T4");
		assertEquals(1, row.getCancelledCount());
		assertEquals(30.0, row.getCancelledAmount(), 1e-9);
		assertEquals(2, row.getReturnsCount());
		assertEquals(25.0, row.getReturnsAmount(), 1e-9, "simple 10 + voucher 15");
		assertEquals(3, row.getDiscountTickets(), "T2 (ticket), T3 (lines), T4 (free line)");
		assertEquals(14.0, row.getDiscountAmount(), 1e-9, "5 + 2 + 1 + 6, the stamp line's left out");
		assertEquals(2, row.getManualDiscountTickets());
		assertEquals(6.0, row.getManualDiscountAmount(), 1e-9, "T2's 5 + T3's line 1");
		assertEquals(1, row.getExpenseCount());
		assertEquals(7.5, row.getExpenseAmount(), 1e-9);
		assertEquals(1, row.getWithdrawalCount());
		assertEquals(100.0, row.getWithdrawalAmount(), 1e-9, "the apport is not an out");
		assertEquals(2, row.getRefusedAuthorisations(), "a success, a refusal without session not counted");
		assertEquals(0, row.getHiddenSessions());
		assertEquals(copyNamed(row, null, null), copyNamed(result.getWatchTotal(), null, null),
				"one cashier: the total is his row");
	}

	@Test
	@DisplayName("À surveiller: attributed to the session's cashier; the filters (period, POS machine, cashier); the total"
			+ " is the rows' sum; a cashier with nothing has a row of zeros, in 'Par caissier' too")
	void watchAttributionFiltersTotal() {
		knownSession(amira, 3);
		CashierSession b = knownSession(bilel, 10);
		CashierSession empty = terminated(user(3L, "Chokri"), 12, 100.0, 100.0);
		db.sales.removeIf(sale -> sale.getCashierSession() == empty);
		CashierSession october = knownSession(amira, 0);
		october.setOpenedAt(LocalDate.of(2026, 10, 31).atTime(20, 0));
		db.machineOf.put(b.getId(), m2);

		CashDifferencesDTO result = all(true);
		assertEquals(cashierIds(result.getCashiers()), watchIds(result.getWatch()), "the same cashiers, the same order");
		assertEquals(3, result.getWatch().size());
		WatchRow chokri = result.getWatch().get(2); // by name: Amira, Bilel, Chokri
		assertEquals("Chokri", chokri.getCashierName());
		assertEquals(new WatchRow(), copyNamed(chokri, null, null), "zeros");
		WatchRow total = result.getWatchTotal();
		assertEquals(8, total.getTickets(), "October left out");
		assertEquals(28.0, total.getDiscountAmount(), 1e-9);
		assertEquals(4, total.getRefusedAuthorisations());
		assertEquals(sumRows(result.getWatch()), copyNamed(total, null, null), "the total is the rows' sum");

		CashDifferencesDTO onlyAmira = read(new CashDifferencesService.Filter(FROM, TO, null, amira.getId(), true));
		assertEquals(1, onlyAmira.getWatch().size());
		assertEquals(4, onlyAmira.getWatchTotal().getTickets());
		CashDifferencesDTO onM2 = read(new CashDifferencesService.Filter(FROM, TO, m2.getId(), null, true));
		assertEquals(1, onM2.getWatch().size());
		assertEquals("Bilel", onM2.getWatch().get(0).getCashierName());
		CashDifferencesDTO day10 = read(new CashDifferencesService.Filter(LocalDate.of(2026, 11, 10),
				LocalDate.of(2026, 11, 10), null, null, true));
		assertEquals(4, day10.getWatchTotal().getTickets());
	}

	@Test
	@DisplayName("À surveiller: the switch showPending changes nothing in it")
	void watchIgnoresTheSwitch() {
		knownSession(amira, 3);
		session(bilel, 4, SessionStatus.CLOSED, 150.0, null);
		CashDifferencesDTO off = service.all(new CashDifferencesService.Filter(FROM, TO, null, null, false), true);
		CashDifferencesDTO on = service.all(new CashDifferencesService.Filter(FROM, TO, null, null, true), true);
		assertEquals(on.getWatch(), off.getWatch());
		assertEquals(on.getWatchTotal(), off.getWatchTotal());
		assertEquals(2, off.getWatch().size(), "Bilel's only-waiting session is not listed, his row is there");
	}

	@Test
	@DisplayName("À surveiller without 'Voir montants session': a session not clôturée shows its counts, not the amounts"
			+ " of its returns, dépenses and prélèvements (they enter the expected cash)")
	void watchHiddenAmounts() {
		CashierSession closed = knownSession(amira, 3);
		closed.setStatus(SessionStatus.CLOSED);
		closed.setResponsibleClosureCash(null);
		knownSession(bilel, 4);
		CashDifferencesDTO hidden = all(false);
		assertEquals(1, hidden.getWatchHiddenSessions());
		WatchRow amiraRow = hidden.getWatch().get(0);
		assertEquals(1, amiraRow.getHiddenSessions());
		assertEquals(2, amiraRow.getReturnsCount());
		assertEquals(0.0, amiraRow.getReturnsAmount(), 1e-9);
		assertEquals(1, amiraRow.getExpenseCount());
		assertEquals(0.0, amiraRow.getExpenseAmount(), 1e-9);
		assertEquals(0.0, amiraRow.getWithdrawalAmount(), 1e-9);
		assertEquals(14.0, amiraRow.getDiscountAmount(), 1e-9, "not in the expected cash: shown");
		assertEquals(30.0, amiraRow.getCancelledAmount(), 1e-9);
		WatchRow bilelRow = hidden.getWatch().get(1);
		assertEquals(25.0, bilelRow.getReturnsAmount(), 1e-9, "clôturée: shown");
		assertEquals(25.0, hidden.getWatchTotal().getReturnsAmount(), 1e-9);
		CashDifferencesDTO visible = all(true);
		assertEquals(0, visible.getWatchHiddenSessions());
		assertEquals(50.0, visible.getWatchTotal().getReturnsAmount(), 1e-9);
	}

	@Test
	@DisplayName("À surveiller: one query per kind for the whole period, never one per session")
	void watchOneQueryPerKind() {
		for (int day = 1; day <= 12; day++) {
			knownSession(day % 2 == 0 ? amira : bilel, day);
		}
		all(true);
		for (String query : new String[] { "cancelledTickets", "discountedLines", "discountedTickets",
				"refusedAuthorisations", "saleAmounts", "cashMovements" }) {
			assertEquals(1, db.calls.get(query), query);
		}
	}

	@Test
	@DisplayName("The Excel has the sheet 'À surveiller' with the cashiers' rows and the total")
	void watchExcel() throws Exception {
		knownSession(amira, 3);
		knownSession(bilel, 4);
		byte[] file = CashDifferencesExcel.of(all(true));
		try (org.apache.poi.xssf.usermodel.XSSFWorkbook book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(
				new java.io.ByteArrayInputStream(file))) {
			org.apache.poi.ss.usermodel.Sheet sheet = book.getSheet("À surveiller");
			assertNotNull(sheet);
			int last = sheet.getLastRowNum();
			assertEquals("Total", sheet.getRow(last).getCell(0).getStringCellValue());
			assertEquals(8.0, sheet.getRow(last).getCell(1).getNumericCellValue(), 1e-9);
			assertEquals(28.0, sheet.getRow(last).getCell(7).getNumericCellValue(), 1e-9);
			assertEquals("Amira", sheet.getRow(last - 2).getCell(0).getStringCellValue());
		}
	}

	/**
	 * A clôturée session (difference 0) of that day with: the fixture's ticket 50 and cancelled 30; T2 40 with a MANUAL
	 * ticket discount 5; T3 20 with a PROMOTION line discount 2, a MANUAL line discount 1 and a stamp line "discount" 1
	 * (not one); T4 10 with a free line (100 %, 3.000 x 2); returns simple 10 and voucher 15; DEPENSE 7.5, PRELEVEMENT
	 * 100, APPORT 20; two refused scans, one success, one refusal without session.
	 */
	private CashierSession knownSession(UserAccount cashier, int day) {
		CashierSession s = terminated(cashier, day, 150.0, 150.0);
		ticket(s, 40.0, 5.0, "MANUAL");
		SalesHeader t3 = ticket(s, 20.0, null, null);
		discountLine(t3, 2.0, "PROMOTION", "ART");
		discountLine(t3, 1.0, "MANUAL", "ART");
		discountLine(t3, 1.0, "MANUAL", "TAX_STAMP");
		SalesHeader t4 = ticket(s, 10.0, null, null);
		SalesLine free = discountLine(t4, 0.0, "PROMOTION", "ART");
		free.setDiscountPercentage(100.0);
		free.setLineTotalIncludingVat(0.0);
		free.setUnitPriceIncludingVat(3.0);
		free.setQuantity(BigDecimal.valueOf(2));
		// system: 100 + 50 + 40 + 20 + 10 - 10 (simple return) - 7.5 - 100 + 20 = 122.5: counted the same (no difference)
		s.setPosUserClosureCash(122.5);
		s.setResponsibleClosureCash(122.5);
		returnOf(s, ReturnType.SIMPLE_RETURN, 10.0);
		returnOf(s, ReturnType.RETURN_VOUCHER, 15.0);
		movement(s, CashMovementType.DEPENSE, 7.5);
		movement(s, CashMovementType.PRELEVEMENT, 100.0);
		movement(s, CashMovementType.APPORT, 20.0);
		scan(s.getId(), false);
		scan(s.getId(), false);
		scan(s.getId(), true);
		scan(null, false);
		return s;
	}

	private SalesHeader ticket(CashierSession session, double total, Double discount, String source) {
		SalesHeader sale = new SalesHeader();
		sale.setId(nextId++);
		sale.setCashierSession(session);
		sale.setStatus(TransactionStatus.COMPLETED);
		sale.setTotalAmount(total);
		sale.setDiscountAmount(discount);
		sale.setDiscountSource(source);
		db.sales.add(sale);
		return sale;
	}

	private SalesLine discountLine(SalesHeader sale, double discount, String source, String itemCode) {
		SalesLine line = new SalesLine();
		line.setId(nextId++);
		line.setSalesHeader(sale);
		line.setDiscountAmount(discount);
		line.setDiscountSource(source);
		line.setLineTotalIncludingVat(10.0);
		Item item = new Item();
		item.setItemCode(itemCode);
		line.setItem(item);
		db.lines.add(line);
		return line;
	}

	private void returnOf(CashierSession session, ReturnType type, double amount) {
		ReturnHeader r = new ReturnHeader();
		r.setId(nextId++);
		r.setCashierSession(session);
		r.setReturnType(type);
		r.setTotalReturnAmount(amount);
		db.returns.add(r);
	}

	private void movement(CashierSession session, CashMovementType type, double amount) {
		CashMovement m = new CashMovement();
		m.setId(nextId++);
		m.setCashierSession(session);
		m.setType(type);
		m.setAmount(amount);
		db.movements.add(m);
	}

	private void scan(Long sessionId, boolean success) {
		BadgeScanLog log = new BadgeScanLog();
		log.setId(nextId++);
		log.setSessionId(sessionId);
		log.setSuccess(success);
		db.badgeLogs.add(log);
	}

	/** The row's numbers with this name and id (to compare numbers only). */
	private static WatchRow copyNamed(WatchRow row, String name, Long id) {
		WatchRow copy = new WatchRow();
		copy.setCashierName(name);
		copy.setCashierId(id);
		copy.setTickets(row.getTickets());
		copy.setCancelledCount(row.getCancelledCount());
		copy.setCancelledAmount(row.getCancelledAmount());
		copy.setReturnsCount(row.getReturnsCount());
		copy.setReturnsAmount(row.getReturnsAmount());
		copy.setDiscountTickets(row.getDiscountTickets());
		copy.setDiscountAmount(row.getDiscountAmount());
		copy.setManualDiscountTickets(row.getManualDiscountTickets());
		copy.setManualDiscountAmount(row.getManualDiscountAmount());
		copy.setExpenseCount(row.getExpenseCount());
		copy.setExpenseAmount(row.getExpenseAmount());
		copy.setWithdrawalCount(row.getWithdrawalCount());
		copy.setWithdrawalAmount(row.getWithdrawalAmount());
		copy.setRefusedAuthorisations(row.getRefusedAuthorisations());
		copy.setHiddenSessions(row.getHiddenSessions());
		return copy;
	}

	private static WatchRow sumRows(List<WatchRow> rows) {
		WatchRow sum = new WatchRow();
		for (WatchRow r : rows) {
			sum.setTickets(sum.getTickets() + r.getTickets());
			sum.setCancelledCount(sum.getCancelledCount() + r.getCancelledCount());
			sum.setCancelledAmount(round(sum.getCancelledAmount() + r.getCancelledAmount()));
			sum.setReturnsCount(sum.getReturnsCount() + r.getReturnsCount());
			sum.setReturnsAmount(round(sum.getReturnsAmount() + r.getReturnsAmount()));
			sum.setDiscountTickets(sum.getDiscountTickets() + r.getDiscountTickets());
			sum.setDiscountAmount(round(sum.getDiscountAmount() + r.getDiscountAmount()));
			sum.setManualDiscountTickets(sum.getManualDiscountTickets() + r.getManualDiscountTickets());
			sum.setManualDiscountAmount(round(sum.getManualDiscountAmount() + r.getManualDiscountAmount()));
			sum.setExpenseCount(sum.getExpenseCount() + r.getExpenseCount());
			sum.setExpenseAmount(round(sum.getExpenseAmount() + r.getExpenseAmount()));
			sum.setWithdrawalCount(sum.getWithdrawalCount() + r.getWithdrawalCount());
			sum.setWithdrawalAmount(round(sum.getWithdrawalAmount() + r.getWithdrawalAmount()));
			sum.setRefusedAuthorisations(sum.getRefusedAuthorisations() + r.getRefusedAuthorisations());
			sum.setHiddenSessions(sum.getHiddenSessions() + r.getHiddenSessions());
		}
		return sum;
	}

	private static double round(double value) {
		return BigDecimal.valueOf(value).setScale(3, java.math.RoundingMode.HALF_UP).doubleValue();
	}

	private static List<Long> watchIds(List<WatchRow> rows) {
		return rows.stream().map(WatchRow::getCashierId).collect(Collectors.toList());
	}

	private static List<Long> cashierIds(List<CashierRow> rows) {
		return rows.stream().map(CashierRow::getCashierId).collect(Collectors.toList());
	}

	// ─── Fixtures ────────────────────────────────────────────────────

	private CashDifferencesDTO all(boolean amountsVisible) {
		return service.all(new CashDifferencesService.Filter(FROM, TO, null, null, true), amountsVisible);
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
