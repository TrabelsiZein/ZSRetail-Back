package com.digithink.zsretail.store.cashdifference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.SalesLine;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.WatchRow;
import com.digithink.zsretail.store.service.SessionAmounts;
import com.digithink.zsretail.store.cashmovement.service.CashMovementTotals;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;
import com.digithink.zsretail.store.service.SessionFiguresService;
import com.digithink.zsretail.store.service.SessionReportService;

/**
 * 3.2.0, the differences, step C: the block "À surveiller" of the page "Écarts de caisse" (docs/modules/cash-sessions.md,
 * "Cash differences"): per cashier of the period's sessions (every session, whatever the switch showPending), what a
 * responsible watches even when the drawer is right. Numbers only, no threshold. Each from the definition the
 * Sessions list, the Journal and the tickets X / Z already use:
 * <ul>
 * <li>tickets, returns: SessionFiguresService (the Sessions list's: tickets neither PENDING nor CANCELLED, every return,
 * their totalReturnAmount), the Journal's TICKET / RETURN;</li>
 * <li>cancelled tickets: status CANCELLED (the Journal's CANCELLED, the X / Z's cancelledCount), their stored total;</li>
 * <li>discounts: as the X / Z's REMISES, SessionReportService.ticketXzLineDiscount of each line plus the ticket
 * discount (loyalty apart, as there), and the MANUAL part (discount_source of the line or the ticket);</li>
 * <li>dépenses and prélèvements: CashMovementTotals (the X / Z's MOUVEMENTS DE CAISSE);</li>
 * <li>refused authorisations: BadgeScanLog success false, by the session the scanning user had open.</li>
 * </ul>
 * Without "Voir montants session" the amounts that enter the expected cash (returns, dépenses, prélèvements) of a
 * session not finally closed are neither sent nor summed, as the fermeture difference of step B; their counts are.
 * Grouped queries for the whole set of sessions, by chunks of ids.
 */
final class CashWatch {

	static final String MANUAL = "MANUAL";

	private CashWatch() {
	}

	/** One session's numbers. */
	static final class Session {
		long tickets;
		long cancelledCount;
		BigDecimal cancelledAmount = BigDecimal.ZERO;
		long returnsCount;
		BigDecimal returnsAmount = BigDecimal.ZERO;
		final Set<Long> discountTickets = new HashSet<>();
		BigDecimal discountAmount = BigDecimal.ZERO;
		final Set<Long> manualTickets = new HashSet<>();
		BigDecimal manualAmount = BigDecimal.ZERO;
		long expenseCount;
		BigDecimal expenseAmount = BigDecimal.ZERO;
		long withdrawalCount;
		BigDecimal withdrawalAmount = BigDecimal.ZERO;
		long refused;
		boolean hidden;
	}

	static void fill(CashDifferencesDTO result, List<SessionDashboardDTO> dashboards,
			Map<Long, SessionFiguresService.Figures> figures, CashWatchRepository repository, boolean amountsVisible) {
		Map<Long, Session> sessions = sessions(dashboards, figures, repository, amountsVisible);
		Map<Long, List<SessionDashboardDTO>> byCashier = new LinkedHashMap<>();
		dashboards.forEach(d -> byCashier.computeIfAbsent(d.getCashierId(), id -> new ArrayList<>()).add(d));
		List<WatchRow> rows = new ArrayList<>();
		byCashier.forEach((cashierId, ofCashier) -> {
			WatchRow row = row(ofCashier.stream().map(d -> sessions.get(d.getId())).collect(Collectors.toList()));
			row.setCashierId(cashierId);
			row.setCashierName(ofCashier.get(0).getCashierFullName());
			rows.add(row);
		});
		rows.sort(java.util.Comparator
				.comparing((WatchRow r) -> r.getCashierName() == null ? "" : r.getCashierName(), String.CASE_INSENSITIVE_ORDER)
				.thenComparing(r -> r.getCashierId() == null ? 0L : r.getCashierId()));
		result.setWatch(rows);
		result.setWatchTotal(row(new ArrayList<>(sessions.values())));
		result.setWatchHiddenSessions(sessions.values().stream().filter(s -> s.hidden).count());
	}

	/** Each session's numbers, by session id (every session of the dashboards, zeros when nothing). */
	static Map<Long, Session> sessions(List<SessionDashboardDTO> dashboards, Map<Long, SessionFiguresService.Figures> figures,
			CashWatchRepository repository, boolean amountsVisible) {
		Map<Long, Session> out = new LinkedHashMap<>();
		for (SessionDashboardDTO d : dashboards) {
			Session s = new Session();
			s.hidden = SessionAmounts.hidden(amountsVisible, d.getStatus());
			SessionFiguresService.Figures f = figures.get(d.getId());
			if (f != null) {
				s.tickets = f.salesCount();
				s.returnsCount = f.returnsCount();
				s.returnsAmount = bd(f.totalReturns());
				CashMovementTotals movements = f.movementTotals();
				s.expenseCount = movements.getDepenseCount();
				s.expenseAmount = movements.getDepenseTotal();
				s.withdrawalCount = movements.getPrelevementCount();
				s.withdrawalAmount = movements.getPrelevementTotal();
			}
			out.put(d.getId(), s);
		}
		List<TransactionStatus> excluded = java.util.Arrays.asList(TransactionStatus.PENDING, TransactionStatus.CANCELLED);
		for (List<Long> chunk : SessionFiguresService.chunks(out.keySet())) {
			for (Object[] row : repository.cancelledTickets(chunk, TransactionStatus.CANCELLED)) {
				Session s = out.get(id(row[0]));
				s.cancelledCount += ((Number) row[1]).longValue();
				s.cancelledAmount = s.cancelledAmount.add(bd(row[2]));
			}
			for (Object[] row : repository.discountedLines(chunk, excluded)) {
				Session s = out.get(id(row[1]));
				BigDecimal amount = bd(SessionReportService.ticketXzLineDiscount(line(row)));
				addDiscount(s, id(row[0]), amount, (String) row[3]);
			}
			for (Object[] row : repository.discountedTickets(chunk, excluded)) {
				addDiscount(out.get(id(row[1])), id(row[0]), bd(row[2]), (String) row[3]);
			}
			for (Object[] row : repository.refusedAuthorisations(chunk)) {
				out.get(id(row[0])).refused += ((Number) row[1]).longValue();
			}
		}
		return out;
	}

	private static void addDiscount(Session s, Long saleId, BigDecimal amount, String source) {
		if (amount.signum() == 0) {
			return;
		}
		s.discountTickets.add(saleId);
		s.discountAmount = s.discountAmount.add(amount);
		if (MANUAL.equals(source)) {
			s.manualTickets.add(saleId);
			s.manualAmount = s.manualAmount.add(amount);
		}
	}

	/** The row of some sessions: counts added; the amounts of a hidden session left out (its counts stay). */
	static WatchRow row(List<Session> sessions) {
		WatchRow row = new WatchRow();
		BigDecimal cancelled = BigDecimal.ZERO;
		BigDecimal returns = BigDecimal.ZERO;
		BigDecimal discount = BigDecimal.ZERO;
		BigDecimal manual = BigDecimal.ZERO;
		BigDecimal expense = BigDecimal.ZERO;
		BigDecimal withdrawal = BigDecimal.ZERO;
		for (Session s : sessions) {
			row.setTickets(row.getTickets() + s.tickets);
			row.setCancelledCount(row.getCancelledCount() + s.cancelledCount);
			cancelled = cancelled.add(s.cancelledAmount);
			row.setReturnsCount(row.getReturnsCount() + s.returnsCount);
			row.setDiscountTickets(row.getDiscountTickets() + s.discountTickets.size());
			discount = discount.add(s.discountAmount);
			row.setManualDiscountTickets(row.getManualDiscountTickets() + s.manualTickets.size());
			manual = manual.add(s.manualAmount);
			row.setExpenseCount(row.getExpenseCount() + s.expenseCount);
			row.setWithdrawalCount(row.getWithdrawalCount() + s.withdrawalCount);
			row.setRefusedAuthorisations(row.getRefusedAuthorisations() + s.refused);
			if (s.hidden) {
				row.setHiddenSessions(row.getHiddenSessions() + 1);
			} else {
				returns = returns.add(s.returnsAmount);
				expense = expense.add(s.expenseAmount);
				withdrawal = withdrawal.add(s.withdrawalAmount);
			}
		}
		row.setCancelledAmount(millimes(cancelled));
		row.setReturnsAmount(millimes(returns));
		row.setDiscountAmount(millimes(discount));
		row.setManualDiscountAmount(millimes(manual));
		row.setExpenseAmount(millimes(expense));
		row.setWithdrawalAmount(millimes(withdrawal));
		return row;
	}

	/** A line of discountedLines as a SalesLine, for SessionReportService.ticketXzLineDiscount. */
	static SalesLine line(Object[] row) {
		SalesLine line = new SalesLine();
		line.setDiscountAmount(row[2] != null ? ((Number) row[2]).doubleValue() : null);
		line.setDiscountSource((String) row[3]);
		line.setLineTotalIncludingVat(row[4] != null ? ((Number) row[4]).doubleValue() : null);
		line.setDiscountPercentage(row[5] != null ? ((Number) row[5]).doubleValue() : null);
		line.setUnitPriceIncludingVat(row[6] != null ? ((Number) row[6]).doubleValue() : null);
		line.setQuantity(row[7] instanceof BigDecimal ? (BigDecimal) row[7]
				: row[7] != null ? BigDecimal.valueOf(((Number) row[7]).doubleValue()) : null);
		if (row[8] != null) {
			Item item = new Item();
			item.setItemCode((String) row[8]);
			line.setItem(item);
		}
		return line;
	}

	private static Long id(Object value) {
		return value == null ? null : ((Number) value).longValue();
	}

	private static BigDecimal bd(Object value) {
		return value instanceof Number ? BigDecimal.valueOf(((Number) value).doubleValue()) : BigDecimal.ZERO;
	}

	private static double millimes(BigDecimal value) {
		return value.setScale(3, RoundingMode.HALF_UP).doubleValue();
	}
}
