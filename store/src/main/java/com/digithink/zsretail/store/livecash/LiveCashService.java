package com.digithink.zsretail.store.livecash;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.WatchRow;
import com.digithink.zsretail.store.cashdifference.CashWatch;
import com.digithink.zsretail.store.cashdifference.CashWatchRepository;
import com.digithink.zsretail.store.cashmovement.service.CashMovementTotals;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;
import com.digithink.zsretail.store.posmachine.service.SessionPosMachineService;
import com.digithink.zsretail.store.service.CashierSessionService;
import com.digithink.zsretail.store.service.SessionAmounts;
import com.digithink.zsretail.store.service.SessionFiguresService;

/**
 * 3.2.0, "Caisse en direct" (docs/modules/cash-sessions.md): the store's day and each drawer now, read only. Nothing is
 * stored; no row is locked (a read-only transaction, plain reads). Every number comes from the definition another page
 * already uses:
 * <ul>
 * <li>a session's tickets, sales, returns and cash movements: SessionFiguresService (the Sessions list's);</li>
 * <li>its expected cash: CashierSessionService.expectedCash (the closing's);</li>
 * <li>"À surveiller": CashWatch (the page Écarts de caisse's), this session only;</li>
 * <li>the day's tickets and sales: the Journal's day (finished tickets dated today, every session).</li>
 * </ul>
 * Only the open sessions are read in detail, and the latest session of each POS machine (one row each). Without "Voir
 * montants session" no amount is sent (SessionAmounts: an open session is never finally closed).
 */
@Service
public class LiveCashService {

	static final List<TransactionStatus> NOT_COUNTED = Arrays.asList(TransactionStatus.PENDING,
			TransactionStatus.CANCELLED);

	@Autowired
	private LiveCashRepository live;

	@Autowired
	private SessionFiguresService sessionFigures;

	@Autowired
	private CashWatchRepository watchRepository;

	@Autowired
	private CashierSessionService cashierSessions;

	@Autowired
	private SessionPosMachineService sessionPosMachines;

	private Clock clock = Clock.systemDefaultZone();

	@Transactional(readOnly = true)
	public LiveCashDTO read(boolean amountsVisible) {
		LiveCashDTO result = new LiveCashDTO();
		result.setReadAt(LocalDateTime.now(clock));
		boolean hidden = SessionAmounts.hidden(amountsVisible, SessionStatus.OPENED);
		result.setAmountsHidden(hidden);
		result.setDrawerOwner(sessionPosMachines.drawerOwner().name());

		List<CashierSession> open = new ArrayList<>();
		for (Object[] row : live.openSessions(SessionStatus.OPENED)) {
			open.add(openSession(row));
		}
		List<Long> openIds = open.stream().map(CashierSession::getId).collect(Collectors.toList());
		Map<Long, LiveCashDTO.OpenSession> details = openSessions(open, openIds, amountsVisible, hidden);

		// The day: the Journal's tickets of today, the open drawers' expected cash
		LocalDate today = LocalDate.now(clock);
		LiveCashDTO.Day day = result.getDay();
		day.setOpenSessions(open.size());
		for (Object[] row : live.dayTotals(today.atStartOfDay(), today.plusDays(1).atStartOfDay(), NOT_COUNTED)) {
			day.setTickets(row[0] instanceof Number ? ((Number) row[0]).longValue() : 0L);
			if (!hidden) {
				day.setSales(millimes(row[1] instanceof Number ? ((Number) row[1]).doubleValue() : 0.0));
			}
		}
		if (!hidden) {
			BigDecimal expected = BigDecimal.ZERO;
			for (LiveCashDTO.OpenSession s : details.values()) {
				expected = expected.add(BigDecimal.valueOf(s.getExpectedCash()));
			}
			day.setExpectedCash(expected.setScale(3, RoundingMode.HALF_UP).doubleValue());
		}

		// The drawers: each POS machine shown, its open sessions and its latest session; then the sessions without one
		Map<Long, Long> machineOf = new HashMap<>();
		for (List<Long> chunk : SessionFiguresService.chunks(openIds)) {
			for (Object[] row : live.machineOf(chunk)) {
				machineOf.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
			}
		}
		Map<Long, Object[]> latest = new HashMap<>();
		for (Object[] row : live.latestSessionOfEachMachine()) {
			latest.put(((Number) row[0]).longValue(), row);
		}
		for (Object[] machine : live.machines(SessionStatus.OPENED)) {
			Long machineId = ((Number) machine[0]).longValue();
			LiveCashDTO.Drawer drawer = new LiveCashDTO.Drawer();
			drawer.setPosMachineId(machineId);
			drawer.setPosMachineName((String) machine[1]);
			drawer.setActive(!Boolean.FALSE.equals(machine[2]));
			for (CashierSession s : open) {
				if (machineId.equals(machineOf.get(s.getId()))) {
					drawer.getSessions().add(details.get(s.getId()));
				}
			}
			Object[] last = latest.get(machineId);
			if (last != null && last[3] == SessionStatus.CLOSED) {
				LiveCashDTO.Waiting waiting = new LiveCashDTO.Waiting();
				waiting.setSessionId(((Number) last[1]).longValue());
				waiting.setSessionNumber((String) last[2]);
				waiting.setClosedAt((LocalDateTime) last[4]);
				waiting.setCashierName(last[5] != null ? (String) last[5] : (String) last[6]);
				drawer.setWaiting(waiting);
			}
			drawer.setState(!drawer.getSessions().isEmpty() ? "OPEN" : drawer.getWaiting() != null ? "WAITING" : "NONE");
			result.getDrawers().add(drawer);
		}
		for (CashierSession s : open) {
			if (!machineOf.containsKey(s.getId())) {
				LiveCashDTO.Drawer drawer = new LiveCashDTO.Drawer();
				drawer.setState("OPEN");
				drawer.getSessions().add(details.get(s.getId()));
				result.getDrawers().add(drawer);
			}
		}
		return result;
	}

	/**
	 * An open session from its columns: not a managed entity (nothing is ever saved from here); what the expected cash
	 * and the page read of it: id, number, status, opened at, opening cash, its cashier's id and names.
	 */
	private static CashierSession openSession(Object[] row) {
		CashierSession s = new CashierSession();
		s.setId(((Number) row[0]).longValue());
		s.setSessionNumber((String) row[1]);
		s.setStatus(SessionStatus.OPENED);
		s.setOpenedAt((LocalDateTime) row[2]);
		s.setOpeningCash(row[3] != null ? ((Number) row[3]).doubleValue() : null);
		if (row[4] != null) {
			UserAccount cashier = new UserAccount();
			cashier.setId(((Number) row[4]).longValue());
			cashier.setFullName((String) row[5]);
			cashier.setUsername((String) row[6]);
			s.setCashier(cashier);
		}
		return s;
	}

	/** The open sessions' data, by id, in their order. */
	private Map<Long, LiveCashDTO.OpenSession> openSessions(List<CashierSession> open, List<Long> ids,
			boolean amountsVisible, boolean hidden) {
		Map<Long, LiveCashDTO.OpenSession> out = new LinkedHashMap<>();
		if (open.isEmpty()) {
			return out;
		}
		Map<Long, SessionFiguresService.Figures> figures = sessionFigures.of(ids);
		Map<Long, Double> movementNets = new HashMap<>();
		figures.forEach((id, f) -> movementNets.put(id, f.movementTotals().getNet().doubleValue()));
		// the closing's computation; not read at all without the right (nothing of it is sent)
		Map<Long, Double> expected = hidden ? new HashMap<>() : cashierSessions.expectedCash(open, movementNets);
		Map<Long, LocalDateTime> lastTicket = new HashMap<>();
		for (List<Long> chunk : SessionFiguresService.chunks(ids)) {
			for (Object[] row : live.lastTicketTimes(chunk, NOT_COUNTED)) {
				lastTicket.put(((Number) row[0]).longValue(), (LocalDateTime) row[1]);
			}
		}
		List<SessionDashboardDTO> dashboards = new ArrayList<>();
		for (CashierSession s : open) {
			SessionDashboardDTO d = new SessionDashboardDTO();
			d.setId(s.getId());
			d.setStatus(s.getStatus());
			dashboards.add(d);
		}
		Map<Long, WatchRow> watch = CashWatch.perSession(dashboards, figures, watchRepository, amountsVisible);

		for (CashierSession s : open) {
			SessionFiguresService.Figures f = figures.get(s.getId());
			CashMovementTotals movements = f.movementTotals();
			LiveCashDTO.OpenSession o = new LiveCashDTO.OpenSession();
			o.setSessionId(s.getId());
			o.setSessionNumber(s.getSessionNumber());
			UserAccount cashier = s.getCashier();
			if (cashier != null) {
				o.setCashierId(cashier.getId());
				o.setCashierName(cashier.getFullName() != null ? cashier.getFullName() : cashier.getUsername());
			}
			o.setOpenedAt(s.getOpenedAt());
			o.setTickets(f.salesCount());
			o.setLastTicketAt(lastTicket.get(s.getId()));
			o.setApportCount(movements.getApportCount());
			o.setWithdrawalCount(movements.getPrelevementCount());
			o.setExpenseCount(movements.getDepenseCount());
			WatchRow w = watch.get(s.getId());
			LiveCashDTO.Watch lw = o.getWatch();
			lw.setCancelledCount(w.getCancelledCount());
			lw.setReturnsCount(w.getReturnsCount());
			lw.setDiscountTickets(w.getDiscountTickets());
			lw.setManualDiscountTickets(w.getManualDiscountTickets());
			lw.setRefusedAuthorisations(w.getRefusedAuthorisations());
			if (!hidden) {
				o.setExpectedCash(expected.get(s.getId()));
				o.setOpeningCash(s.getOpeningCash());
				o.setSales(millimes(f.totalSales()));
				o.setApportTotal(movements.getApportTotal().doubleValue());
				o.setWithdrawalTotal(movements.getPrelevementTotal().doubleValue());
				o.setExpenseTotal(movements.getDepenseTotal().doubleValue());
				lw.setCancelledAmount(w.getCancelledAmount());
				lw.setReturnsAmount(millimes(f.totalReturns()));
				lw.setDiscountAmount(w.getDiscountAmount());
				lw.setManualDiscountAmount(w.getManualDiscountAmount());
			}
			out.put(s.getId(), o);
		}
		return out;
	}

	private static double millimes(double value) {
		return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
	}
}
