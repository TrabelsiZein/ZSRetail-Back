package com.digithink.zsretail.inventory.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

/**
 * Reads the first sheet of an inventory count file (.xlsx or .xls): column A the barcode or item code, column B the
 * counted quantity. Empty rows are skipped; the first row with content is skipped when its column B is not a number
 * (a header). A numeric code is read as a plain number text (an EAN-13 typed as a number gives 6191234567890, never
 * 6.19E+12 or 6191234567890.0). A row keeps its quantity error instead of a quantity; the service decides the line
 * status. See docs/modules/inventory-count.md.
 */
public final class InventoryFileReader {

	/** Above this, the file is refused (30,000 rows expected at most). */
	public static final int MAX_ROWS = 100_000;

	/** One row of the file: its number in Excel (1-based), the code, and the quantity or why it is not valid. */
	public static final class FileRow {
		public final int rowNumber;
		public final String code;
		public final Integer quantity;
		public final String quantityError;

		FileRow(int rowNumber, String code, Integer quantity, String quantityError) {
			this.rowNumber = rowNumber;
			this.code = code;
			this.quantity = quantity;
			this.quantityError = quantityError;
		}
	}

	private InventoryFileReader() {
	}

	/** The rows with a code or a quantity. IllegalArgumentException when the file is not a readable workbook. */
	public static List<FileRow> read(InputStream in) {
		Workbook opened;
		try {
			opened = WorkbookFactory.create(in);
		} catch (IOException | RuntimeException e) {
			throw new IllegalArgumentException("The file is not a readable Excel file (.xlsx or .xls).", e);
		}
		try (Workbook workbook = opened) {
			if (workbook.getNumberOfSheets() == 0) {
				throw new IllegalArgumentException("The file has no sheet.");
			}
			return read(workbook.getSheetAt(0));
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static List<FileRow> read(Sheet sheet) {
		List<FileRow> rows = new ArrayList<>();
		boolean first = true;
		for (int r = sheet.getFirstRowNum(); r <= sheet.getLastRowNum(); r++) {
			Row row = sheet.getRow(r);
			if (row == null) {
				continue;
			}
			String code = codeText(row.getCell(0));
			Cell quantityCell = row.getCell(1);
			String quantityText = rawText(quantityCell);
			if (code.isEmpty() && quantityText.isEmpty()) {
				continue;
			}
			BigDecimal number = number(quantityCell);
			if (first) {
				first = false;
				if (number == null) {
					continue; // a header: its column B is not a number
				}
			}
			if (rows.size() >= MAX_ROWS) {
				throw new IllegalArgumentException("The file has more than " + MAX_ROWS + " rows.");
			}
			rows.add(row(r + 1, code, quantityText, number));
		}
		return rows;
	}

	private static FileRow row(int rowNumber, String code, String quantityText, BigDecimal number) {
		if (quantityText.isEmpty()) {
			return new FileRow(rowNumber, code, null, "no quantity");
		}
		if (number == null) {
			return new FileRow(rowNumber, code, null, "not a number: " + quantityText);
		}
		if (number.signum() < 0) {
			return new FileRow(rowNumber, code, null, "negative quantity: " + plain(number));
		}
		if (number.stripTrailingZeros().scale() > 0) {
			return new FileRow(rowNumber, code, null, "not a whole number: " + plain(number));
		}
		if (number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
			return new FileRow(rowNumber, code, null, "quantity too large: " + plain(number));
		}
		return new FileRow(rowNumber, code, number.intValueExact(), null);
	}

	/** Column A: a text trimmed, a number as a plain number text. */
	static String codeText(Cell cell) {
		if (cell == null) {
			return "";
		}
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		switch (type) {
			case NUMERIC:
				return plain(BigDecimal.valueOf(cell.getNumericCellValue()));
			case STRING:
				return clean(cell.getStringCellValue());
			case BOOLEAN:
				return String.valueOf(cell.getBooleanCellValue());
			default:
				return "";
		}
	}

	/** Column B as a number: a numeric cell, or a text that reads as one (a comma accepted as decimal mark); else null. */
	static BigDecimal number(Cell cell) {
		if (cell == null) {
			return null;
		}
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		if (type == CellType.NUMERIC) {
			return BigDecimal.valueOf(cell.getNumericCellValue());
		}
		if (type == CellType.STRING) {
			String text = clean(cell.getStringCellValue()).replace(',', '.');
			if (text.isEmpty()) {
				return null;
			}
			try {
				return new BigDecimal(text);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return null;
	}

	/** Column B as shown in the messages; empty when blank. */
	private static String rawText(Cell cell) {
		if (cell == null) {
			return "";
		}
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		switch (type) {
			case NUMERIC:
				return plain(BigDecimal.valueOf(cell.getNumericCellValue()));
			case STRING:
				return clean(cell.getStringCellValue());
			case BOOLEAN:
				return String.valueOf(cell.getBooleanCellValue());
			case ERROR:
				return "#ERROR";
			default:
				return "";
		}
	}

	private static String plain(BigDecimal number) {
		return number.stripTrailingZeros().toPlainString();
	}

	/** Trimmed, a non-breaking space counted as a space. */
	private static String clean(String text) {
		return text == null ? "" : text.replace(' ', ' ').trim();
	}
}
