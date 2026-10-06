package com.digithink.zsretail.inventory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Test support: an inventory count file in memory. Each row is {column A, column B}; a Number becomes a numeric cell, a
 * String a text cell, null no cell; a null row leaves an empty row.
 */
public final class InventoryFiles {

	private InventoryFiles() {
	}

	/** An .xlsx file. */
	public static InputStream xlsx(Object[]... rows) {
		return write(new XSSFWorkbook(), rows);
	}

	/** An .xls file. */
	public static InputStream xls(Object[]... rows) {
		return write(new HSSFWorkbook(), rows);
	}

	public static Object[] row(Object a, Object b) {
		return new Object[] { a, b };
	}

	private static InputStream write(Workbook workbook, Object[][] rows) {
		try (Workbook book = workbook; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = book.createSheet("Count");
			for (int r = 0; r < rows.length; r++) {
				if (rows[r] == null) {
					continue;
				}
				Row row = sheet.createRow(r);
				for (int c = 0; c < rows[r].length; c++) {
					Object value = rows[r][c];
					if (value instanceof Number) {
						row.createCell(c).setCellValue(((Number) value).doubleValue());
					} else if (value != null) {
						row.createCell(c).setCellValue(value.toString());
					}
				}
			}
			book.write(out);
			return new ByteArrayInputStream(out.toByteArray());
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
