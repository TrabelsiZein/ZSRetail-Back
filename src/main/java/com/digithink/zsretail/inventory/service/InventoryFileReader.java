package com.digithink.zsretail.inventory.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import com.digithink.zsretail.utils.Quantities;

/**
 * Reads the first sheet of an inventory count file (.xlsx or .xls): column A the barcode or item code, column B the
 * counted quantity. Empty rows are skipped; the first row with content is skipped when its column B is not a number
 * (a header). 2.2.1: a quantity may carry up to 3 decimals when the store allows decimal quantities
 * (ALLOW_DECIMAL_QUANTITY); a number cell is read to 15 significant digits first (Excel float noise: 1.3400000000000001
 * is 1.34). A numeric code is read as a plain number text (an EAN-13 typed as a number gives 6191234567890, never
 * 6.19E+12 or 6191234567890.0). A row keeps its quantity error instead of a quantity; the service decides the line
 * status. See docs/modules/inventory-count.md.
 */
public final class InventoryFileReader {

	/** Above this, the file is refused (30,000 rows expected at most). */
	public static final int MAX_ROWS = 100_000;

	/**
	 * One row of the file: its number in Excel (1-based), the code, whether it was a number cell (Excel dropped its
	 * leading zeros), and the quantity or why it is not valid.
	 */
	public static final class FileRow {
		public final int rowNumber;
		public final String code;
		public final boolean numericCode;
		/** 2.2.1: up to 3 decimals, without trailing zeros; null when the row has a quantity error. */
		public final BigDecimal quantity;
		public final String quantityError;

		FileRow(int rowNumber, String code, boolean numericCode, BigDecimal quantity, String quantityError) {
			this.rowNumber = rowNumber;
			this.code = code;
			this.numericCode = numericCode;
			this.quantity = quantity;
			this.quantityError = quantityError;
		}
	}

	/** Excel keeps and shows 15 significant digits; what a double holds beyond them is float noise. */
	private static final MathContext EXCEL_DIGITS = new MathContext(15, RoundingMode.HALF_UP);

	private InventoryFileReader() {
	}

	/** Whole quantities only, as in 2.2.0: {@link #read(InputStream, boolean)} with decimals not allowed. */
	public static List<FileRow> read(InputStream in) {
		return read(in, false);
	}

	/**
	 * The rows with a code or a quantity. IllegalArgumentException when the file is not a readable workbook.
	 *
	 * @param allowDecimal 2.2.1: true when the store allows decimal quantities (up to 3 decimals); false: a decimal is
	 *            refused as in 2.2.0 ("not a whole number")
	 */
	public static List<FileRow> read(InputStream in, boolean allowDecimal) {
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
			return read(workbook.getSheetAt(0), allowDecimal);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	static List<FileRow> read(Sheet sheet, boolean allowDecimal) {
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
			rows.add(row(r + 1, code, isNumber(row.getCell(0)), quantityText, number, allowDecimal));
		}
		return rows;
	}

	private static FileRow row(int rowNumber, String code, boolean numericCode, String quantityText,
			BigDecimal number, boolean allowDecimal) {
		String error = null;
		if (quantityText.isEmpty()) {
			error = "no quantity";
		} else if (number == null) {
			error = "not a number: " + quantityText;
		} else if (number.signum() < 0) {
			error = "negative quantity: " + plain(number);
		} else if (number.stripTrailingZeros().scale() > 0 && !allowDecimal) {
			error = "not a whole number: " + plain(number);
		} else if (Quantities.decimals(number) > Quantities.SCALE) {
			error = "more than " + Quantities.SCALE + " decimals: " + plain(number) + " (code " + code + ")";
		} else if (number.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) {
			error = "quantity too large: " + plain(number);
		}
		return new FileRow(rowNumber, code, numericCode, error == null ? Quantities.normalize(number) : null, error);
	}

	/** True for a number cell (also a formula giving a number). */
	private static boolean isNumber(Cell cell) {
		if (cell == null) {
			return false;
		}
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		return type == CellType.NUMERIC;
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

	/**
	 * Column B as a number: a numeric cell (read to 15 significant digits, as Excel shows it: its float noise
	 * 1.3400000000000001 is 1.34, a real 1.3405 stays), or a text that reads as one (a comma accepted as decimal mark: "1,34"
	 * is 1.34); else null.
	 */
	static BigDecimal number(Cell cell) {
		if (cell == null) {
			return null;
		}
		CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
		if (type == CellType.NUMERIC) {
			return BigDecimal.valueOf(cell.getNumericCellValue()).round(EXCEL_DIGITS);
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
