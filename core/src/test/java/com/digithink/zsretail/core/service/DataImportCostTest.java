package com.digithink.zsretail.core.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;

import com.digithink.zsretail.core.dto.ImportFieldMappingDTO;
import com.digithink.zsretail.core.dto.ImportResultDTO;
import com.digithink.zsretail.core.model.Item;
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
 * CMP, the item import (Excel): the cost price column is the starting weighted average of a new item only; an item
 * already here keeps the CMP its entries wrote (its other columns are imported as before).
 */
class DataImportCostTest {

	@Test
	@DisplayName("A new item takes the cost of its row; an existing item keeps its CMP, its name and price imported")
	void costForNewItemsOnly() throws Exception {
		Map<String, Item> byCode = new HashMap<>();
		Item existing = new Item();
		existing.setId(7L);
		existing.setItemCode("A1");
		existing.setName("Before");
		existing.setCostPrice(7.875);
		byCode.put("A1", existing);
		ItemRepository items = mock(ItemRepository.class);
		when(items.findByItemCode(anyString())).thenAnswer(a -> Optional.ofNullable(byCode.get(a.getArgument(0))));
		when(items.save(any(Item.class))).thenAnswer(a -> {
			Item item = a.getArgument(0);
			byCode.put(item.getItemCode(), item);
			return item;
		});
		@SuppressWarnings("unchecked")
		ObjectProvider<CatalogueHeadOfficeHooks> hooks = mock(ObjectProvider.class);
		DataImportService service = new DataImportService(mock(ItemFamilyRepository.class),
				mock(ItemSubFamilyRepository.class), items, mock(ItemBarcodeRepository.class), mock(VendorRepository.class),
				mock(CustomerRepository.class), mock(LocationRepository.class), mock(SalesPriceRepository.class),
				mock(SalesDiscountRepository.class), hooks, mock(StockMovementService.class),
				mock(PlatformTransactionManager.class));

		byte[] file;
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet();
			row(sheet.createRow(0), "Code", "Name", "Price", "Cost");
			row(sheet.createRow(1), "A1", "After", "12", "4");
			row(sheet.createRow(2), "A2", "New", "10", "6.5");
			row(sheet.createRow(3), "A3", "No cost", "10", "");
			workbook.write(out);
			file = out.toByteArray();
		}
		List<ImportFieldMappingDTO> mapping = Arrays.asList(new ImportFieldMappingDTO("itemCode", "Code"),
				new ImportFieldMappingDTO("name", "Name"), new ImportFieldMappingDTO("unitPrice", "Price"),
				new ImportFieldMappingDTO("costPrice", "Cost"));
		ImportResultDTO result = service.executeImport(new MockMultipartFile("file", "items.xlsx", null, file), "ITEMS",
				mapping);

		assertEquals(3, result.getSuccessCount());
		assertEquals("After", byCode.get("A1").getName());
		assertEquals(12.0, byCode.get("A1").getUnitPrice());
		assertEquals(7.875, byCode.get("A1").getCostPrice(), "the CMP of an existing item is kept");
		assertEquals(6.5, byCode.get("A2").getCostPrice(), "a new item: its starting CMP");
		assertNull(byCode.get("A3").getCostPrice());
	}

	private static void row(Row row, String... values) {
		for (int i = 0; i < values.length; i++) {
			row.createCell(i).setCellValue(values[i]);
		}
	}
}
