package com.digithink.zsretail.store.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.YearMonth;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.digithink.zsretail.store.dto.DayJournalDTO;
import com.digithink.zsretail.store.dto.DayJournalDTO.JournalLine;
import com.digithink.zsretail.store.dto.DayRowDTO;

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
			return bytes(workbook);
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
