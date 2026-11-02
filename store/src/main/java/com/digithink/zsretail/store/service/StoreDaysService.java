package com.digithink.zsretail.store.service;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.enumeration.ItemType;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.repository.CashierSessionRepository;
import com.digithink.zsretail.core.repository.ReturnHeaderRepository;
import com.digithink.zsretail.core.repository.ReturnLineRepository;
import com.digithink.zsretail.core.repository.SalesHeaderRepository;
import com.digithink.zsretail.core.repository.SalesLineRepository;
import com.digithink.zsretail.store.dto.DayRowDTO;

/**
 * 3.1.0: the days of a month, read-only (docs/modules/cash-sessions.md, "Journal"): one row per date with at least
 * one finished ticket, cancelled ticket, return, session closed or session opened and still open, newest first. The
 * tickets by their salesDate and the returns by their returnDate (as the day journal); the sessions closed by their closedAt (as the store ticket
 * Z). Light queries: dates and amounts only, no entity of a ticket or a return.
 */
@Service
public class StoreDaysService {

	@Autowired
	private SalesHeaderRepository salesHeaderRepository;

	@Autowired
	private ReturnHeaderRepository returnHeaderRepository;

	@Autowired
	private CashierSessionRepository cashierSessionRepository;

	/** 3.1.2, the gains: the lines of the month's tickets and returns (one projection each). */
	@Autowired
	private SalesLineRepository salesLineRepository;

	@Autowired
	private ReturnLineRepository returnLineRepository;

	/** Today, for inProgress; a test sets its own. */
	java.time.Clock clock = java.time.Clock.systemDefaultZone();

	/** The days without the gains (as before 3.1.2). */
	public List<DayRowDTO> buildMonth(YearMonth month) {
		return buildMonth(month, false);
	}

	/**
	 * The days; with withMargins (the caller checks the installation and the right read:admin-margins) each day carries
	 * the gains of its tickets and returns (JournalCosts), from one projection of their lines per kind: the same numbers
	 * as the day journal's totals.
	 */
	public List<DayRowDTO> buildMonth(YearMonth month, boolean withMargins) {
		LocalDateTime from = month.atDay(1).atStartOfDay();
		LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
		Map<LocalDate, Day> days = new TreeMap<>(Comparator.reverseOrder());

		for (Object[] row : salesHeaderRepository.findFinishedTimesAndTotals(from, to, SessionReportService.EXCLUDED)) {
			Day day = day(days, row[0]);
			day.row.setTicketsCount(day.row.getTicketsCount() + 1);
			day.ttc = day.ttc.add(DayJournalService.bd(number(row[1])));
		}
		for (LocalDateTime time : salesHeaderRepository.findSalesDatesByStatusBetween(TransactionStatus.CANCELLED, from,
				to)) {
			Day day = day(days, time);
			day.row.setCancelledCount(day.row.getCancelledCount() + 1);
		}
		for (Object[] row : returnHeaderRepository.findReturnTimesAndAmounts(from, to)) {
			Day day = day(days, row[0]);
			day.row.setReturnsCount(day.row.getReturnsCount() + 1);
			day.returns = day.returns.add(DayJournalService.bd(number(row[1])));
		}
		for (CashierSession session : cashierSessionRepository
				.findByStatusInAndClosedAtGreaterThanEqualAndClosedAtLessThanOrderByOpenedAtAsc(StoreReportService.CLOSED,
						from, to)) {
			Day day = day(days, session.getClosedAt());
			day.row.setSessionsClosed(day.row.getSessionsClosed() + 1);
		}
		for (CashierSession session : cashierSessionRepository
				.findByStatusAndOpenedAtGreaterThanEqualAndOpenedAtLessThan(SessionStatus.OPENED, from, to)) {
			Day day = day(days, session.getOpenedAt());
			day.row.setSessionsOpen(day.row.getSessionsOpen() + 1);
		}
		// in progress: today, while a session is open, whatever its opening day (it sells into today's journal)
		Day today = days.get(LocalDate.now(clock));
		if (today != null && cashierSessionRepository.countByStatus(SessionStatus.OPENED) > 0) {
			today.row.setInProgress(true);
		}

		if (withMargins) {
			gains(days, from, to);
		}

		List<DayRowDTO> rows = new ArrayList<>();
		for (Day day : days.values()) {
			day.row.setTotalTTC(day.ttc.doubleValue());
			day.row.setReturnsAmount(day.returns.doubleValue());
			day.row.setNet(day.ttc.subtract(day.returns).doubleValue());
			rows.add(day.row);
		}
		return rows;
	}

	/** 3.1.2: each document's gains from its lines (JournalCosts), added up per day; all null on a day without cost. */
	private void gains(Map<LocalDate, Day> days, LocalDateTime from, LocalDateTime to) {
		Map<LocalDate, List<JournalCosts.Result>> byDay = new HashMap<>();
		for (Document document : documents(
				salesLineRepository.findCostLinesOfFinishedBetween(from, to, SessionReportService.EXCLUDED), true)) {
			byDay.computeIfAbsent(document.date, d -> new ArrayList<>())
					.add(JournalCosts.ticket(document.lines, document.amount));
		}
		for (Document document : documents(returnLineRepository.findCostLinesOfReturnsBetween(from, to), false)) {
			byDay.computeIfAbsent(document.date, d -> new ArrayList<>())
					.add(JournalCosts.returned(document.lines, document.amount));
		}
		byDay.forEach((date, results) -> {
			Day day = days.get(date);
			if (day == null) {
				return; // a document of a day the month does not list (cannot happen: the same dates)
			}
			JournalCosts.Result result = JournalCosts.day(results);
			day.row.setCost(result.costValue());
			day.row.setMargin(result.marginValue());
			day.row.setMarginPercent(result.marginPercent);
			day.row.setLinesWithoutCost(result.linesWithoutCost);
		});
	}

	/** One ticket or return of the projection: its date, its amount (total paid or refund) and its lines. */
	private static final class Document {
		LocalDate date;
		Double amount;
		final List<JournalCosts.Line> lines = new ArrayList<>();
	}

	/**
	 * The projection's rows by document, in reading order. A ticket's row: {id, date, total, item code, item type, TTC, HT,
	 * VAT %, quantity, unit cost}; a return's: the same without the VAT %.
	 */
	private static List<Document> documents(List<Object[]> rows, boolean tickets) {
		Map<Long, Document> byId = new LinkedHashMap<>();
		for (Object[] row : rows) {
			Document document = byId.computeIfAbsent(((Number) row[0]).longValue(), id -> new Document());
			document.date = dateTime(row[1]).toLocalDate();
			document.amount = number(row[2]);
			int at = tickets ? 8 : 7;
			document.lines.add(new JournalCosts.Line((String) row[3], (ItemType) row[4], number(row[5]), number(row[6]),
					tickets && row[7] != null ? ((Number) row[7]).intValue() : null, decimal(row[at]), decimal(row[at + 1])));
		}
		return new ArrayList<>(byId.values());
	}

	private static BigDecimal decimal(Object value) {
		if (value == null) {
			return null;
		}
		return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString());
	}

	private static final class Day {
		final DayRowDTO row = new DayRowDTO();
		BigDecimal ttc = BigDecimal.ZERO.setScale(3);
		BigDecimal returns = BigDecimal.ZERO.setScale(3);
	}

	private static Day day(Map<LocalDate, Day> days, Object time) {
		LocalDate date = dateTime(time).toLocalDate();
		return days.computeIfAbsent(date, d -> {
			Day day = new Day();
			day.row.setDate(d);
			return day;
		});
	}

	/** A projected time: LocalDateTime (the attribute's type), or Timestamp should a driver give one. */
	private static LocalDateTime dateTime(Object value) {
		return value instanceof Timestamp ? ((Timestamp) value).toLocalDateTime() : (LocalDateTime) value;
	}

	private static Double number(Object value) {
		return value instanceof Number ? ((Number) value).doubleValue() : null;
	}
}
