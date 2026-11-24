package com.digithink.zsretail.store.eventlog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;

import com.digithink.zsretail.core.eventlog.EventImportance;

/**
 * 3.4.0, event log step 6: the Excel file of the Journal page, the page's filters: every event (a run of refused logins
 * one row per event), in the page's order, at most {@link EventLogReader#EXCEL_LIMIT} (the title row says when it is
 * cut). French headers as the other exports, amounts to the millime. A streaming workbook (SXSSF: 100 rows in memory)
 * with set widths: autosizing 20,000 rows would be slow.
 */
public final class EventLogExcel {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	static final String[] HEADERS = { "Date et heure", "Importance", "Action", "Texte", "Montant", "Utilisateur",
			"Autorisé par", "Badge de", "Poste de caisse", "Session", "Avant", "Après", "Reçu par le serveur",
			"Heure de la caisse", "N°", "Panier", "Doute", "Adresse" };

	private static final int[] WIDTHS = { 20, 11, 30, 80, 12, 22, 22, 22, 18, 20, 20, 20, 20, 20, 9, 38, 40, 16 };

	/** The French sentences of the doubt codes (TillEventBatch); an unknown code is written as it is. */
	static final Map<String, String> DOUBTS = new LinkedHashMap<>();
	static {
		DOUBTS.put("SESSION_OF_ANOTHER_USER", "La session annoncée par la caisse est celle d'un autre utilisateur.");
		DOUBTS.put("SESSION_UNKNOWN", "La session annoncée par la caisse n'existe pas.");
		DOUBTS.put("USER_UNKNOWN", "L'utilisateur annoncé par la caisse n'existe pas.");
		DOUBTS.put("MACHINE_UNKNOWN", "Le poste de caisse n'a pas été reconnu.");
		DOUBTS.put("FRONT_TIME_INVALID", "L'heure de la caisse était illisible : l'heure d'arrivée au serveur est prise.");
	}

	private EventLogExcel() {
	}

	public static String fileName(LocalDate from, LocalDate to) {
		return "journal-des-evenements-" + from + "-" + to + ".xlsx";
	}

	/** {@code total}: how many events the filters give (more than the rows: the file is cut). */
	public static byte[] of(EventLogFilter filter, List<EventLogRow> rows, long total) throws IOException {
		SXSSFWorkbook workbook = new SXSSFWorkbook(100);
		try {
			Font boldFont = workbook.createFont();
			boldFont.setBold(true);
			CellStyle bold = workbook.createCellStyle();
			bold.setFont(boldFont);
			CellStyle amount = workbook.createCellStyle();
			amount.setDataFormat(workbook.createDataFormat().getFormat("#,##0.000"));
			CellStyle time = workbook.createCellStyle();
			time.setDataFormat(workbook.createDataFormat().getFormat("dd/mm/yyyy hh:mm:ss"));

			Sheet sheet = workbook.createSheet("Journal des événements");
			for (int c = 0; c < WIDTHS.length; c++) {
				sheet.setColumnWidth(c, WIDTHS[c] * 256);
			}
			int r = 0;
			text(sheet.createRow(r++), 0, "Journal des événements du " + filter.getFrom().format(DAY) + " au "
					+ filter.getTo().format(DAY), bold);
			text(sheet.createRow(r++), 0, total > rows.size()
					? rows.size() + " premiers événements sur " + total + " (limite du fichier : " + EventLogReader.EXCEL_LIMIT
							+ ") : resserrez les filtres pour avoir le reste."
					: total + " événement" + (total > 1 ? "s" : "") + ".", null);
			r++;
			Row header = sheet.createRow(r++);
			for (int c = 0; c < HEADERS.length; c++) {
				text(header, c, HEADERS[c], bold);
			}
			for (EventLogRow e : rows) {
				Row row = sheet.createRow(r++);
				date(row, 0, e.getOccurredAt(), time);
				text(row, 1, importanceLabel(e.getImportance()), null);
				text(row, 2, e.getCodeLabel(), null);
				text(row, 3, e.getDetails(), null);
				if (e.getAmount() != null) {
					Cell cell = row.createCell(4);
					cell.setCellValue(e.getAmount());
					cell.setCellStyle(amount);
				}
				text(row, 5, person(e.getUserName(), e.getUserLogin()), null);
				text(row, 6, person(e.getAuthorisedByName(), e.getAuthorisedByLogin()), null);
				text(row, 7, person(e.getBadgeOwnerName(), e.getBadgeOwnerLogin()), null);
				text(row, 8, e.getPosMachineName(), null);
				text(row, 9, e.getSessionNumber(), null);
				text(row, 10, e.getOldValue(), null);
				text(row, 11, e.getNewValue(), null);
				date(row, 12, e.getEventAt(), time);
				date(row, 13, e.getFrontAt(), time);
				if (e.getSequenceNo() != null) {
					row.createCell(14).setCellValue(e.getSequenceNo());
				}
				text(row, 15, e.getCartId(), null);
				text(row, 16, doubtText(e.getDoubt()), null);
				text(row, 17, e.getClientAddress(), null);
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			workbook.write(out);
			return out.toByteArray();
		} finally {
			workbook.dispose();
			workbook.close();
		}
	}

	static String importanceLabel(int importance) {
		if (importance >= EventImportance.ALERT) {
			return "Alerte";
		}
		if (importance >= EventImportance.NOTABLE) {
			return "Notable";
		}
		return "Étape";
	}

	/** The doubt codes as sentences, one after the other. */
	static String doubtText(String doubt) {
		if (doubt == null || doubt.trim().isEmpty()) {
			return null;
		}
		List<String> sentences = new ArrayList<>();
		for (String code : doubt.split(",")) {
			String c = code.trim();
			if (!c.isEmpty()) {
				sentences.add(DOUBTS.getOrDefault(c, c));
			}
		}
		return String.join(" ", sentences);
	}

	/** "Name (login)", the name alone when it is the login, the login alone without a name. */
	static String person(String name, String login) {
		if (name == null || name.trim().isEmpty()) {
			return login;
		}
		return login == null || login.equals(name) ? name : name + " (" + login + ")";
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

	private static void date(Row row, int column, LocalDateTime value, CellStyle style) {
		if (value == null) {
			return;
		}
		Cell cell = row.createCell(column);
		cell.setCellValue(value);
		cell.setCellStyle(style);
	}
}
