package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import com.digithink.zsretail.core.dto.ImportFieldMappingDTO;
import com.digithink.zsretail.core.dto.ImportResultDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.enumeration.CostChangeSource;
import com.digithink.zsretail.core.repository.CustomerRepository;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemFamilyRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.core.repository.LocationRepository;
import com.digithink.zsretail.core.repository.SalesDiscountRepository;
import com.digithink.zsretail.core.repository.SalesPriceRepository;
import com.digithink.zsretail.core.repository.VendorRepository;

/**
 * CMP, the item import (Excel): the cost price column is the starting weighted average of a new item; 3.1.2, the initial
 * cost: also of an item already here without a CMP (a cost above 0, an EXCEL_IMPORT history row); an item with a CMP
 * keeps the CMP its entries wrote, the cell ignored and counted (costsIgnored). Its other columns are imported as before.
 * The CMP decided on as stored (findCostPriceById, the row locked), not as the row was read.
 */
class DataImportCostTest {

	private final Map<String, Item> byCode = new HashMap<>();
	private ItemRepository items;
	private CostHistoryService history;
	private DataImportService service;

	@BeforeEach
	void setUp() {
		items = mock(ItemRepository.class);
		when(items.findByItemCode(anyString())).thenAnswer(a -> Optional.ofNullable(byCode.get(a.getArgument(0))));
		when(items.findCostPriceById(anyLong())).thenAnswer(a -> byCode.values().stream()
				.filter(i -> a.getArgument(0).equals(i.getId())).findFirst().map(Item::getCostPrice).orElse(null));
		when(items.save(any(Item.class))).thenAnswer(a -> {
			Item item = a.getArgument(0);
			byCode.put(item.getItemCode(), item);
			return item;
		});
		history = mock(CostHistoryService.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<CatalogueHeadOfficeHooks> hooks = mock(ObjectProvider.class);
		service = new DataImportService(mock(ItemFamilyRepository.class), mock(ItemSubFamilyRepository.class), items,
				mock(ItemBarcodeRepository.class), mock(VendorRepository.class), mock(CustomerRepository.class),
				mock(LocationRepository.class), mock(SalesPriceRepository.class), mock(SalesDiscountRepository.class), hooks,
				mock(StockMovementService.class), history, mock(PlatformTransactionManager.class));
	}

	@Test
	@DisplayName("A new item takes the cost of its row; an existing item keeps its CMP, its name and price imported")
	void costForNewItemsOnly() throws Exception {
		existing(7L, "A1", 7.875);
		ImportResultDTO result = importRows(true, row("A1", "After", "12", "4"), row("A2", "New", "10", "6.5"),
				row("A3", "No cost", "10", ""));

		assertEquals(3, result.getSuccessCount());
		assertEquals("After", byCode.get("A1").getName());
		assertEquals(12.0, byCode.get("A1").getUnitPrice());
		assertEquals(7.875, byCode.get("A1").getCostPrice(), "the CMP of an existing item is kept");
		assertEquals(6.5, byCode.get("A2").getCostPrice(), "a new item: its starting CMP");
		assertNull(byCode.get("A3").getCostPrice());
	}

	@Test
	@DisplayName("Initial cost: an existing item without a CMP takes a cost above 0, with its EXCEL_IMPORT history row")
	void initialCostWhenNone() throws Exception {
		existing(7L, "A1", null);
		existing(8L, "A2", null);
		existing(9L, "A3", null);
		ImportResultDTO result = importRows(true, row("A1", "One", "12", "4,5"), row("A2", "Two", "10", "0"),
				row("A3", "Three", "10", ""));

		assertEquals(3, result.getSuccessCount());
		assertEquals(4.5, byCode.get("A1").getCostPrice(), "no CMP: the row's cost is its initial one");
		assertNull(byCode.get("A2").getCostPrice(), "0 is not an initial cost");
		assertNull(byCode.get("A3").getCostPrice(), "an empty cell: nothing");
		assertEquals(0, result.getCostsIgnored());
		ArgumentCaptor<CostHistoryService.Origin> origin = ArgumentCaptor.forClass(CostHistoryService.Origin.class);
		verify(history).record(eq(7L), isNull(), eq(4.5), isNull(), eq(4.5), origin.capture());
		assertEquals(CostChangeSource.EXCEL_IMPORT, origin.getValue().source);
		assertEquals("Import", origin.getValue().changedBy);
		verify(history, never()).record(eq(8L), any(), any(), any(), any(), any());
		verify(history, never()).record(eq(9L), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("An existing item with a CMP: the cost cell ignored and counted; one without in the same file takes its cost")
	void costIgnoredWhenCmp() throws Exception {
		existing(7L, "A1", 7.875);
		existing(8L, "A2", null);
		ImportResultDTO result = importRows(true, row("A1", "One", "12", "4"), row("A2", "Two", "10", "3"));

		assertEquals(2, result.getSuccessCount());
		assertEquals(7.875, byCode.get("A1").getCostPrice());
		assertEquals(3.0, byCode.get("A2").getCostPrice());
		assertEquals(1, result.getCostsIgnored(), "A1's cell; A2 had no CMP");
		verify(history, never()).record(eq(7L), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("An item read without a CMP that a reception costed since: the stored CMP kept, the cell ignored and counted")
	void storedCmpWins() throws Exception {
		Item read = existing(7L, "A1", null);
		when(items.findCostPriceById(7L)).thenReturn(5.25); // written by an entry after the row was read
		ImportResultDTO result = importRows(true, row("A1", "One", "12", "4"));

		assertEquals(5.25, read.getCostPrice(), "the stored CMP, not the cell, and not the null read before");
		assertEquals(1, result.getCostsIgnored());
		verify(items).lockForCostChange(7L);
		verify(history, never()).record(eq(7L), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("A file without the cost column imports as before: no cost set, none ignored, the CMP never read")
	void noCostColumn() throws Exception {
		existing(7L, "A1", null);
		existing(8L, "A2", 7.875);
		ImportResultDTO result = importRows(false, row("A1", "One", "12", null), row("A2", "Two", "10", null),
				row("A3", "New", "10", null));

		assertEquals(3, result.getSuccessCount());
		assertNull(byCode.get("A1").getCostPrice());
		assertEquals(7.875, byCode.get("A2").getCostPrice());
		assertNull(byCode.get("A3").getCostPrice());
		assertEquals(0, result.getCostsIgnored());
		verify(items, never()).lockForCostChange(anyLong());
		verify(items, never()).findCostPriceById(anyLong());
	}

	private Item existing(long id, String code, Double cost) {
		Item item = new Item();
		item.setId(id);
		item.setItemCode(code);
		item.setName("Before");
		item.setCostPrice(cost);
		byCode.put(code, item);
		return item;
	}

	private static String[] row(String... values) {
		return values;
	}

	/** The rows under Code, Name, Price and (withCost) Cost, mapped as the page maps them. */
	private ImportResultDTO importRows(boolean withCost, String[]... rows) throws Exception {
		byte[] file;
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet();
			cells(sheet.createRow(0), withCost ? new String[] { "Code", "Name", "Price", "Cost" }
					: new String[] { "Code", "Name", "Price" });
			for (int r = 0; r < rows.length; r++) {
				cells(sheet.createRow(r + 1), withCost ? rows[r] : Arrays.copyOf(rows[r], 3));
			}
			workbook.write(out);
			file = out.toByteArray();
		}
		List<ImportFieldMappingDTO> mapping = new ArrayList<>(Arrays.asList(new ImportFieldMappingDTO("itemCode", "Code"),
				new ImportFieldMappingDTO("name", "Name"), new ImportFieldMappingDTO("unitPrice", "Price")));
		if (withCost) {
			mapping.add(new ImportFieldMappingDTO("costPrice", "Cost"));
		}
		return service.executeImport(new MockMultipartFile("file", "items.xlsx", null, file), "ITEMS", mapping);
	}

	private static void cells(Row row, String... values) {
		for (int i = 0; i < values.length; i++) {
			row.createCell(i).setCellValue(values[i]);
		}
	}
}
