package com.digithink.zsretail.core.convention;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.convention.service.ConventionCardExcel;
import com.digithink.zsretail.core.convention.service.ConventionCardExcel.ExportRow;
import com.digithink.zsretail.core.convention.service.ConventionCardExcel.Line;

/**
 * 3.6.0, conventions part B5: ConventionCardExcel, the cards' .xlsx files read and written (no database): the header in
 * French or English, the whole-file refusals, the cells (numbers, texts, formulas, Arabic and accents), the export's
 * columns, the template.
 */
class ConventionCardExcelTest {

	/** An .xlsx whose first sheet holds these rows: a String a text cell, a Number a number cell, a "=" text a formula. */
	static byte[] xlsx(Object[]... rows) {
		try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			fill(workbook, rows);
			workbook.getCreationHelper().createFormulaEvaluator().evaluateAll();
			workbook.write(out);
			return out.toByteArray();
		} catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void fill(Workbook workbook, Object[]... rows) {
		Sheet sheet = workbook.createSheet("Cartes");
		for (int r = 0; r < rows.length; r++) {
			if (rows[r] == null) {
				continue; // an empty row
			}
			Row row = sheet.createRow(r);
			for (int c = 0; c < rows[r].length; c++) {
				Object value = rows[r][c];
				if (value == null) {
					continue;
				}
				Cell cell = row.createCell(c);
				if (value instanceof Number) {
					cell.setCellValue(((Number) value).doubleValue());
				} else if (value.toString().startsWith("=")) {
					cell.setCellFormula(value.toString().substring(1));
				} else {
					cell.setCellValue(value.toString());
				}
			}
		}
	}

	private static Object[] row(Object... cells) {
		return cells;
	}

	private static ConventionException refused(String code, byte[] file) {
		ConventionException e = assertThrows(ConventionException.class, () -> ConventionCardExcel.read(file));
		assertEquals(code, e.getCode(), e.getMessage());
		return e;
	}

	private static List<String> numbers(List<Line> lines) {
		return lines.stream().map(l -> l.number).collect(Collectors.toList());
	}

	@Test
	@DisplayName("The header in French or English, any case, accents or not, in any order, other columns ignored; the"
			+ " lines with their Excel row, the empty rows skipped")
	void header() {
		List<Line> fr = ConventionCardExcel.read(xlsx(row("Numéro de carte", "Titulaire", "Téléphone", "Matricule"),
				row("MIN-1", "Ali", "98111222", "M-1"), null, row("MIN-2", null, null, null), row(" ", " ", null, null),
				row("MIN-3", "Sana")));
		assertEquals(Arrays.asList("MIN-1", "MIN-2", "MIN-3"), numbers(fr));
		assertEquals(Arrays.asList(2, 4, 6), fr.stream().map(l -> l.row).collect(Collectors.toList()), "Excel's rows");
		assertEquals("Ali", fr.get(0).holderName);
		assertEquals("98111222", fr.get(0).holderPhone);
		assertEquals("M-1", fr.get(0).holderEmployeeNo);
		assertEquals("", fr.get(1).holderName, "no holder: still a line");

		List<Line> en = ConventionCardExcel.read(xlsx(row("Employee number", "Remarks", "CARD NUMBER", "holder name"),
				row("E-9", "vip", "min-9", "Hélène")));
		assertEquals("min-9", en.get(0).number, "read as typed (the service normalises)");
		assertEquals("Hélène", en.get(0).holderName);
		assertEquals("E-9", en.get(0).holderEmployeeNo);
		assertEquals("", en.get(0).holderPhone, "no phone column");

		List<Line> variants = ConventionCardExcel.read(xlsx(row("N° de carte", "NOM DU TITULAIRE", "Tél", "matricule"),
				row("X-1", "Ali", "1", "2")));
		assertEquals("Ali", variants.get(0).holderName);
		assertEquals("1", variants.get(0).holderPhone);
		List<Line> exported = ConventionCardExcel.read(ConventionCardExcel.export("MIN", "Santé", "Ministère",
				Arrays.asList(new ExportRow("MIN-00001", "Ali", "M-1"))));
		assertEquals("MIN-00001", exported.get(0).number, "an export reads back: its extra columns ignored");
		assertEquals("Ali", exported.get(0).holderName);
		assertEquals("M-1", exported.get(0).holderEmployeeNo);
	}

	@Test
	@DisplayName("Whole-file refusals: empty, not an .xlsx (a .csv, an old .xls, a renamed text), no header with a card"
			+ " number column, the header only, over 2 MB, over 5,000 lines")
	void wrongFiles() throws IOException {
		refused("CONVENTION_IMPORT_EMPTY", new byte[0]);
		refused("CONVENTION_IMPORT_EMPTY", null);
		refused("CONVENTION_IMPORT_NOT_XLSX", "Numéro de carte;Titulaire\nMIN-1;Ali\n".getBytes(StandardCharsets.UTF_8));
		try (HSSFWorkbook xls = new HSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			fill(xls, row("Numéro de carte"), row("MIN-1"));
			xls.write(out);
			refused("CONVENTION_IMPORT_NOT_XLSX", out.toByteArray());
		}
		ConventionException noHeader = refused("CONVENTION_IMPORT_NO_HEADER", xlsx(row("MIN-1", "Ali"), row("MIN-2")));
		assertEquals("Numéro de carte, Titulaire, Téléphone, Matricule", noHeader.getParams().get("expected"));
		refused("CONVENTION_IMPORT_NO_HEADER", xlsx(row("Titulaire", "Matricule"), row("Ali", "M-1")));
		refused("CONVENTION_IMPORT_EMPTY", xlsx(row("Numéro de carte", "Titulaire")));
		refused("CONVENTION_IMPORT_EMPTY", xlsx());
		refused("CONVENTION_IMPORT_EMPTY", ConventionCardExcel.template(false));
		assertEquals(2048, refused("CONVENTION_IMPORT_TOO_BIG", new byte[ConventionCardExcel.MAX_BYTES + 1]).getParams()
				.get("maxKb"));
		Object[][] rows = new Object[ConventionCardExcel.MAX_ROWS + 2][];
		rows[0] = row("Card number");
		for (int i = 1; i < rows.length; i++) {
			rows[i] = row("C-" + i);
		}
		assertEquals(5000, refused("CONVENTION_IMPORT_TOO_MANY_ROWS", xlsx(rows)).getParams().get("max"));
		Object[][] max = Arrays.copyOf(rows, ConventionCardExcel.MAX_ROWS + 1);
		assertEquals(5000, ConventionCardExcel.read(xlsx(max)).size(), "5,000 lines: fine");
	}

	@Test
	@DisplayName("Cells: a number cell as its plain digits (12345, 1.2345E4, an EAN-13), beyond 15 digits marked (Excel"
			+ " changed it), a formula's value, a text kept as typed (Arabic, accents, a text 1.2345E4)")
	void cells() {
		List<Line> lines = ConventionCardExcel.read(xlsx(row("Numéro de carte", "Titulaire", "Téléphone", "Matricule"),
				row(12345, "محمد بن علي", 98111222, 4521), row(1.2345E4, "Hélène Gaëlle", null, null),
				row(6191234567890L, null, null, null), row(1234567890123456L, null, null, null),
				row("=\"F-\"&(40+2)", null, null, null), row("1.2345E4", null, null, null), row(" 00123 ", null, null,
						null), row(12.5, null, null, null)));
		assertEquals(Arrays.asList("12345", "12345", "6191234567890", "1234567890123456", "F-42", "1.2345E4", "00123",
				"12.5"), numbers(lines));
		assertEquals("محمد بن علي", lines.get(0).holderName, "Arabic kept");
		assertEquals("98111222", lines.get(0).holderPhone, "a phone typed as a number");
		assertEquals("4521", lines.get(0).holderEmployeeNo);
		assertEquals("Hélène Gaëlle", lines.get(1).holderName, "accents kept");
		List<Boolean> changed = lines.stream().map(l -> l.numberTooLongForExcel).collect(Collectors.toList());
		assertEquals(Arrays.asList(false, false, false, true, false, false, false, false), changed);
	}

	@Test
	@DisplayName("The export: one sheet, its header (number, code, name, organisation, holder, employee number), one line"
			+ " per card in order, every cell text, never a phone; the template's header in French or English, its columns"
			+ " as text")
	void exportAndTemplate() throws IOException {
		byte[] file = ConventionCardExcel.export("MIN", "Personnel de santé", "وزارة الصحة", Arrays.asList(
				new ExportRow("MIN-00001", "Ali", "M-1"), new ExportRow("MIN-00002", null, null)));
		try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(file))) {
			Sheet sheet = book.getSheetAt(0);
			assertEquals(2, sheet.getLastRowNum());
			assertArrayEquals(new String[] { "Numéro de carte", "Code convention", "Convention", "Organisation",
					"Titulaire", "Matricule" }, texts(sheet.getRow(0), 6));
			assertArrayEquals(new String[] { "MIN-00001", "MIN", "Personnel de santé", "وزارة الصحة", "Ali", "M-1" },
					texts(sheet.getRow(1), 6));
			assertArrayEquals(new String[] { "MIN-00002", "MIN", "Personnel de santé", "وزارة الصحة", "", "" },
					texts(sheet.getRow(2), 6));
			assertEquals("@", sheet.getRow(1).getCell(0).getCellStyle().getDataFormatString());
			assertEquals(6, sheet.getRow(0).getLastCellNum(), "six columns: no phone");
		}
		for (boolean english : new boolean[] { false, true }) {
			try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(ConventionCardExcel.template(english)))) {
				Sheet sheet = book.getSheetAt(0);
				assertArrayEquals((english ? ConventionCardExcel.TEMPLATE_EN : ConventionCardExcel.TEMPLATE_FR)
						.toArray(new String[0]), texts(sheet.getRow(0), 4));
				assertEquals("@", sheet.getColumnStyle(0).getDataFormatString(), "a number keeps its leading zeros");
				assertEquals(0, sheet.getLastRowNum());
			}
		}
		assertTrue(ConventionCardExcel.exportName("MIN-SANTE", java.time.LocalDateTime.of(2026, 11, 28, 9, 30))
				.equals("cartes-MIN-SANTE-20261128-0930.xlsx"));
		assertFalse(String.join(",", ConventionCardExcel.EXPORT_HEADER).toLowerCase().contains("phone"));
	}

	private static String[] texts(Row row, int n) {
		List<String> out = new ArrayList<>();
		for (int c = 0; c < n; c++) {
			Cell cell = row.getCell(c);
			out.add(cell == null ? "" : cell.getStringCellValue());
		}
		return out.toArray(new String[0]);
	}
}
