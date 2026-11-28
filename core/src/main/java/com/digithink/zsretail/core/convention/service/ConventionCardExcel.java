package com.digithink.zsretail.core.convention.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import com.digithink.zsretail.core.convention.ConventionException;

/**
 * 3.6.0, conventions part B5: the cards' Excel files (POI, .xlsx only), no Spring, no database.
 * <ul>
 * <li>{@link #read}: an import file. Its first sheet; the first row with content is the header, its columns found by
 * their names in French or English, any case, accents or not, in any order (the card number required, the holder's
 * name, phone and employee number optional, other columns ignored). Every row after it with content in those columns
 * is a line, with its Excel row number. A number typed in a number cell (12345, 1.2345E4) is read as the plain number
 * "12345"; above 15 digits Excel has already changed it (it keeps 15): the line says so.</li>
 * <li>{@link #export}: the file for the card printer (decision 8), its columns in French; never the phone.</li>
 * <li>{@link #template}: the empty import file, its header in French or English, its columns formatted as text (a
 * number keeps its leading zeros).</li>
 * </ul>
 * The whole-file refusals: CONVENTION_IMPORT_EMPTY, CONVENTION_IMPORT_TOO_BIG (maxKb), CONVENTION_IMPORT_NOT_XLSX,
 * CONVENTION_IMPORT_NO_HEADER (expected), CONVENTION_IMPORT_TOO_MANY_ROWS (max).
 */
public final class ConventionCardExcel {

	public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
	/** Lines per import file (as a generation's 5,000). */
	public static final int MAX_ROWS = 5000;
	/** An import file's size: 5,000 lines of four columns are about 200 KB. */
	public static final int MAX_BYTES = 2 * 1024 * 1024;
	/** Excel keeps 15 significant digits of a number. */
	static final int EXCEL_DIGITS = 15;

	public static final List<String> TEMPLATE_FR = Collections.unmodifiableList(Arrays.asList("Numéro de carte",
			"Titulaire", "Téléphone", "Matricule"));
	public static final List<String> TEMPLATE_EN = Collections.unmodifiableList(Arrays.asList("Card number",
			"Holder name", "Phone", "Employee number"));
	public static final List<String> EXPORT_HEADER = Collections.unmodifiableList(Arrays.asList("Numéro de carte",
			"Code convention", "Convention", "Organisation", "Titulaire", "Matricule"));

	/** The header names recognised, normalised ({@link #normalised}). */
	private static final Set<String> NUMBER = names("numero de carte", "numero carte", "n de carte", "no de carte",
			"n carte", "numero", "carte", "card number", "card no", "card", "number");
	private static final Set<String> NAME = names("titulaire", "nom du titulaire", "nom titulaire", "nom",
			"nom et prenom", "holder", "holder name", "name", "full name");
	private static final Set<String> PHONE = names("telephone", "tel", "mobile", "gsm", "phone", "phone number");
	private static final Set<String> EMPLOYEE_NO = names("matricule", "numero de matricule", "employee number",
			"employee no", "employee id", "staff number");

	private ConventionCardExcel() {
	}

	/** One line of an import file: its Excel row (1-based), the texts read (trimmed; "" when empty). */
	public static final class Line {
		public final int row;
		public final String number;
		/** The number cell held more than 15 digits: Excel already changed its last ones. */
		public final boolean numberTooLongForExcel;
		public final String holderName;
		public final String holderPhone;
		public final String holderEmployeeNo;

		Line(int row, String number, boolean numberTooLongForExcel, String holderName, String holderPhone,
				String holderEmployeeNo) {
			this.row = row;
			this.number = number;
			this.numberTooLongForExcel = numberTooLongForExcel;
			this.holderName = holderName;
			this.holderPhone = holderPhone;
			this.holderEmployeeNo = holderEmployeeNo;
		}
	}

	// ─── Reading an import file ──────────────────────────────────────

	public static List<Line> read(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			throw ConventionException.refused("CONVENTION_IMPORT_EMPTY", "The file is empty.");
		}
		if (bytes.length > MAX_BYTES) {
			throw ConventionException.refused("CONVENTION_IMPORT_TOO_BIG", "The file is over " + MAX_BYTES / 1024
					+ " KB.", "maxKb", MAX_BYTES / 1024);
		}
		Workbook workbook;
		try {
			workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
		} catch (IOException | RuntimeException notXlsx) {
			throw ConventionException.refused("CONVENTION_IMPORT_NOT_XLSX", "The file is not an Excel .xlsx file (an old"
					+ " .xls or a .csv: save it as .xlsx, or start from the template).");
		}
		try (Workbook opened = workbook) {
			if (opened.getNumberOfSheets() == 0) {
				throw ConventionException.refused("CONVENTION_IMPORT_EMPTY", "The file has no sheet.");
			}
			return read(opened.getSheetAt(0));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static List<Line> read(Sheet sheet) {
		int[] columns = null; // number, name, phone, employee number (-1: absent)
		List<Line> lines = new ArrayList<>();
		for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
			Row row = sheet.getRow(r);
			if (row == null || empty(row)) {
				continue;
			}
			if (columns == null) {
				columns = header(row);
				continue;
			}
			String number = text(row, columns[0]);
			String name = text(row, columns[1]);
			String phone = text(row, columns[2]);
			String employeeNo = text(row, columns[3]);
			if (number.isEmpty() && name.isEmpty() && phone.isEmpty() && employeeNo.isEmpty()) {
				continue;
			}
			if (lines.size() >= MAX_ROWS) {
				throw ConventionException.refused("CONVENTION_IMPORT_TOO_MANY_ROWS", "The file has more than " + MAX_ROWS
						+ " lines: split it.", "max", MAX_ROWS);
			}
			lines.add(new Line(r + 1, number, tooLongForExcel(row.getCell(columns[0])), name, phone, employeeNo));
		}
		if (lines.isEmpty()) { // nothing at all, or the header only
			throw ConventionException.refused("CONVENTION_IMPORT_EMPTY", "The file has no card line"
					+ (columns == null ? "." : " under its header."));
		}
		return lines;
	}

	/** The columns of the header row; refused when it has no card number column. */
	private static int[] header(Row row) {
		int[] columns = { -1, -1, -1, -1 };
		for (Cell cell : row) {
			String name = normalised(cellText(cell));
			int index = cell.getColumnIndex();
			int which = NUMBER.contains(name) ? 0 : NAME.contains(name) ? 1 : PHONE.contains(name) ? 2
					: EMPLOYEE_NO.contains(name) ? 3 : -1;
			if (which >= 0 && columns[which] < 0) {
				columns[which] = index;
			}
		}
		if (columns[0] < 0) {
			throw ConventionException.refused("CONVENTION_IMPORT_NO_HEADER", "The first line of the file is not a header"
					+ " with a column \"Numéro de carte\" (or \"Card number\"): start from the template.", "expected",
					String.join(", ", TEMPLATE_FR));
		}
		return columns;
	}

	private static boolean empty(Row row) {
		for (Cell cell : row) {
			if (!cellText(cell).isEmpty()) {
				return false;
			}
		}
		return true;
	}

	private static String text(Row row, int column) {
		return column < 0 ? "" : cellText(row.getCell(column));
	}

	/** A text trimmed (a non-breaking space as a space); a number as its plain digits; a formula's value. */
	static String cellText(Cell cell) {
		if (cell == null) {
			return "";
		}
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		switch (type) {
		case STRING:
			return clean(cell.getStringCellValue());
		case NUMERIC:
			return BigDecimal.valueOf(cell.getNumericCellValue()).stripTrailingZeros().toPlainString();
		case BOOLEAN:
			return String.valueOf(cell.getBooleanCellValue()).toUpperCase(Locale.ROOT);
		case ERROR:
			return clean(new DataFormatter().formatCellValue(cell));
		default:
			return "";
		}
	}

	/** A number cell beyond Excel's 15 significant digits. */
	private static boolean tooLongForExcel(Cell cell) {
		if (cell == null) {
			return false;
		}
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		return type == CellType.NUMERIC && Math.abs(cell.getNumericCellValue()) >= Math.pow(10, EXCEL_DIGITS);
	}

	private static String clean(String text) {
		return text == null ? "" : text.replace(' ', ' ').trim();
	}

	/** Lower case, without accents, other characters as one space: "N° de Carte" is "n de carte". */
	static String normalised(String header) {
		String plain = Normalizer.normalize(header == null ? "" : header, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
	}

	private static Set<String> names(String... names) {
		return Collections.unmodifiableSet(new HashSet<>(Arrays.asList(names)));
	}

	// ─── The export and the template ─────────────────────────────────

	/** A card of the export: the decision 8 columns, never the phone. */
	public static final class ExportRow {
		public final String cardNumber;
		public final String holderName;
		public final String holderEmployeeNo;

		public ExportRow(String cardNumber, String holderName, String holderEmployeeNo) {
			this.cardNumber = cardNumber;
			this.holderName = holderName;
			this.holderEmployeeNo = holderEmployeeNo;
		}
	}

	/** Number, convention code, name, organisation, holder name, employee number; one line per card, in this order. */
	public static byte[] export(String code, String name, String organisation, List<ExportRow> rows) {
		SXSSFWorkbook workbook = new SXSSFWorkbook(200);
		try {
			Sheet sheet = workbook.createSheet("Cartes");
			CellStyle text = textStyle(workbook);
			headerRow(workbook, sheet, EXPORT_HEADER);
			int r = 1;
			for (ExportRow row : rows) {
				Row line = sheet.createRow(r++);
				String[] values = { row.cardNumber, code, name, organisation, row.holderName, row.holderEmployeeNo };
				for (int c = 0; c < values.length; c++) {
					Cell cell = line.createCell(c);
					cell.setCellStyle(text);
					if (values[c] != null) {
						cell.setCellValue(values[c]);
					}
				}
			}
			int[] widths = { 22, 18, 30, 30, 30, 18 };
			for (int c = 0; c < widths.length; c++) {
				sheet.setColumnWidth(c, widths[c] * 256);
			}
			return bytes(workbook);
		} finally {
			workbook.dispose();
		}
	}

	/** The empty import file: its header (French, or English), its four columns formatted as text. */
	public static byte[] template(boolean english) {
		try (XSSFWorkbook workbook = new XSSFWorkbook()) {
			Sheet sheet = workbook.createSheet(english ? "Cards" : "Cartes");
			CellStyle text = textStyle(workbook);
			List<String> header = english ? TEMPLATE_EN : TEMPLATE_FR;
			headerRow(workbook, sheet, header);
			int[] widths = { 22, 30, 18, 18 };
			for (int c = 0; c < header.size(); c++) {
				sheet.setColumnWidth(c, widths[c] * 256);
				sheet.setDefaultColumnStyle(c, text); // a number typed keeps its leading zeros
			}
			sheet.createFreezePane(0, 1);
			return bytes(workbook);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** "cartes-MIN-SANTE-20261128-0930.xlsx". */
	public static String exportName(String code, java.time.LocalDateTime at) {
		return "cartes-" + code + "-" + at.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
				+ ".xlsx";
	}

	private static void headerRow(Workbook workbook, Sheet sheet, List<String> names) {
		CellStyle bold = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		bold.setFont(font);
		Row header = sheet.createRow(0);
		for (int c = 0; c < names.size(); c++) {
			Cell cell = header.createCell(c);
			cell.setCellValue(names.get(c));
			cell.setCellStyle(bold);
		}
	}

	private static CellStyle textStyle(Workbook workbook) {
		CellStyle text = workbook.createCellStyle();
		text.setDataFormat(workbook.createDataFormat().getFormat("@"));
		return text;
	}

	private static byte[] bytes(Workbook workbook) {
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			workbook.write(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
