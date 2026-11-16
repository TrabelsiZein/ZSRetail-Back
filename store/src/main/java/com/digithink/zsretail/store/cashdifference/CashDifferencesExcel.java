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
				amount(row, 7, line.getOpeningGap(), amount);
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
