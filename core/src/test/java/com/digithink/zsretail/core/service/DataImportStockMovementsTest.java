package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.ImportFieldMappingDTO;
import com.digithink.zsretail.core.dto.ImportResultDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.StockMovement;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
import com.digithink.zsretail.core.repository.CustomerRepository;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.repository.LocationRepository;
import com.digithink.zsretail.core.repository.SalesDiscountRepository;
import com.digithink.zsretail.core.repository.SalesPriceRepository;
import com.digithink.zsretail.core.repository.StockBatchRepository;
import com.digithink.zsretail.core.repository.StockMovementRepository;
import com.digithink.zsretail.core.repository.VendorRepository;
import com.digithink.zsretail.core.support.TestModes;

/**
 * 3.1.2, the stock value at a date: the stock the Excel item import writes has its movement, in the row's transaction. A
 * new item: OPENING_STOCK (note "Import Excel", created by Import); an existing item: the difference as ADJUSTMENT_IN /
 * ADJUSTMENT_OUT (note "Import Excel"), nothing when it does not change; no stock column: nothing. A row in error rolls
 * back. Supply from the ERP: no movement. Real DataImportService and StockMovementService over mocks.
 */
class DataImportStockMovementsTest {

	private final Map<String, Item> byCode = new HashMap<>();
	private final Map<Long, Item> byId = new HashMap<>();
	private final List<StockMovement> movements = new ArrayList<>();
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private long nextId = 100;

	private Item existing(long id, String code, int stock) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		item.setName(code);
		item.setStockQuantity(BigDecimal.valueOf(stock));
		byCode.put(code, item);
		byId.put(id, item);
		return item;
	}

	private DataImportService service(ApplicationModeService mode) throws Exception {
		ItemRepository items = mock(ItemRepository.class);
		when(items.findByItemCode(anyString())).thenAnswer(a -> Optional.ofNullable(byCode.get(a.getArgument(0))));
		when(items.findById(anyLong())).thenAnswer(a -> Optional.ofNullable(byId.get(a.getArgument(0))));
		when(items.save(any(Item.class))).thenAnswer(a -> {
			Item item = a.getArgument(0);
			if (item.getId() == null) {
				item.setId(nextId++);
			}
			byCode.put(item.getItemCode(), item);
			byId.put(item.getId(), item);
			return item;
		});
		StockMovementRepository movementRepository = mock(StockMovementRepository.class);
		when(movementRepository.save(any(StockMovement.class))).thenAnswer(a -> {
			movements.add(a.getArgument(0));
			return a.getArgument(0);
		});
		StockMovementService stockMovements = new StockMovementService();
		set(stockMovements, "applicationModeService", mode);
		set(stockMovements, "stockMovementRepository", movementRepository);
		set(stockMovements, "itemRepository", items);
		set(stockMovements, "stockBatchRepository", mock(StockBatchRepository.class));
		@SuppressWarnings("unchecked")
		ObjectProvider<CatalogueHeadOfficeHooks> hooks = mock(ObjectProvider.class);
		return new DataImportService(mock(ItemFamilyRepository.class), mock(ItemSubFamilyRepository.class), items,
				mock(ItemBarcodeRepository.class), mock(VendorRepository.class), mock(CustomerRepository.class),
				mock(LocationRepository.class), mock(SalesPriceRepository.class), mock(SalesDiscountRepository.class), hooks,
				stockMovements, transactions);
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = StockMovementService.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static MockMultipartFile file(String[]... rows) throws Exception {
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet();
			for (int r = 0; r < rows.length; r++) {
				Row row = sheet.createRow(r);
				for (int c = 0; c < rows[r].length; c++) {
					row.createCell(c).setCellValue(rows[r][c]);
				}
			}
			workbook.write(out);
			return new MockMultipartFile("file", "items.xlsx", null, out.toByteArray());
		}
	}

	private static List<ImportFieldMappingDTO> mapping(boolean withStock) {
		List<ImportFieldMappingDTO> mapping = new ArrayList<>(Arrays.asList(new ImportFieldMappingDTO("itemCode", "Code"),
				new ImportFieldMappingDTO("name", "Name"), new ImportFieldMappingDTO("unitPrice", "Price")));
		if (withStock) {
			mapping.add(new ImportFieldMappingDTO("stockQuantity", "Stock"));
		}
		return mapping;
	}

	private StockMovement movementOf(String code) {
		Long id = byCode.get(code).getId();
		List<StockMovement> found = movements.stream().filter(m -> m.getItem().getId().equals(id))
				.collect(Collectors.toList());
		assertEquals(1, found.size(), "one movement for " + code);
		return found.get(0);
	}

	@Test
	@DisplayName("A new item: OPENING_STOCK; an existing one: its difference as ADJUSTMENT_IN / OUT; unchanged: nothing")
	void movementsOfTheImport() throws Exception {
		existing(1L, "UP", 3);
		existing(2L, "DOWN", 8);
		existing(3L, "SAME", 4);
		ImportResultDTO result = service(TestModes.standalone()).executeImport(file(
				new String[] { "Code", "Name", "Price", "Stock" },
				new String[] { "UP", "Up", "10", "8" },
				new String[] { "DOWN", "Down", "10", "2" },
				new String[] { "SAME", "Same", "10", "4" },
				new String[] { "NEW", "New", "10", "5" },
				new String[] { "NONE", "No stock", "10", "" }), "ITEMS", mapping(true));

		assertEquals(5, result.getSuccessCount());
		assertEquals(3, movements.size(), "UP, DOWN and NEW only");
		StockMovement up = movementOf("UP");
		assertEquals(StockMovementType.ADJUSTMENT_IN, up.getMovementType());
		assertEquals(StockMovementDirection.IN, up.getDirection());
		assertEquals(0, BigDecimal.valueOf(5).compareTo(up.getQuantity()));
		assertEquals("Import Excel", up.getNotes());
		assertEquals("ADJUSTMENT", up.getReferenceType());
		StockMovement down = movementOf("DOWN");
		assertEquals(StockMovementType.ADJUSTMENT_OUT, down.getMovementType());
		assertEquals(StockMovementDirection.OUT, down.getDirection());
		assertEquals(0, BigDecimal.valueOf(6).compareTo(down.getQuantity()));
		StockMovement opening = movementOf("NEW");
		assertEquals(StockMovementType.OPENING_STOCK, opening.getMovementType());
		assertEquals(StockMovementDirection.IN, opening.getDirection());
		assertEquals(0, BigDecimal.valueOf(5).compareTo(opening.getQuantity()));
		assertEquals("Import Excel", opening.getNotes());
		assertEquals("OPENING_STOCK", opening.getReferenceType());
		assertEquals("Import", opening.getCreatedBy());
		assertEquals(0, BigDecimal.valueOf(8).compareTo(byCode.get("UP").getStockQuantity()), "the stock as before");
		verify(transactions, times(5)).commit(any());
	}

	@Test
	@DisplayName("No stock column: the stock is not touched and no movement is written")
	void noStockColumn() throws Exception {
		existing(1L, "A", 3);
		service(TestModes.standalone()).executeImport(file(new String[] { "Code", "Name", "Price" },
				new String[] { "A", "A", "10" }, new String[] { "B", "B", "10" }), "ITEMS", mapping(false));
		assertTrue(movements.isEmpty());
		assertEquals(0, BigDecimal.valueOf(3).compareTo(byCode.get("A").getStockQuantity()));
	}

	@Test
	@DisplayName("A row in error: its transaction rolled back, the other rows imported")
	void rowInError() throws Exception {
		ImportResultDTO result = service(TestModes.standalone()).executeImport(file(
				new String[] { "Code", "Name", "Price", "Stock" },
				new String[] { "OK", "Ok", "10", "2" },
				new String[] { "BAD", "Bad", "not a price", "2" }), "ITEMS", mapping(true));
		assertEquals(1, result.getSuccessCount());
		assertEquals(1, result.getErrorCount());
		verify(transactions, times(1)).commit(any());
		verify(transactions, times(1)).rollback(any());
		assertEquals(1, movements.size());
	}

	@Test
	@DisplayName("Supply from the ERP (the import screen refuses it first): the stock written as before, no movement")
	void supplyFromErp() throws Exception {
		existing(1L, "A", 3);
		service(TestModes.erp()).executeImport(file(new String[] { "Code", "Name", "Price", "Stock" },
				new String[] { "A", "A", "10", "9" }, new String[] { "B", "B", "10", "4" }), "ITEMS", mapping(true));
		assertTrue(movements.isEmpty());
		assertEquals(0, BigDecimal.valueOf(9).compareTo(byCode.get("A").getStockQuantity()));
		verify(transactions, never()).rollback(any());
	}
}
