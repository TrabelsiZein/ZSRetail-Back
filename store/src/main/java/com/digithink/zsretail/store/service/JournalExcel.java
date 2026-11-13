package com.digithink.zsretail.store.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.YearMonth;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.digithink.zsretail.store.dto.DayJournalDTO;
import com.digithink.zsretail.store.dto.DayJournalDTO.JournalLine;
import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.store.dto.DayRowDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.ControlLine;
import com.digithink.zsretail.store.dto.SessionReportTicketDTO.PaymentLine;
import com.digithink.zsretail.store.dto.StoreReportTicketDTO;
import com.digithink.zsretail.store.dto.StoreReportTicketDTO.PosMachineGroup;
import com.digithink.zsretail.store.dto.StoreReportTicketDTO.SessionRow;

/**
 * 3.1.0: the day journal and the month's days as .xlsx (Apache POI, in core): one sheet, a header row, one row per
 * line, a totals row; amounts with 3 decimals. The headers are in French, as the printed tickets of the store.
 */
public final class JournalExcel {

	public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

	private static final String[] JOURNAL_HEADERS = { "Heure", "N°", "Type", "Session", "Caissier", "Client",
			"Total HT", "TVA", "Timbre", "Total TTC", "Paiements" };

	private static final String[] DAYS_HEADERS = { "Date", "Sessions fermées", "Sessions ouvertes", "Tickets",
			"Retours", "Annulés", "Total TTC", "Montant retours", "Net", "En cours" };

	private JournalExcel() {
	}

	/** File name of a day journal: journal-2026-10-13.xlsx. */
	public static String journalFileName(DayJournalDTO journal) {
		return "journal-" + journal.getDate() + ".xlsx";
	}

	/** File name of a month: jours-2026-10.xlsx. */
	public static String daysFileName(YearMonth month) {
		return "jours-" + month + ".xlsx";
	}

	/**
	 * The journal: one row per line (a return negative), then two totals rows: "Total ventes TTC" (the finished
	 * tickets) and "Net" (the amount columns added up, returns included: its TTC is tickets - returns), with the totals
	 * per method.
	 */
	public static byte[] journal(DayJournalDTO journal) throws IOException {
		return journal(journal, false);
	}

	/** 3.1.2: with withMargins, the columns Coût, Marge and Lignes sans coût after the others (JournalCosts). */
	public static byte[] journal(DayJournalDTO journal, boolean withMargins) throws IOException {
		return journal(journal, withMargins, null);
	}

	/**
	 * 3.2.0, POS machines step 3: with the day's store ticket grouped per POS machine (byMachine.posMachines, the drawer
	 * of the POS machine), a second sheet "Postes de caisse" with the page's per-machine subtotals; the journal sheet
	 * unchanged. byMachine null or without groups: the journal alone, as before.
	 */
	public static byte[] journal(DayJournalDTO journal, boolean withMargins, StoreReportTicketDTO byMachine)
			throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			Styles styles = new Styles(workbook);
			Sheet sheet = workbook.createSheet("Journal " + journal.getDate());
			String[] headers = withMargins ? concat(JOURNAL_HEADERS, MARGIN_HEADERS) : JOURNAL_HEADERS;
			header(sheet, headers, styles);
			int r = 1;
			for (JournalLine line : journal.getLines()) {
				Row row = sheet.createRow(r++);
				date(row, 0, line.getTime(), styles.time);
				text(row, 1, line.getNumber());
				text(row, 2, typeLabel(line.getType()));
				text(row, 3, line.getSessionNumber());
				text(row, 4, line.getCashierName());
				text(row, 5, line.getCustomerName());
				amount(row, 6, line.getTotalHT(), styles.amount);
				amount(row, 7, line.getTotalVat(), styles.amount);
				amount(row, 8, line.getTaxStamp(), styles.amount);
				amount(row, 9, line.getTotalTTC(), styles.amount);
				text(row, 10, payments(line.getPayments()));
				if (withMargins) {
					margins(row, 11, line.getCost(), line.getMargin(), line.getLinesWithoutCost(), styles, false);
				}
			}
			DayJournalDTO.Totals totals = journal.getTotals();
			// the finished tickets' totals
			Row sales = sheet.createRow(r++);
			text(sales, 0, "Total ventes TTC", styles.bold);
			text(sales, 2, totals.getTicketsCount() + " tickets, " + totals.getReturnsCount() + " retours, "
					+ totals.getCancelledCount() + " annulés", styles.bold);
			amount(sales, 6, totals.getTotalHT(), styles.boldAmount);
			amount(sales, 7, totals.getTotalVat(), styles.boldAmount);
			amount(sales, 8, totals.getTaxStamp(), styles.boldAmount);
			amount(sales, 9, totals.getTotalTTC(), styles.boldAmount);
			// the column sums: returns included, so the TTC is the day's net; the payments per method add up to it
			Row net = sheet.createRow(r);
			text(net, 0, "Net", styles.bold);
			amount(net, 6, columnSum(journal, JournalLine::getTotalHT), styles.boldAmount);
			amount(net, 7, columnSum(journal, JournalLine::getTotalVat), styles.boldAmount);
			amount(net, 8, columnSum(journal, JournalLine::getTaxStamp), styles.boldAmount);
			amount(net, 9, columnSum(journal, JournalLine::getTotalTTC), styles.boldAmount);
			text(net, 10, payments(totals.getPayments()), styles.bold);
			if (withMargins) {
				margins(net, 11, totals.getCost(), totals.getMargin(), totals.getLinesWithoutCost(), styles, true);
			}
			autosize(sheet, headers.length);
			if (byMachine != null && byMachine.getPosMachines() != null) {
				posMachines(workbook.createSheet("Postes de caisse"), byMachine, styles);
			}
			return bytes(workbook);
		}
	}

	// ─── Par poste de caisse (3.2.0, POS machines step 3) ────────────

	/** The label of a POS machine; "—" for the sessions without one. */
	private static String machineLabel(String name) {
		return name != null ? name : "—";
	}

	/**
	 * The sheet "Postes de caisse": the store ticket's groups (the X today, the Z another day), three tables one under
	 * the other: the sessions of each machine; the subtotals per machine and the store's total; the payments and the
	 * control per machine and method and the store's.
	 */
	private static void posMachines(Sheet sheet, StoreReportTicketDTO ticket, Styles styles) {
		int r = 0;
		text(sheet.createRow(r++), 0, "Ticket " + ticket.getType() + " du " + ticket.getDate() + " par poste de caisse",
				styles.bold);
		r++;

		// the sessions
		r = headerAt(sheet, r, new String[] { "Poste de caisse", "Session", "Caissier", "Ouverture", "Clôture",
				"Statut" }, styles);
		for (PosMachineGroup group : ticket.getPosMachines()) {
			for (SessionRow s : group.getSessions()) {
				Row row = sheet.createRow(r++);
				text(row, 0, machineLabel(group.getPosMachineName()));
				text(row, 1, s.getSessionNumber());
				text(row, 2, s.getCashierName());
				date(row, 3, s.getOpenedAt(), styles.time);
				date(row, 4, s.getClosedAt(), styles.time);
				text(row, 5, statusLabel(s.getStatus()));
			}
		}
		r++;

		// the subtotals
		r = headerAt(sheet, r, new String[] { "Poste de caisse", "Sessions", "Tickets", "Annulés", "Total HT", "TVA",
				"Timbre", "Total TTC", "Retours", "Net", "Fond de caisse" }, styles);
		for (PosMachineGroup group : ticket.getPosMachines()) {
			r = subtotal(sheet.createRow(r), r, machineLabel(group.getPosMachineName()), group.getSessions().size(),
					group.getSales(), group.getReturns(), group.getNetSales(), group.getOpeningCash(), styles, false);
		}
		r = subtotal(sheet.createRow(r), r, "Total magasin", ticket.getSessions().size(), ticket.getSales(),
				ticket.getReturns(), ticket.getNetSales(), ticket.getOpeningCash(), styles, true);
		r++;

		// the payments and the control per method
		r = headerAt(sheet, r, new String[] { "Poste de caisse", "Mode de paiement", "Paiements", "Attendu",
				"Compté", "Écart", "Tickets attendus", "Tickets comptés", "Écart tickets" },
				styles);
		for (PosMachineGroup group : ticket.getPosMachines()) {
			r = methods(sheet, r, machineLabel(group.getPosMachineName()), group.getPayments(), group.getControl(),
					styles, false);
		}
		methods(sheet, r, "Total magasin", ticket.getPayments(), ticket.getControl(), styles, true);
		autosize(sheet, 11);
	}

	private static int headerAt(Sheet sheet, int r, String[] headers, Styles styles) {
		Row row = sheet.createRow(r);
		for (int i = 0; i < headers.length; i++) {
			text(row, i, headers[i], styles.header);
		}
		return r + 1;
	}

	private static int subtotal(Row row, int r, String label, int sessions, SessionReportTicketDTO.Sales sales,
			SessionReportTicketDTO.Returns returns, Double net, Double openingCash, Styles styles, boolean bold) {
		CellStyle amount = bold ? styles.boldAmount : styles.amount;
		text(row, 0, label, bold ? styles.bold : null);
		count(row, 1, (long) sessions, bold ? styles.bold : null);
		row.createCell(2).setCellValue(sales.getTicketsCount());
		row.createCell(3).setCellValue(sales.getCancelledCount());
		amount(row, 4, sales.getTotalHT(), amount);
		amount(row, 5, sales.getTotalVat(), amount);
		amount(row, 6, sales.getTaxStamp(), amount);
		amount(row, 7, sales.getTotalTTC(), amount);
		amount(row, 8, returns.getTotalAmount(), amount);
		amount(row, 9, net, amount);
		amount(row, 10, openingCash, amount);
		return r + 1;
	}

	/**
	 * One row per method of a group: the control's methods in the till's order, then a method paid but not in the
	 * control; an empty cell for a value absent (counted on a ticket X, a method not declared).
	 */
	private static int methods(Sheet sheet, int r, String label, List<PaymentLine> payments, List<ControlLine> control,
			Styles styles, boolean bold) {
		CellStyle amount = bold ? styles.boldAmount : styles.amount;
		Map<String, PaymentLine> paid = new LinkedHashMap<>();
		payments.forEach(p -> paid.put(p.getCode(), p));
		Set<String> done = new HashSet<>();
		for (ControlLine c : control) {
			done.add(c.getCode());
			Row row = sheet.createRow(r++);
			text(row, 0, label, bold ? styles.bold : null);
			text(row, 1, c.getName(), bold ? styles.bold : null);
			PaymentLine p = paid.get(c.getCode());
			amount(row, 2, p != null ? p.getAmount() : null, amount);
			amount(row, 3, c.getExpected(), amount);
			amount(row, 4, c.getCounted(), amount);
			amount(row, 5, c.getDifference(), amount);
			if (c.isScanned()) {
				number(row, 6, c.getExpectedCount());
				number(row, 7, c.getCountedCount());
				number(row, 8, c.getCountDifference());
			}
		}
		for (PaymentLine p : payments) {
			if (!done.contains(p.getCode())) {
				Row row = sheet.createRow(r++);
				text(row, 0, label, bold ? styles.bold : null);
				text(row, 1, p.getName(), bold ? styles.bold : null);
				amount(row, 2, p.getAmount(), amount);
			}
		}
		return r;
	}

	/** A count kept as it is (0 and negative included); an empty cell when absent. */
	private static void number(Row row, int column, Long value) {
		if (value != null) {
			row.createCell(column).setCellValue(value);
		}
	}

	private static String statusLabel(SessionStatus status) {
		if (status == null) {
			return null;
		}
		switch (status) {
			case OPENED:
				return "Ouvert";
			case CLOSED:
				return "Fermé";
			default:
				return "Terminé";
		}
	}

	/** The month's days, newest first, and a totals row. */
	public static byte[] days(YearMonth month, List<DayRowDTO> days) throws IOException {
		return days(month, days, null);
	}

	/**
	 * 3.1.2: with the month's gains (StoreDaysService.monthGains; null: none), the columns Coût, Marge, Marge % and Lignes
	 * sans coût after the others; the total row is the month's, by the same rule as a day (its lines without cost those
	 * of all its tickets and returns).
	 */
	public static byte[] days(YearMonth month, List<DayRowDTO> days, JournalCosts.Result monthGains) throws IOException {
		boolean withMargins = monthGains != null;
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			Styles styles = new Styles(workbook);
			Sheet sheet = workbook.createSheet("Jours " + month);
			String[] headers = withMargins ? concat(DAYS_HEADERS, DAYS_MARGIN_HEADERS) : DAYS_HEADERS;
			header(sheet, headers, styles);
			int r = 1;
			long[] counts = new long[5];
			double[] amounts = new double[3];
			for (DayRowDTO day : days) {
				Row row = sheet.createRow(r++);
				Cell date = row.createCell(0);
				date.setCellValue(day.getDate());
				date.setCellStyle(styles.date);
				long[] values = { day.getSessionsClosed(), day.getSessionsOpen(), day.getTicketsCount(),
						day.getReturnsCount(), day.getCancelledCount() };
				for (int i = 0; i < values.length; i++) {
					row.createCell(1 + i).setCellValue(values[i]);
					counts[i] += values[i];
				}
				Double[] money = { day.getTotalTTC(), day.getReturnsAmount(), day.getNet() };
				for (int i = 0; i < money.length; i++) {
					amount(row, 6 + i, money[i], styles.amount);
					amounts[i] = DayJournalService.bd(amounts[i]).add(DayJournalService.bd(money[i])).doubleValue();
				}
				text(row, 9, day.isInProgress() ? "Oui" : "Non");
				if (withMargins) {
					amount(row, 10, day.getCost(), styles.amount);
					amount(row, 11, day.getMargin(), styles.amount);
					amount(row, 12, day.getMarginPercent(), styles.percent);
					count(row, 13, day.getLinesWithoutCost(), null);
				}
			}
			Row row = sheet.createRow(r);
			text(row, 0, "TOTAL", styles.bold);
			for (int i = 0; i < counts.length; i++) {
				Cell cell = row.createCell(1 + i);
				cell.setCellValue(counts[i]);
				cell.setCellStyle(styles.bold);
			}
			for (int i = 0; i < amounts.length; i++) {
				amount(row, 6 + i, amounts[i], styles.boldAmount);
			}
			if (withMargins && monthGains.costValue() != null) {
				amount(row, 10, monthGains.costValue(), styles.boldAmount);
				amount(row, 11, monthGains.marginValue(), styles.boldAmount);
				amount(row, 12, monthGains.marginPercent, styles.boldPercent);
				count(row, 13, monthGains.linesWithoutCost, styles.bold);
			}
			autosize(sheet, headers.length);
			return bytes(workbook);
		}
	}

	// ─── Gains (3.1.2) ───────────────────────────────────────────────

	private static final String[] MARGIN_HEADERS = { "Coût", "Marge", "Lignes sans coût" };

	private static final String[] DAYS_MARGIN_HEADERS = { "Coût", "Marge", "Marge %", "Lignes sans coût" };

	/** Cost, margin and the lines without cost from the column given (empty cells when absent). */
	private static void margins(Row row, int column, Double cost, Double margin, Long without, Styles styles,
			boolean bold) {
		amount(row, column, cost, bold ? styles.boldAmount : styles.amount);
		amount(row, column + 1, margin, bold ? styles.boldAmount : styles.amount);
		count(row, column + 2, without, bold ? styles.bold : null);
	}

	/** A count, its cell left empty when 0 or absent. */
	private static void count(Row row, int column, Long value, CellStyle style) {
		if (value != null && value > 0) {
			Cell cell = row.createCell(column);
			cell.setCellValue(value);
			if (style != null) {
				cell.setCellStyle(style);
			}
		}
	}

	private static String[] concat(String[] first, String[] second) {
		String[] all = java.util.Arrays.copyOf(first, first.length + second.length);
		System.arraycopy(second, 0, all, first.length, second.length);
		return all;
	}

	// ─── Helpers ─────────────────────────────────────────────────────

	private static final class Styles {
		final CellStyle header;
		final CellStyle bold;
		final CellStyle amount;
		final CellStyle boldAmount;
		final CellStyle time;
		final CellStyle date;
		/** 3.1.2: a margin %, one decimal. */
		final CellStyle percent;
		final CellStyle boldPercent;

		Styles(XSSFWorkbook workbook) {
			Font boldFont = workbook.createFont();
			boldFont.setBold(true);
			short threeDecimals = workbook.createDataFormat().getFormat("#,##0.000");
			header = workbook.createCellStyle();
			header.setFont(boldFont);
			bold = workbook.createCellStyle();
			bold.setFont(boldFont);
			amount = workbook.createCellStyle();
			amount.setDataFormat(threeDecimals);
			boldAmount = workbook.createCellStyle();
			boldAmount.setDataFormat(threeDecimals);
			boldAmount.setFont(boldFont);
			time = workbook.createCellStyle();
			time.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm:ss"));
			date = workbook.createCellStyle();
			date.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy"));
			short oneDecimal = workbook.createDataFormat().getFormat("0.0");
			percent = workbook.createCellStyle();
			percent.setDataFormat(oneDecimal);
			boldPercent = workbook.createCellStyle();
			boldPercent.setDataFormat(oneDecimal);
			boldPercent.setFont(boldFont);
		}
	}

	private static void header(Sheet sheet, String[] headers, Styles styles) {
		Row row = sheet.createRow(0);
		for (int i = 0; i < headers.length; i++) {
			text(row, i, headers[i], styles.header);
		}
	}

	private static void text(Row row, int column, String value) {
		if (value != null) {
			row.createCell(column).setCellValue(value);
		}
	}

	private static void text(Row row, int column, String value, CellStyle style) {
		Cell cell = row.createCell(column);
		if (value != null) {
			cell.setCellValue(value);
		}
		cell.setCellStyle(style);
	}

	private static void amount(Row row, int column, Double value, CellStyle style) {
		Cell cell = row.createCell(column);
		if (value != null) {
			cell.setCellValue(value);
		}
		cell.setCellStyle(style);
	}

	private static void date(Row row, int column, java.time.LocalDateTime value, CellStyle style) {
		if (value != null) {
			Cell cell = row.createCell(column);
			cell.setCellValue(value);
			cell.setCellStyle(style);
		}
	}

	private static String typeLabel(String type) {
		switch (type) {
			case DayJournalDTO.TICKET:
				return "Ticket";
			case DayJournalDTO.RETURN:
				return "Retour";
			default:
				return "Annulé";
		}
	}

	private static String payments(List<DayJournalDTO.MethodAmount> payments) {
		return payments.stream().map(p -> p.getMethodName() + " " + String.format(java.util.Locale.ROOT, "%.3f", p.getAmount()))
				.collect(Collectors.joining("; "));
	}

	private static Double columnSum(DayJournalDTO journal, java.util.function.Function<JournalLine, Double> field) {
		return journal.getLines().stream().map(l -> DayJournalService.bd(field.apply(l)))
				.reduce(java.math.BigDecimal.ZERO.setScale(3), java.math.BigDecimal::add).doubleValue();
	}

	private static void autosize(Sheet sheet, int columns) {
		for (int i = 0; i < columns; i++) {
			sheet.autoSizeColumn(i);
		}
	}

	private static byte[] bytes(XSSFWorkbook workbook) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		workbook.write(out);
		return out.toByteArray();
	}
}
