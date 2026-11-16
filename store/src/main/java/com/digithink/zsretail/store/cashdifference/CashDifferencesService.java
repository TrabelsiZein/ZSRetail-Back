package com.digithink.zsretail.store.cashdifference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.CashierRow;
import com.digithink.zsretail.store.service.SessionAmounts;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.Line;
import com.digithink.zsretail.store.cashmovement.dto.OpeningUnverifiedDTO;
import com.digithink.zsretail.store.cashmovement.service.DrawerHandoverService;
import com.digithink.zsretail.store.dto.SessionDashboardDTO;
import com.digithink.zsretail.store.repository.SessionFiguresRepository;
import com.digithink.zsretail.store.service.SessionFiguresService;

/**
 * 3.2.0, the differences, step B: the page "Écarts de caisse" (docs/modules/cash-sessions.md, "Cash differences"). Read
 * only, nothing stored, no rule of its own: each session's numbers are the Sessions list's line
 * (SessionFiguresService.dashboards, SessionDashboardDTO.fromEntity), the écart d'ouverture the list's, the opening
 * without the previous session finally closed the row kept at that opening (cashier_session_opening_unverified).
 */
@Service
public class CashDifferencesService {

	/** A difference below half a millime is none (the values are rounded to the millime; the list's rule for the écart). */
	static final double ZERO = 0.0005;

	@Autowired
	private SessionFiguresRepository figures;

	@Autowired
	private SessionFiguresService sessionFigures;

	@Autowired(required = false)
	private DrawerHandoverService drawerHandoverService;

	/** Step C: the block "À surveiller" (cancelled tickets, discounts, refused authorisations). */
	@Autowired
	private CashWatchRepository watchRepository;

	/** The period and filters (from and to both included, as the Sessions list's dates). */
	public static final class Filter {
		final LocalDate from;
		final LocalDate to;
		final Long posMachineId;
		final Long cashierId;
		/**
		 * The switch "Afficher les sessions en attente de clôture" (off by default): off, a session whose only reason
		 * is to wait for its clôture is not listed (a customer that never does the clôture would see every session).
		 */
		final boolean showPending;

		public Filter(LocalDate from, LocalDate to, Long posMachineId, Long cashierId) {
			this(from, to, posMachineId, cashierId, false);
		}

		public Filter(LocalDate from, LocalDate to, Long posMachineId, Long cashierId, boolean showPending) {
			this.from = from;
			this.to = to;
			this.posMachineId = posMachineId;
			this.cashierId = cashierId;
			this.showPending = showPending;
		}
	}

	/** Every listed line and the summary of the period (no paging: the Excel file and the page's numbers). */
	public CashDifferencesDTO all(Filter filter, boolean amountsVisible) {
		List<CashierSession> sessions = figures.openedBetween(filter.from.atStartOfDay(), filter.to.atTime(23, 59, 59),
				filter.cashierId, filter.posMachineId);
		// the figures read once for the lines and the block "À surveiller" (step C)
		Map<Long, SessionFiguresService.Figures> sessionsFigures = sessionFigures
				.of(sessions.stream().map(CashierSession::getId).collect(Collectors.toList()));
		List<SessionDashboardDTO> dashboards = sessionFigures.dashboards(sessions, sessionsFigures);
		Map<Long, OpeningUnverifiedDTO> unverified = new HashMap<>();
		if (drawerHandoverService != null) {
			for (List<Long> chunk : SessionFiguresService
					.chunks(sessions.stream().map(CashierSession::getId).collect(Collectors.toList()))) {
				unverified.putAll(drawerHandoverService.unverifiedBySession(chunk));
			}
		}
		CashDifferencesDTO result = new CashDifferencesDTO();
		result.setFrom(filter.from);
		result.setTo(filter.to);
		result.setPosMachineId(filter.posMachineId);
		result.setCashierId(filter.cashierId);
		result.setPeriodSessions(sessions.size());
		result.setAmountsHidden(!amountsVisible);
		List<Line> lines = new ArrayList<>();
		for (SessionDashboardDTO dashboard : dashboards) {
			Line line = line(dashboard, unverified.get(dashboard.getId()), amountsVisible);
			if (line != null) {
				lines.add(line);
			}
		}
		// the switch off: the sessions waiting for their clôture with no other reason are left out of the lines only; the
		// summary keeps them (their counts; they add nothing to a sum: no difference, no écart)
		List<Line> listed = filter.showPending ? lines
				: lines.stream().filter(CashDifferencesService::hasOtherReason).collect(Collectors.toList());
		result.setShowPending(filter.showPending);
		result.setContent(listed);
		result.setListedSessions(listed.size());
		result.setTotalElements(listed.size());
		result.setHiddenClosingDifferences(lines.stream().filter(Line::isClosingDifferenceHidden).count());
		result.setHiddenOpeningGaps(lines.stream().filter(Line::isOpeningGapHidden).count()); // 3.2.0
		summary(result, lines, listed);
		// "Par caissier" has every cashier of the period, a row of zeros for one with nothing (step C: the same cashiers
		// as "À surveiller")
		addCashiersWithNothing(result, dashboards);
		CashWatch.fill(result, dashboards, sessionsFigures, watchRepository, amountsVisible);
		return result;
	}

	/** The period's cashiers missing from "Par caissier" (no line with a reason), with zeros, in the rows' order. */
	static void addCashiersWithNothing(CashDifferencesDTO result, List<SessionDashboardDTO> dashboards) {
		Map<Long, String> names = new LinkedHashMap<>();
		dashboards.forEach(d -> names.putIfAbsent(d.getCashierId(), d.getCashierFullName()));
		List<CashierRow> rows = new ArrayList<>(result.getCashiers());
		names.forEach((id, name) -> {
			if (rows.stream().noneMatch(r -> java.util.Objects.equals(r.getCashierId(), id))) {
				CashierRow row = new CashierRow();
				row.setCashierId(id);
				row.setCashierName(name);
				rows.add(row);
			}
		});
		rows.sort(CASHIER_ORDER);
		result.setCashiers(rows);
	}

	/** By name (case ignored), then id: the order of "Par caissier" and "À surveiller". */
	static final Comparator<CashierRow> CASHIER_ORDER = Comparator
			.comparing((CashierRow r) -> r.getCashierName() == null ? "" : r.getCashierName(), String.CASE_INSENSITIVE_ORDER)
			.thenComparing(r -> r.getCashierId() == null ? 0L : r.getCashierId());

	/** One page of the lines (newest first), with the summary of the whole period. */
	public CashDifferencesDTO page(Filter filter, boolean amountsVisible, int page, int size) {
		CashDifferencesDTO result = all(filter, amountsVisible);
		int pageSize = Math.max(1, size);
		int pageNumber = Math.max(0, page);
		List<Line> lines = result.getContent();
		int start = Math.min(lines.size(), pageNumber * pageSize);
		result.setContent(new ArrayList<>(lines.subList(start, Math.min(lines.size(), start + pageSize))));
		result.setNumber(pageNumber);
		result.setSize(pageSize);
		result.setTotalPages((int) Math.ceil(lines.size() / (double) pageSize));
		return result;
	}

	/**
	 * The session's line, or null when it has none of the five reasons: a difference at the fermeture, a difference at the
	 * clôture, an écart d'ouverture, opened without the previous session finally closed, closed and not yet finally
	 * closed. Without "Voir montants session" the fermeture's difference of a session not finally closed is hidden (the
	 * Sessions list masks it until the responsible's blind count); the session stays listed (a CLOSED one always is).
	 */
	static Line line(SessionDashboardDTO dashboard, OpeningUnverifiedDTO unverified, boolean amountsVisible) {
		boolean pending = dashboard.getStatus() == SessionStatus.CLOSED;
		boolean hidden = SessionAmounts.hidden(amountsVisible, dashboard.getStatus());
		// 3.2.0: the écart d'ouverture of a session not finally closed is hidden without the right (the opening cash enters
		// the expected cash) and, as the hidden fermeture difference, is no reason: listing an OPENED session for it alone
		// would tell that it is not 0
		Double openingGap = hidden ? null : dashboard.getOpeningGap();
		boolean listed = nonZero(dashboard.getCashDifference()) || nonZero(dashboard.getResponsibleDifference())
				|| nonZero(openingGap) || unverified != null || pending;
		if (!listed) {
			return null;
		}
		Line line = new Line();
		line.setSessionId(dashboard.getId());
		line.setSessionNumber(dashboard.getSessionNumber());
		line.setOpenedAt(dashboard.getOpenedAt());
		line.setClosedAt(dashboard.getClosedAt());
		line.setCashierId(dashboard.getCashierId());
		line.setCashierName(dashboard.getCashierFullName());
		line.setPosMachineName(dashboard.getPosMachineName());
		line.setStatus(dashboard.getStatus());
		if (hidden && dashboard.getCashDifference() != null) {
			line.setClosingDifferenceHidden(true);
		} else {
			line.setClosingDifference(dashboard.getCashDifference());
		}
		line.setFinalClosingDifference(dashboard.getResponsibleDifference());
		line.setOpeningGap(openingGap);
		line.setOpeningGapHidden(hidden && dashboard.getOpeningGap() != null);
		line.setOpenedWithoutFinalClosing(unverified);
		line.setPendingFinalClosing(pending);
		return line;
	}

	/**
	 * A reason other than waiting for the clôture. A fermeture difference hidden (without "Voir montants session") is
	 * not one: listing only the waiting sessions whose hidden difference is not 0 would tell the responsible which
	 * drawers are off before his blind count.
	 */
	static boolean hasOtherReason(Line line) {
		return nonZero(line.getClosingDifference()) || nonZero(line.getFinalClosingDifference())
				|| nonZero(line.getOpeningGap()) || line.getOpenedWithoutFinalClosing() != null;
	}

	static boolean nonZero(Double value) {
		return value != null && Math.abs(value) >= ZERO;
	}

	/**
	 * One row per cashier of the lines (by name, then id), and the total: the signed sums and the shortages alone, the
	 * counts of every line with a reason (the waiting ones too, whatever the switch); "sessions" counts the listed ones.
	 */
	static void summary(CashDifferencesDTO result, List<Line> lines, List<Line> listed) {
		Map<Long, List<Line>> byCashier = new LinkedHashMap<>();
		for (Line line : lines) {
			byCashier.computeIfAbsent(line.getCashierId(), id -> new ArrayList<>()).add(line);
		}
		List<CashierRow> rows = byCashier.values().stream().map(CashDifferencesService::row)
				.sorted(Comparator.comparing((CashierRow r) -> r.getCashierName() == null ? "" : r.getCashierName(),
						String.CASE_INSENSITIVE_ORDER).thenComparing(r -> r.getCashierId() == null ? 0L : r.getCashierId()))
				.collect(Collectors.toList());
		rows.forEach(r -> r.setSessions(listed.stream()
				.filter(l -> java.util.Objects.equals(l.getCashierId(), r.getCashierId())).count()));
		result.setCashiers(rows);
		CashierRow total = row(lines);
		total.setCashierId(null);
		total.setCashierName(null);
		total.setSessions(listed.size());
		result.setTotal(total);
	}

	private static CashierRow row(List<Line> lines) {
		CashierRow row = new CashierRow();
		if (!lines.isEmpty()) {
			row.setCashierId(lines.get(0).getCashierId());
			row.setCashierName(lines.get(0).getCashierName());
		}
		row.setSessions(lines.size());
		row.setClosingTotal(sum(lines, Line::getClosingDifference, false));
		row.setClosingShortage(sum(lines, Line::getClosingDifference, true));
		row.setFinalClosingTotal(sum(lines, Line::getFinalClosingDifference, false));
		row.setFinalClosingShortage(sum(lines, Line::getFinalClosingDifference, true));
		row.setOpeningGapTotal(sum(lines, Line::getOpeningGap, false));
		row.setOpeningGapShortage(sum(lines, Line::getOpeningGap, true));
		row.setOpenedWithoutFinalClosing(lines.stream().filter(l -> l.getOpenedWithoutFinalClosing() != null).count());
		row.setPendingFinalClosing(lines.stream().filter(Line::isPendingFinalClosing).count());
		return row;
	}

	/** The values' sum to the millime (BigDecimal: no 0.1 + 0.2), or of the negative ones alone. */
	private static double sum(List<Line> lines, java.util.function.Function<Line, Double> field, boolean negativeOnly) {
		BigDecimal sum = BigDecimal.ZERO;
		for (Line line : lines) {
			Double value = field.apply(line);
			if (nonZero(value) && (!negativeOnly || value < 0)) {
				sum = sum.add(BigDecimal.valueOf(value));
			}
		}
		return sum.setScale(3, RoundingMode.HALF_UP).doubleValue();
	}
}
