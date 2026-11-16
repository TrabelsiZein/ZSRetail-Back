package com.digithink.zsretail.store.cashdifference;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.CashierRow;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.Line;
import com.digithink.zsretail.store.cashdifference.CashDifferencesDTO.WatchRow;
import com.digithink.zsretail.store.service.JournalExcel;

/**
 * 3.2.0, the differences, step B: the Excel file of the page "Écarts de caisse", as the Journal's (French headers,
 * amounts to the millime, an empty cell when absent or 0): the sheet "Écarts de caisse" (every listed line, not only
 * the page shown) and the sheet "Résumé" (the per-cashier rows and the total).
 */
public final class CashDifferencesExcel {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	static final String[] LINE_HEADERS = { "Session", "Ouverte le", "Caissier", "Poste de caisse", "Statut",
			"Écart fermeture", "Écart clôture", "Écart d'ouverture", "Remarques" };

	/** Step C: the sheet "À surveiller". */
	static final String[] WATCH_HEADERS = { "Caissier", "Tickets", "Tickets annulés", "Montant annulé", "Retours",
			"Montant retours", "Tickets avec remise", "Remises", "dont manuelles (tickets)", "dont manuelles (montant)",
			"Dépenses", "Montant dépenses", "Prélèvements", "Montant prélèvements", "Autorisations refusées" };

	static final String[] SUMMARY_HEADERS = { "Caissier", "Sessions avec écart", "Écart fermeture",
			"dont manquants", "Écart clôture", "dont manquants", "Écart d'ouverture", "dont manquants",
			"Ouvertes sans clôture de la précédente", "En attente de clôture" };

	/** Shown in place of a fermeture difference the user may not see (the screen's ******). */
	static final String HIDDEN = "******";

	private CashDifferencesExcel() {
	}

	public static String fileName(LocalDate from, LocalDate to) {
		return "ecarts-de-caisse-" + from + "-" + to + ".xlsx";
	}

	public static byte[] of(CashDifferencesDTO result) throws IOException {
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			Font boldFont = workbook.createFont();
			boldFont.setBold(true);
			CellStyle bold = workbook.createCellStyle();
			bold.setFont(boldFont);
			short threeDecimals = workbook.createDataFormat().getFormat("#,##0.000");
			CellStyle amount = workbook.createCellStyle();
			amount.setDataFormat(threeDecimals);
			CellStyle boldAmount = workbook.createCellStyle();
			boldAmount.setDataFormat(threeDecimals);
			boldAmount.setFont(boldFont);
			CellStyle time = workbook.createCellStyle();
			time.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm:ss"));

			String period = "du " + result.getFrom().format(DAY) + " au " + result.getTo().format(DAY);

			Sheet lines = workbook.createSheet("Écarts de caisse");
			int r = 0;
			text(lines.createRow(r++), 0, "Écarts de caisse " + period, bold);
			r++;
			r = header(lines, r, LINE_HEADERS, bold);
			for (Line line : result.getContent()) {
				Row row = lines.createRow(r++);
				text(row, 0, line.getSessionNumber(), null);
				if (line.getOpenedAt() != null) {
					Cell cell = row.createCell(1);
					cell.setCellValue(line.getOpenedAt());
					cell.setCellStyle(time);
				}
				text(row, 2, line.getCashierName(), null);
				text(row, 3, line.getPosMachineName() != null ? line.getPosMachineName() : "—", null);
				text(row, 4, JournalExcel.statusLabel(line.getStatus()), null);
				if (line.isClosingDifferenceHidden()) {
					text(row, 5, HIDDEN, null);
				} else {
					amount(row, 5, line.getClosingDifference(), amount);
				}
				amount(row, 6, line.getFinalClosingDifference(), amount);
				if (line.isOpeningGapHidden()) {
					text(row, 7, HIDDEN, null);
				} else {
					amount(row, 7, line.getOpeningGap(), amount);
				}
				text(row, 8, remarks(line), null);
			}
			for (int c = 0; c < LINE_HEADERS.length; c++) {
				lines.autoSizeColumn(c);
			}

			Sheet summary = workbook.createSheet("Résumé");
			r = 0;
			text(summary.createRow(r++), 0, "Écarts de caisse " + period, bold);
			text(summary.createRow(r++), 0, "Sessions listées : " + result.getListedSessions() + " sur "
					+ result.getPeriodSessions() + " sessions de la période", null);
			long pending = result.getTotal() != null ? result.getTotal().getPendingFinalClosing() : 0;
			if (!result.isShowPending() && pending > 0) {
				text(summary.createRow(r++), 0, "Sessions en attente de clôture : " + pending
						+ " (listées seulement avec un autre écart ou une remarque)", null);
			}
			if (result.getHiddenClosingDifferences() > 0) {
				text(summary.createRow(r++), 0, "Écarts de fermeture masqués (sessions non clôturées, droit « Voir montants"
						+ " session ») : " + result.getHiddenClosingDifferences(), null);
			}
			if (result.getHiddenOpeningGaps() > 0) {
				text(summary.createRow(r++), 0, "Écarts d'ouverture masqués (sessions non clôturées, droit « Voir montants"
						+ " session ») : " + result.getHiddenOpeningGaps(), null);
			}
			r++;
			r = header(summary, r, SUMMARY_HEADERS, bold);
			for (CashierRow cashier : result.getCashiers()) {
				summaryRow(summary.createRow(r++), cashier, cashier.getCashierName(), null, amount);
			}
			if (result.getTotal() != null) {
				summaryRow(summary.createRow(r++), result.getTotal(), "Total", bold, boldAmount);
			}
			for (int c = 0; c < SUMMARY_HEADERS.length; c++) {
				summary.autoSizeColumn(c);
			}

			// step C: the sheet "À surveiller" (every session of the period, whatever the switch)
			Sheet watch = workbook.createSheet("À surveiller");
			r = 0;
			text(watch.createRow(r++), 0, "À surveiller " + period, bold);
			text(watch.createRow(r++), 0, "Par caissier, toutes les sessions de la période (aucun seuil, des nombres)", null);
			if (result.getWatchHiddenSessions() > 0) {
				text(watch.createRow(r++), 0, "Montants des retours, dépenses et prélèvements masqués pour "
						+ result.getWatchHiddenSessions() + " session(s) non clôturée(s) (droit « Voir montants session »)", null);
			}
			r++;
			r = header(watch, r, WATCH_HEADERS, bold);
			for (WatchRow row : result.getWatch()) {
				watchRow(watch.createRow(r++), row, row.getCashierName(), null, amount);
			}
			if (result.getWatchTotal() != null) {
				watchRow(watch.createRow(r++), result.getWatchTotal(), "Total", bold, boldAmount);
			}
			for (int c = 0; c < WATCH_HEADERS.length; c++) {
				watch.autoSizeColumn(c);
			}

			ByteArrayOutputStream out = new ByteArrayOutputStream();
			workbook.write(out);
			return out.toByteArray();
		}
	}

	/** The line's remarks, as the screen: "En attente de clôture", "Ouverte sans clôture de <previous>". */
	static String remarks(Line line) {
		StringBuilder text = new StringBuilder();
		if (line.isPendingFinalClosing()) {
			text.append("En attente de clôture");
		}
		if (line.getOpenedWithoutFinalClosing() != null) {
			if (text.length() > 0) {
				text.append(" ; ");
			}
			text.append("Ouverte sans clôture de la précédente (")
					.append(line.getOpenedWithoutFinalClosing().getPreviousSessionNumber()).append(")");
		}
		return text.length() > 0 ? text.toString() : null;
	}

	private static void summaryRow(Row row, CashierRow values, String name, CellStyle textStyle, CellStyle amountStyle) {
		text(row, 0, name, textStyle);
		row.createCell(1).setCellValue(values.getSessions());
		sum(row, 2, values.getClosingTotal(), amountStyle);
		sum(row, 3, values.getClosingShortage(), amountStyle);
		sum(row, 4, values.getFinalClosingTotal(), amountStyle);
		sum(row, 5, values.getFinalClosingShortage(), amountStyle);
		sum(row, 6, values.getOpeningGapTotal(), amountStyle);
		sum(row, 7, values.getOpeningGapShortage(), amountStyle);
		row.createCell(8).setCellValue(values.getOpenedWithoutFinalClosing());
		row.createCell(9).setCellValue(values.getPendingFinalClosing());
	}

	private static void watchRow(Row row, WatchRow v, String name, CellStyle textStyle, CellStyle amountStyle) {
		text(row, 0, name, textStyle);
		row.createCell(1).setCellValue(v.getTickets());
		row.createCell(2).setCellValue(v.getCancelledCount());
		sum(row, 3, v.getCancelledAmount(), amountStyle);
		row.createCell(4).setCellValue(v.getReturnsCount());
		sum(row, 5, v.getReturnsAmount(), amountStyle);
		row.createCell(6).setCellValue(v.getDiscountTickets());
		sum(row, 7, v.getDiscountAmount(), amountStyle);
		row.createCell(8).setCellValue(v.getManualDiscountTickets());
		sum(row, 9, v.getManualDiscountAmount(), amountStyle);
		row.createCell(10).setCellValue(v.getExpenseCount());
		sum(row, 11, v.getExpenseAmount(), amountStyle);
		row.createCell(12).setCellValue(v.getWithdrawalCount());
		sum(row, 13, v.getWithdrawalAmount(), amountStyle);
		row.createCell(14).setCellValue(v.getRefusedAuthorisations());
	}

	private static int header(Sheet sheet, int r, String[] headers, CellStyle style) {
		Row row = sheet.createRow(r);
		for (int i = 0; i < headers.length; i++) {
			text(row, i, headers[i], style);
		}
		return r + 1;
	}

	private static void text(Row row, int column, String value, CellStyle style) {
		if (value == null) {
			return;
		}
		Cell cell = row.createCell(column);
		cell.setCellValue(value);
		if (style != null) {
			cell.setCellStyle(style);
		}
	}

	/** A sum of the summary: always written (a total of 0 is a total, -20 and +20). */
	private static void sum(Row row, int column, double value, CellStyle style) {
		Cell cell = row.createCell(column);
		cell.setCellValue(value);
		cell.setCellStyle(style);
	}

	/** An amount cell; empty when absent or 0 (no difference). */
	private static void amount(Row row, int column, Double value, CellStyle style) {
		Cell cell = row.createCell(column);
		if (CashDifferencesService.nonZero(value)) {
			cell.setCellValue(value);
		}
		cell.setCellStyle(style);
	}
}
