package com.digithink.zsretail.inventory;

import static com.digithink.zsretail.inventory.InventoryFiles.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockMultipartFile;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.inventory.controller.InventoryCountAPI;
import com.digithink.zsretail.inventory.enumeration.InventoryCountStatus;
import com.digithink.zsretail.inventory.enumeration.InventoryLineStatus;
import com.digithink.zsretail.inventory.model.InventoryCountLine;
import com.digithink.zsretail.inventory.service.InventoryCountService;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.StockMovement;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.support.InMemoryCatalogue;
import com.digithink.zsretail.support.InMemoryStock;
import com.digithink.zsretail.support.TestModes;

/**
 * Inventory count: the import (lookup by barcode then item code, rows merged, statuses), the validation (the stock
 * becomes the counted quantity, one movement per difference, a second validation refused) and the 403 on a head office
 * or with the supply from the ERP. The real service over an in-memory store ({@link InMemoryInventory}). See
 * docs/modules/inventory-count.md.
 */
class InventoryCountServiceTest {

	private InMemoryInventory inventory;
	private InventoryCountService service;

	@BeforeEach
	void store() {
		InMemoryCatalogue catalogue = new InMemoryCatalogue(1000);
		item(catalogue, "A001", 10, null);
		catalogue.barcode("6191234567890", catalogue.itemByCode("A001").get());
		item(catalogue, "B002", 2, null);
		item(catalogue, "C003", 5, null);
		item(catalogue, "S004", null, ItemType.SERVICE);
		item(catalogue, "D005", 8, null);
		item(catalogue, "E006", 4, ItemType.PRODUCT);
		item(catalogue, "F007", null, ItemType.PACKAGE);
		inventory = new InMemoryInventory(new InMemoryStock(catalogue));
		inventory.openSessions = 1;
		service = inventory.service(TestModes.standalone());
	}

	private static Item item(InMemoryCatalogue catalogue, String code, Integer stock, ItemType type) {
		Item item = catalogue.item(code, 10, null);
		item.setStockQuantity(stock == null ? null : BigDecimal.valueOf(stock));
		item.setType(type);
		return item;
	}

	/** A001 by its EAN typed as a number (6, stock 10), B002 on two rows (3 + 4, stock 2), C003 one bad row, a service, an unknown code, E006 equal, F007 a pack without stock (3). */
	private static InputStream countFile() {
		return InventoryFiles.xlsx(row("Code", "Quantité"), row(6191234567890d, 6d), row("B002", 3d), row("b002", 4d),
				row("C003", "x"), row("C003", 2d), row("S004", 1d), row("ZZZ", 3d), row("E006", 4d), row("F007", 3d));
	}

	private long create() {
		Map<String, Object> created = service.create(LocalDate.of(2026, 10, 5), "Annual count", "count.xlsx",
				countFile(), "admin");
		return (Long) created.get("id");
	}

	private int stock(String code) {
		return inventory.stock.stockOf(code);
	}

	@Test
	@DisplayName("Import: barcode then item code (case ignored), rows of one item merged, NOT_FOUND / NOT_COUNTED / BAD_QUANTITY kept; nothing applied")
	@SuppressWarnings("unchecked")
	void importBuildsLines() {
		Map<String, Object> created = service.create(LocalDate.of(2026, 10, 5), " Annual count ", "count.xlsx",
				countFile(), "admin");
		long id = (Long) created.get("id");

		assertEquals("INV-202610-000001", created.get("number"));
		assertEquals(InventoryCountStatus.DRAFT, created.get("status"));
		assertEquals("2026-10-05", created.get("countDate"));
		assertEquals("Annual count", created.get("note"));
		assertEquals("count.xlsx", created.get("fileName"));

		List<InventoryCountLine> lines = inventory.linesOf(id);
		assertEquals(Arrays.asList("6191234567890", "B002", "C003", "S004", "ZZZ", "E006", "F007"),
				lines.stream().map(InventoryCountLine::getCode).collect(Collectors.toList()), "one line per item, file order");

		InventoryCountLine a = inventory.line(id, "6191234567890");
		assertEquals(InventoryLineStatus.OK, a.getStatus());
		assertEquals(catalogue("A001").getId(), a.getItemId(), "found by its barcode");
		assertEquals(BigDecimal.valueOf(6), a.getCountedQuantity());
		assertEquals(BigDecimal.valueOf(10), a.getSystemQuantityAtImport());

		InventoryCountLine b = inventory.line(id, "B002");
		assertEquals(InventoryLineStatus.OK, b.getStatus());
		assertEquals(BigDecimal.valueOf(7), b.getCountedQuantity(), "3 + 4");
		assertEquals(Integer.valueOf(2), b.getMergedRows());

		InventoryCountLine c = inventory.line(id, "C003");
		assertEquals(InventoryLineStatus.BAD_QUANTITY, c.getStatus());
		assertNull(c.getCountedQuantity());
		assertEquals("row 5: not a number: x", c.getMessage());

		assertEquals(InventoryLineStatus.NOT_COUNTED, inventory.line(id, "S004").getStatus());
		InventoryCountLine unknown = inventory.line(id, "ZZZ");
		assertEquals(InventoryLineStatus.NOT_FOUND, unknown.getStatus());
		assertNull(unknown.getItemId());
		assertEquals(BigDecimal.valueOf(0), inventory.line(id, "F007").getSystemQuantityAtImport(), "no stock counts as 0");

		Map<String, Object> summary = (Map<String, Object>) created.get("summary");
		assertEquals(9, summary.get("rowsRead"));
		assertEquals(7L, summary.get("lines"));
		assertEquals(4L, summary.get("linesOk"));
		assertEquals(1L, summary.get("linesNotFound"));
		assertEquals(1L, summary.get("linesNotCounted"));
		assertEquals(1L, summary.get("linesBadQuantity"));
		assertEquals(3L, summary.get("linesWithDifference"), "A001, B002, F007");
		assertEquals(BigDecimal.valueOf(8), summary.get("quantityUp"), "+5 and +3");
		assertEquals(BigDecimal.valueOf(4), summary.get("quantityDown"));
		assertEquals(1L, summary.get("openCashierSessions"));

		assertEquals(10, stock("A001"), "nothing applied at the import");
		assertTrue(inventory.stock.movements.isEmpty());
	}

	@Test
	@DisplayName("Validation: each OK item's stock becomes the counted quantity, one INVENTORY_IN/OUT movement per difference; other items untouched")
	void validationApplies() {
		long id = create();
		catalogue("A001").setStockQuantity(BigDecimal.valueOf(9)); // a sale after the import: the stock read at the validation counts

		Map<String, Object> validated = service.validate(id, "admin").get();

		assertEquals(InventoryCountStatus.VALIDATED, validated.get("status"));
		assertEquals("admin", validated.get("validatedBy"));
		assertEquals(6, stock("A001"));
		assertEquals(7, stock("B002"));
		assertEquals(3, stock("F007"));
		assertEquals(4, stock("E006"));
		assertEquals(5, stock("C003"), "bad quantity: not applied");
		assertEquals(8, stock("D005"), "not in the file: not touched");
		assertNull(catalogue("S004").getStockQuantity(), "a service: not touched");

		List<StockMovement> movements = inventory.stock.movements;
		assertEquals(3, movements.size(), "no movement for E006 (no difference)");
		StockMovement out = movement("A001");
		assertEquals(StockMovementType.INVENTORY_OUT, out.getMovementType());
		assertEquals(StockMovementDirection.OUT, out.getDirection());
		assertEquals(BigDecimal.valueOf(3), out.getQuantity(), "9 - 6");
		StockMovement in = movement("B002");
		assertEquals(StockMovementType.INVENTORY_IN, in.getMovementType());
		assertEquals(StockMovementDirection.IN, in.getDirection());
		assertEquals(BigDecimal.valueOf(5), in.getQuantity());
		for (StockMovement movement : movements) {
			assertEquals("INVENTORY", movement.getReferenceType());
			assertEquals(Long.valueOf(id), movement.getReferenceId());
			assertEquals("INV-202610-000001", movement.getNotes());
			assertEquals("admin", movement.getCreatedBy());
		}

		InventoryCountLine a = inventory.line(id, "6191234567890");
		assertEquals(BigDecimal.valueOf(9), a.getSystemQuantityAtValidation());
		assertEquals(BigDecimal.valueOf(-3), a.getDifferenceApplied());
		assertEquals(BigDecimal.valueOf(0), inventory.line(id, "E006").getDifferenceApplied());
		assertNull(inventory.line(id, "C003").getDifferenceApplied(), "never applied");
	}

	@Test
	@DisplayName("A second validation is refused (409) and changes nothing; a validated count cannot be imported again nor deleted")
	void validatedOnce() {
		long id = create();
		service.validate(id, "admin");
		int movements = inventory.stock.movements.size();
		catalogue("A001").setStockQuantity(BigDecimal.valueOf(1));

		IllegalStateException again = assertThrows(IllegalStateException.class, () -> service.validate(id, "admin"));
		assertEquals("This count is already validated: INV-202610-000001.", again.getMessage());
		assertEquals(1, stock("A001"));
		assertEquals(movements, inventory.stock.movements.size());

		assertThrows(IllegalStateException.class,
				() -> service.reimport(id, "again.xlsx", countFile(), "admin"));
		assertThrows(IllegalStateException.class, () -> service.delete(id));
		assertEquals(7, inventory.linesOf(id).size());
	}

	@Test
	@DisplayName("A draft: imported again its lines are replaced; deleted with its lines; the next number follows the last one")
	void draftReimportAndDelete() {
		long id = create();
		service.reimport(id, "second.xlsx", InventoryFiles.xlsx(row("A001", 1d)), "admin");
		assertEquals(1, inventory.linesOf(id).size());
		assertEquals("second.xlsx", inventory.counts.get(id).getFileName());
		assertEquals(1, inventory.counts.get(id).getRowsRead());

		long second = create();
		assertEquals("INV-202610-000002", inventory.counts.get(second).getNumber());

		assertTrue(service.delete(id));
		assertFalse(inventory.counts.containsKey(id));
		assertTrue(inventory.linesOf(id).isEmpty());
		assertFalse(service.delete(id), "unknown: 404");
		assertFalse(service.validate(id, "admin").isPresent(), "unknown: 404");
	}

	@Test
	@DisplayName("A number cell not found: tried with leading zeros up to 14 digits, as a barcode and as an item code,"
			+ " taken only when exactly one item matches")
	void leadingZerosOfANumberCell() {
		InMemoryCatalogue catalogue = inventory.catalogue;
		catalogue.barcode("0012345678905", item(catalogue, "U001", 1, null)); // a UPC stored as an EAN-13
		catalogue.barcode("0555", item(catalogue, "X001", 1, null));
		item(catalogue, "00555", 1, null); // 555 matches two items: never a guess
		item(catalogue, "00777", 2, null); // an item code with leading zeros

		long id = (Long) service.create(null, null, "zeros.xlsx", InventoryFiles.xlsx(row("Code", "Qty"),
				row(12345678905d, 4d), row(555d, 1d), row(777d, 3d)), "admin").get("id");

		InventoryCountLine upc = inventory.line(id, "12345678905");
		assertEquals(InventoryLineStatus.OK, upc.getStatus());
		assertEquals(catalogue.itemByCode("U001").get().getId(), upc.getItemId());
		assertEquals(BigDecimal.valueOf(4), upc.getCountedQuantity());
		assertEquals("Found with leading zeros: 0012345678905", upc.getMessage());

		assertEquals(InventoryLineStatus.NOT_FOUND, inventory.line(id, "555").getStatus(), "two matches");
		InventoryCountLine code = inventory.line(id, "777");
		assertEquals(InventoryLineStatus.OK, code.getStatus());
		assertEquals(catalogue.itemByCode("00777").get().getId(), code.getItemId(), "an item code too");
	}

	@Test
	@DisplayName("A text cell is never padded: only Excel's number cells lose their zeros")
	void textCellNotPadded() {
		inventory.catalogue.barcode("0012345678905", item(inventory.catalogue, "U001", 1, null));
		long id = (Long) service.create(null, null, "text.xlsx", InventoryFiles.xlsx(row("12345678905", 1d)), "admin")
				.get("id");
		assertEquals(InventoryLineStatus.NOT_FOUND, inventory.line(id, "12345678905").getStatus());
	}

	@Test
	@DisplayName("A file without data rows is refused (400)")
	void emptyFile() {
		IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
				() -> service.create(null, null, "empty.xlsx", InventoryFiles.xlsx(row("Code", "Qty")), "admin"));
		assertEquals("The file has no row to import.", refused.getMessage());
		assertTrue(inventory.counts.isEmpty());
	}

	@Test
	@DisplayName("Supply from the ERP, or a head office: every endpoint answers 403 and nothing is written")
	void notAvailable() {
		ApplicationModeService headOffice = TestModes.of(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE"));
		for (ApplicationModeService mode : new ApplicationModeService[] { TestModes.erp(), headOffice }) {
			InventoryCountService refused = inventory.service(mode);
			assertFalse(refused.isAvailable());
			assertThrows(InventoryCountService.NotAvailableException.class,
					() -> refused.create(null, null, "count.xlsx", countFile(), "admin"));

			InventoryCountAPI api = new InventoryCountAPI(refused, null);
			MockMultipartFile file = new MockMultipartFile("file", "count.xlsx", null, new byte[] { 1 });
			for (ResponseEntity<?> answer : Arrays.asList(api.list(null, null), api.create(file, null, null),
					api.get(1L), api.reimport(1L, file), api.lines(1L, null, null, null, null), api.validate(1L),
					api.delete(1L))) {
				assertEquals(HttpStatus.FORBIDDEN, answer.getStatusCode());
				assertEquals(InventoryCountService.NOT_AVAILABLE, ((Map<?, ?>) answer.getBody()).get("error"));
			}
		}
		assertTrue(inventory.counts.isEmpty());
		assertTrue(inventory.stock.movements.isEmpty());
	}

	@Test
	@DisplayName("A store fed by its head office keeps its own stock: inventory counts are available")
	void supplyFromHeadOfficeAvailable() {
		MockEnvironment supplied = new MockEnvironment().withProperty("headoffice.url", "http://localhost:888/zsretail/api")
				.withProperty("headoffice.api-key", "k").withProperty("ownership.catalogue", "HEAD_OFFICE")
				.withProperty("ownership.supply", "HEAD_OFFICE");
		assertTrue(inventory.service(TestModes.of(supplied)).isAvailable());
	}

	private Item catalogue(String code) {
		return inventory.catalogue.itemByCode(code).get();
	}

	private StockMovement movement(String itemCode) {
		return inventory.stock.movements.stream().filter(m -> itemCode.equals(m.getItem().getItemCode())).findFirst()
				.orElseThrow(() -> new IllegalArgumentException(itemCode));
	}

	// --- 2.2.1: decimal quantities ---

	/**
	 * The shapes of a real count file: A001 by its EAN as a number 1.34 (stock 10), B002 on two rows "0,25" + 0.5
	 * (stock 2), C003 with a 4th decimal (stock 5), D005 whole and equal (8), a row without code, an unknown code 0.2.
	 */
	private static InputStream decimalFile() {
		return InventoryFiles.xlsx(row("Code à barre", "Quantité"), row(6191234567890d, 1.34d), row("B002", "0,25"),
				row("B002", 0.5d), row("C003", 1.3405d), row("D005", 8d), row(null, 2d), row("ZZZ", 0.2d));
	}

	private static String plain(BigDecimal quantity) {
		return quantity == null ? null : quantity.toPlainString();
	}

	@Test
	@DisplayName("2.2.1, decimals allowed: 1.34 imported and validated, the stock becomes 1.34 and the movement holds the difference; a 4th decimal refused by row and code")
	@SuppressWarnings("unchecked")
	void decimalCountImportedAndValidated() {
		inventory.allowDecimal = true;
		long id = (Long) service.create(LocalDate.of(2026, 10, 10), null, "decimal.xlsx", decimalFile(), "admin")
				.get("id");

		InventoryCountLine a = inventory.line(id, "6191234567890");
		assertEquals(InventoryLineStatus.OK, a.getStatus());
		assertEquals("1.34", plain(a.getCountedQuantity()));
		assertEquals("10", plain(a.getSystemQuantityAtImport()));
		InventoryCountLine b = inventory.line(id, "B002");
		assertEquals("0.75", plain(b.getCountedQuantity()), "0,25 + 0.5");
		assertEquals(Integer.valueOf(2), b.getMergedRows());
		InventoryCountLine c = inventory.line(id, "C003");
		assertEquals(InventoryLineStatus.BAD_QUANTITY, c.getStatus());
		assertEquals("row 5: more than 3 decimals: 1.3405 (code C003)", c.getMessage());
		assertEquals("0.2", plain(inventory.line(id, "ZZZ").getCountedQuantity()));
		assertEquals("No code", inventory.line(id, "").getMessage());

		Map<String, Object> summary = (Map<String, Object>) service.get(id).get().get("summary");
		assertEquals(3L, summary.get("linesOk"));
		assertEquals(1L, summary.get("linesBadQuantity"));
		assertEquals(2L, summary.get("linesNotFound"));
		assertEquals(2L, summary.get("linesWithDifference"));
		assertEquals("0", plain((BigDecimal) summary.get("quantityUp")));
		assertEquals("9.91", plain((BigDecimal) summary.get("quantityDown")), "(10 - 1.34) + (2 - 0.75)");

		List<Map<String, Object>> rows = (List<Map<String, Object>>) service.lines(id, "all", "6191234567890", 0, 10)
				.get().get("content");
		assertEquals("1.34", plain((BigDecimal) rows.get(0).get("countedQuantity")));
		assertEquals("10", plain((BigDecimal) rows.get(0).get("systemQuantity")), "10.000 sent as 10");
		assertEquals("-8.66", plain((BigDecimal) rows.get(0).get("difference")));

		service.validate(id, "admin");

		assertEquals("1.34", plain(inventory.stock.quantityOf("A001")));
		assertEquals("0.75", plain(inventory.stock.quantityOf("B002")));
		assertEquals("5", plain(inventory.stock.quantityOf("C003")), "the bad line is not applied");
		assertEquals("8", plain(inventory.stock.quantityOf("D005")));
		StockMovement out = movement("A001");
		assertEquals(StockMovementType.INVENTORY_OUT, out.getMovementType());
		assertEquals("8.66", plain(out.getQuantity()));
		assertEquals("1.25", plain(movement("B002").getQuantity()));
		assertEquals(2, inventory.stock.movements.size(), "no movement for D005 (equal) nor C003");
		InventoryCountLine applied = inventory.line(id, "6191234567890");
		assertEquals("10", plain(applied.getSystemQuantityAtValidation()));
		assertEquals("-8.66", plain(applied.getDifferenceApplied()));
		summary = (Map<String, Object>) service.get(id).get().get("summary");
		assertEquals("9.91", plain((BigDecimal) summary.get("quantityDown")), "validated: from what was applied");
	}

	@Test
	@DisplayName("2.2.1, decimals not allowed (the default): every decimal row refused as in 2.2.0, row by row; nothing applied for them")
	void decimalRefusedWhenSettingOff() {
		long id = (Long) service.create(LocalDate.of(2026, 10, 10), null, "decimal.xlsx", decimalFile(), "admin")
				.get("id");

		assertEquals("row 2: not a whole number: 1.34", inventory.line(id, "6191234567890").getMessage());
		assertEquals("row 3: not a whole number: 0.25; row 4: not a whole number: 0.5", inventory.line(id, "B002").getMessage(),
				"every row of the item with its reason, as in 2.2.0");
		assertEquals("row 5: not a whole number: 1.3405", inventory.line(id, "C003").getMessage());
		assertEquals(InventoryLineStatus.OK, inventory.line(id, "D005").getStatus());

		service.validate(id, "admin");
		assertEquals(10, stock("A001"));
		assertEquals(2, stock("B002"));
		assertTrue(inventory.stock.movements.isEmpty(), "D005 equal, the others refused");
	}

	@Test
	@DisplayName("2.2.1: a whole-number count gives the same lines and summary with decimals allowed or not (2.2.0)")
	@SuppressWarnings("unchecked")
	void wholeCountSameEitherWay() {
		long off = create();
		inventory.allowDecimal = true;
		long on = create();

		List<String> linesOff = inventory.linesOf(off).stream().map(InventoryCountServiceTest::describe)
				.collect(Collectors.toList());
		List<String> linesOn = inventory.linesOf(on).stream().map(InventoryCountServiceTest::describe)
				.collect(Collectors.toList());
		assertEquals(linesOff, linesOn);
		Map<String, Object> summaryOff = (Map<String, Object>) service.get(off).get().get("summary");
		Map<String, Object> summaryOn = (Map<String, Object>) service.get(on).get().get("summary");
		assertEquals(summaryOff.toString(), summaryOn.toString());
		assertEquals("8", plain((BigDecimal) summaryOn.get("quantityUp")), "written as 8, not 8.000");
	}

	private static String describe(InventoryCountLine line) {
		return line.getCode() + " " + line.getStatus() + " " + plain(line.getCountedQuantity()) + " "
				+ plain(line.getSystemQuantityAtImport()) + " " + line.getMergedRows() + " " + line.getMessage();
	}
}
