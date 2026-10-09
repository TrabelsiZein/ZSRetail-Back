package com.digithink.zsretail.inventory;

import static com.digithink.zsretail.inventory.InventoryFiles.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
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
		assertEquals(BigDecimal.valueOf(3), rows.get(0).quantity);
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
		assertEquals(BigDecimal.valueOf(0), withHeader.get(1).quantity, "0 is a valid count");

		List<FileRow> noHeader = InventoryFileReader.read(InventoryFiles.xlsx(row("A001", 5d), row("A002", "7")));
		assertEquals(2, noHeader.size(), "a first row with a number is data");
		assertEquals(BigDecimal.valueOf(7), noHeader.get(1).quantity, "a number typed as text");
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
		assertEquals(BigDecimal.valueOf(12), rows.get(4).quantity);
		assertNull(rows.get(4).quantityError);
		assertEquals(BigDecimal.valueOf(4), rows.get(5).quantity);
		assertEquals("not a whole number: 2.5", rows.get(6).quantityError);
		assertTrue(rows.stream().filter(r -> r.quantityError != null).allMatch(r -> r.quantity == null));
	}

	@Test
	@DisplayName("An .xls file reads the same; a file that is not Excel is refused (400)")
	void xlsAndNotExcel() {
		List<FileRow> rows = InventoryFileReader.read(InventoryFiles.xls(row("Code", "Qty"), row(6191234567890d, 2d)));
		assertEquals("6191234567890", rows.get(0).code);
		assertEquals(BigDecimal.valueOf(2), rows.get(0).quantity);

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

	// --- 2.2.1: decimal quantities ---

	/** The shapes of a real count file: EAN number cells, decimals as numbers and as text, noise, a 4th decimal. */
	private static List<FileRow> decimalFile(boolean allowDecimal) {
		return InventoryFileReader.read(InventoryFiles.xlsx(row("Code à barre", "Quantité"),
				row(6192464103466d, 1.34d), row(6192464103473d, Math.nextUp(1.17d)), row("6192464104258", "0,08"),
				row("A", "1.34"), row("B", 0.1d + 0.2d), row(6192464102513d, 1.3405d), row("C", "2,5000"),
				row("D", 3d)), allowDecimal);
	}

	@Test
	@DisplayName("2.2.1, decimals allowed: 1.34 as a number or text (\"1,34\", \"1.34\"), Excel noise cleaned to 3 decimals, a 4th decimal refused with its code")
	void decimalsAllowed() {
		List<FileRow> rows = decimalFile(true);

		assertEquals(8, rows.size());
		assertEquals("1.34", rows.get(0).quantity.toPlainString());
		assertEquals("1.17", rows.get(1).quantity.toPlainString(), "1.1700000000000002 is Excel noise");
		assertEquals("0.08", rows.get(2).quantity.toPlainString(), "a comma as decimal mark");
		assertEquals("1.34", rows.get(3).quantity.toPlainString());
		assertEquals("0.3", rows.get(4).quantity.toPlainString(), "0.30000000000000004 is float noise");
		assertNull(rows.get(5).quantity);
		assertEquals("more than 3 decimals: 1.3405 (code 6192464102513)", rows.get(5).quantityError);
		assertEquals(7, rows.get(5).rowNumber);
		assertEquals("2.5", rows.get(6).quantity.toPlainString(), "trailing zeros are not decimals");
		assertEquals("3", rows.get(7).quantity.toPlainString());
	}

	@Test
	@DisplayName("2.2.1, decimals not allowed (the default): every decimal refused as in 2.2.0, whole quantities unchanged")
	void decimalsNotAllowed() {
		List<FileRow> rows = decimalFile(false);

		assertEquals("not a whole number: 1.34", rows.get(0).quantityError);
		assertEquals("not a whole number: 1.17", rows.get(1).quantityError);
		assertEquals("not a whole number: 0.08", rows.get(2).quantityError);
		assertEquals("not a whole number: 1.34", rows.get(3).quantityError);
		assertEquals("not a whole number: 0.3", rows.get(4).quantityError);
		assertEquals("not a whole number: 1.3405", rows.get(5).quantityError, "the 2.2.0 message first");
		assertEquals("not a whole number: 2.5", rows.get(6).quantityError);
		assertEquals(BigDecimal.valueOf(3), rows.get(7).quantity);
		assertTrue(rows.subList(0, 7).stream().allMatch(r -> r.quantity == null));
	}
}
