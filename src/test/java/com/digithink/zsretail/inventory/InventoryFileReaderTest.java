package com.digithink.zsretail.inventory;

import static com.digithink.zsretail.inventory.InventoryFiles.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.inventory.service.InventoryFileReader;
import com.digithink.zsretail.inventory.service.InventoryFileReader.FileRow;

/**
 * Inventory count: reading the Excel file (column A the code, column B the quantity, first sheet). See
 * docs/modules/inventory-count.md.
 */
class InventoryFileReaderTest {

	@Test
	@DisplayName("An EAN-13 typed as a number reads as its digits (never 6.19E+12 nor 6191234567890.0); a text code is trimmed")
	void numberCellAsCode() {
		List<FileRow> rows = InventoryFileReader.read(InventoryFiles.xlsx(row(6191234567890d, 3d),
				row("  B002  ", 4d), row(12d, 1d)));

		assertEquals(3, rows.size());
		assertEquals("6191234567890", rows.get(0).code);
		assertEquals(Integer.valueOf(3), rows.get(0).quantity);
		assertEquals("B002", rows.get(1).code);
		assertEquals("12", rows.get(2).code);
		assertEquals(1, rows.get(0).rowNumber, "Excel row numbers, 1-based");
		assertTrue(rows.get(0).numericCode, "a number cell: its leading zeros may be lost");
		assertFalse(rows.get(1).numericCode);
	}

	@Test
	@DisplayName("The first row is a header only when its column B is not a number; empty rows are skipped")
	void headerAndEmptyRows() {
		List<FileRow> withHeader = InventoryFileReader.read(InventoryFiles.xlsx(row("Code", "Quantité"),
				row("A001", 5d), null, row(null, null), row("A002", 0d)));
		assertEquals(2, withHeader.size());
		assertEquals("A001", withHeader.get(0).code);
		assertEquals(2, withHeader.get(0).rowNumber);
		assertEquals(5, withHeader.get(1).rowNumber);
		assertEquals(Integer.valueOf(0), withHeader.get(1).quantity, "0 is a valid count");

		List<FileRow> noHeader = InventoryFileReader.read(InventoryFiles.xlsx(row("A001", 5d), row("A002", "7")));
		assertEquals(2, noHeader.size(), "a first row with a number is data");
		assertEquals(Integer.valueOf(7), noHeader.get(1).quantity, "a number typed as text");
	}

	@Test
	@DisplayName("Bad quantities keep their row with the reason: missing, text, negative, decimal; 12.0 and '4,0' are whole")
	void badQuantities() {
		List<FileRow> rows = InventoryFileReader.read(InventoryFiles.xlsx(row("Code", "Qty"), row("A", null),
				row("B", "abc"), row("C", -2d), row("D", 1.5d), row("E", 12.0d), row("F", "4,0"), row("G", "2,5")));

		assertEquals(7, rows.size());
		assertEquals("no quantity", rows.get(0).quantityError);
		assertEquals("not a number: abc", rows.get(1).quantityError);
		assertEquals("negative quantity: -2", rows.get(2).quantityError);
		assertEquals("not a whole number: 1.5", rows.get(3).quantityError);
		assertEquals(Integer.valueOf(12), rows.get(4).quantity);
		assertNull(rows.get(4).quantityError);
		assertEquals(Integer.valueOf(4), rows.get(5).quantity);
		assertEquals("not a whole number: 2.5", rows.get(6).quantityError);
		assertTrue(rows.stream().filter(r -> r.quantityError != null).allMatch(r -> r.quantity == null));
	}

	@Test
	@DisplayName("An .xls file reads the same; a file that is not Excel is refused (400)")
	void xlsAndNotExcel() {
		List<FileRow> rows = InventoryFileReader.read(InventoryFiles.xls(row("Code", "Qty"), row(6191234567890d, 2d)));
		assertEquals("6191234567890", rows.get(0).code);
		assertEquals(Integer.valueOf(2), rows.get(0).quantity);

		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> InventoryFileReader.read(new ByteArrayInputStream("code;qty\nA;1".getBytes())));
		assertEquals("The file is not a readable Excel file (.xlsx or .xls).", refused.getMessage());
	}

	@Test
	@DisplayName("Codes are kept as read: two rows of the same code stay two rows (the service merges them)")
	void rowsAreNotMergedHere() {
		List<FileRow> rows = InventoryFileReader.read(InventoryFiles.xlsx(row("A", 1d), row("A", 2d)));
		assertEquals("A,A", rows.stream().map(r -> r.code).collect(Collectors.joining(",")));
	}
}
