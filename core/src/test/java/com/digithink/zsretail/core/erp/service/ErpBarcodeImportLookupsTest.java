package com.digithink.zsretail.core.erp.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.core.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;
import com.digithink.zsretail.core.repository.ItemBarcodeRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.erp.api.dto.ErpItemBarcodeDTO;

/**
 * 3.1.1 speed (barcodes step of the ERP import): one window of 1,000 barcode rows reads its items in two queries (by ERP
 * id, by code) and its barcodes in two (by ERP id, by value), never one SELECT per row (each also flushed the packet:
 * 13,165 ParaFendri barcodes ran more than 27 minutes). The rows are written as before: the barcode found by ERP id then
 * by value, its item by ERP id then by code, one WARNING per row left out, the primary flag only when the ERP gives one.
 */
class ErpBarcodeImportLookupsTest {

	private InMemoryItems tables;
	private final List<String> calls = new ArrayList<>();
	private ErpCommunicationService communications;
	private ErpItemBootstrapService imports;
	private Item shirt;
	private Item tie;

	/** The repository, its calls recorded by method name. */
	@SuppressWarnings("unchecked")
	private <T> T counted(Class<T> type, T target) {
		return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, (proxy, method, args) -> {
			calls.add(type.getSimpleName() + "." + method.getName());
			try {
				return method.invoke(target, args);
			} catch (InvocationTargetException e) {
				throw e.getCause();
			}
		});
	}

	@BeforeEach
	void setUp() {
		tables = new InMemoryItems(1);
		shirt = tables.item("70061~001", 50.0, null);
		shirt.setErpExternalId("E-SHIRT");
		tie = tables.item("14ZDU05~001", 19.5, null); // known by its code only
		communications = mock(ErpCommunicationService.class);
		imports = new ErpItemBootstrapService(tables.familyRepository(), tables.subFamilyRepository(),
				counted(ItemRepository.class, tables.itemRepository()),
				counted(ItemBarcodeRepository.class, tables.barcodeRepository()), null, null, null, null,
				communications, null, tables.categoryRepository(), tables.sectionRepository());
	}

	private static ErpItemBarcodeDTO dto(String externalId, String item, String barcode, Boolean primary) {
		ErpItemBarcodeDTO dto = new ErpItemBarcodeDTO();
		dto.setExternalId(externalId);
		dto.setItemExternalId(item);
		dto.setBarcode(barcode);
		dto.setPrimaryBarcode(primary);
		return dto;
	}

	@Test
	@DisplayName("A packet of 2,000 rows: two windows of four reads each (items by ERP id and by code, barcodes by ERP id"
			+ " and by value), no query per row, all 2,000 written, well under a second")
	void boundedQueries() {
		List<ErpItemBarcodeDTO> packet = new ArrayList<>();
		for (int i = 0; i < 2000; i++) {
			packet.add(dto("B" + i, i % 2 == 0 ? "E-SHIRT" : "14ZDU05~001", "619" + i, null));
		}
		long started = System.nanoTime();
		List<ItemBarcode> saved = imports.importItemBarcodes(packet);
		long ms = (System.nanoTime() - started) / 1_000_000;

		List<String> reads = new ArrayList<>(calls);
		reads.removeIf(call -> call.endsWith(".save"));
		List<String> window = Arrays.asList("ItemRepository.findByErpExternalIdIn", "ItemRepository.findByItemCodeIn",
				"ItemBarcodeRepository.findByErpExternalIdIn", "ItemBarcodeRepository.findByBarcodeIn");
		List<String> expected = new ArrayList<>(window);
		expected.addAll(window);
		assertEquals(expected, reads, "no findByErpExternalId / findByItemCode / findByBarcode per row");
		assertEquals(2000, saved.size());
		assertEquals(2000, tables.barcodeSaves);
		assertTrue(ms < 1000, "2,000 rows in " + ms + " ms");
	}

	@Test
	@DisplayName("The rows as before: found by ERP id first, then by value; the item by ERP id or by code; a new one"
			+ " created; the same barcode twice in a packet updated, not made twice; the primary flag only when given")
	void sameResults() {
		ItemBarcode byExternal = tables.barcode("111", shirt);
		byExternal.setErpExternalId("X-1");
		byExternal.setIsPrimary(true);
		ItemBarcode byValue = tables.barcode("222", tie);
		byValue.setErpExternalId("OLD-2");
		ItemBarcode other = tables.barcode("333", tie);
		other.setErpExternalId("X-3");

		List<ItemBarcode> saved = imports.importItemBarcodes(Arrays.asList(dto("X-1", "14ZDU05~001", "1110", null),
				dto("X-2", "E-SHIRT", "222", false), dto("X-4", "E-SHIRT", "444", null),
				dto("X-4", "E-SHIRT", "444", true)));

		assertSame(byExternal, saved.get(0), "found by its ERP id, its value changed");
		assertEquals("1110", byExternal.getBarcode());
		assertSame(tie, byExternal.getItem(), "the item found by its code");
		assertTrue(byExternal.getIsPrimary(), "no primary flag given: kept");
		assertSame(byValue, saved.get(1), "no barcode of ERP id X-2: found by its value");
		assertEquals("X-2", byValue.getErpExternalId());
		assertSame(shirt, byValue.getItem(), "the item found by its ERP id");
		assertFalse(byValue.getIsPrimary());
		ItemBarcode created = saved.get(2);
		assertEquals("444", created.getBarcode(), "created");
		assertSame(created, saved.get(3), "the same barcode twice in a packet: the row created first, updated");
		assertTrue(created.getIsPrimary());
		assertEquals("333", other.getBarcode(), "untouched");
		assertEquals(4, tables.barcodes.size());
	}

	@Test
	@DisplayName("A barcode whose value changed is no longer found by its old value: a later row of that value is a new"
			+ " barcode, as with the queries per row")
	void oldValueForgotten() {
		ItemBarcode moved = tables.barcode("555", shirt);
		moved.setErpExternalId("X-5");

		List<ItemBarcode> saved = imports.importItemBarcodes(
				Arrays.asList(dto("X-5", "E-SHIRT", "5550", null), dto(null, "E-SHIRT", "555", null)));

		assertSame(moved, saved.get(0));
		assertEquals("5550", moved.getBarcode());
		assertEquals("555", saved.get(1).getBarcode());
		assertTrue(saved.get(1) != moved, "a new barcode, not the moved one");
	}

	@Test
	@DisplayName("The lookup keys compare as SQL Server compares the columns (the IN query finds a value of another case"
			+ " or with trailing spaces): else a second row of a barcode the query found, refused by the unique key")
	void sqlServerComparison() {
		assertEquals(ErpItemBootstrapService.key("ABC123"), ErpItemBootstrapService.key("abc123  "));
		assertFalse(ErpItemBootstrapService.key(" 123").equals(ErpItemBootstrapService.key("123")),
				"leading spaces count");
		assertEquals(null, ErpItemBootstrapService.key(null));
	}

	@Test
	@DisplayName("One WARNING row per barcode left out, with the same messages: no parent item, no value")
	void warnings() {
		List<ItemBarcode> saved = imports.importItemBarcodes(Arrays.asList(dto("X-9", "NONE", "999", null),
				dto(null, "E-SHIRT", " ", null), null, dto("X-8", "E-SHIRT", "888", null)));

		assertEquals(1, saved.size());
		verify(communications).logOperation(eq(ErpSyncOperation.IMPORT_ITEM_BARCODES), any(), isNull(),
				eq(ErpCommunicationStatus.WARNING), isNull(),
				eq("Skipping item barcode because parent item NONE not found"), any(), any());
		verify(communications).logOperation(eq(ErpSyncOperation.IMPORT_ITEM_BARCODES), any(), isNull(),
				eq(ErpCommunicationStatus.WARNING), isNull(),
				eq("Skipping item barcode import because no barcode value provided"), any(), any());
		verify(communications, times(2)).logOperation(any(), any(), any(), any(), any(), any(), any(), any());
	}
}
