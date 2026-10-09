package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.headoffice.dto.StockPointDTO;
import com.digithink.zsretail.headoffice.model.HoStockPoint;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.support.InMemoryCatalogue;

/**
 * Stock points, step 1: the list of the points de stock. Code trimmed, uppercase, unique and final; placed last; a point
 * used by a store cannot be deactivated or deleted; a point with items cannot be deleted; the order is set as a whole.
 * In-memory tables, no Spring context.
 */
class HoStockPointServiceTest {

	private InMemoryCatalogue ho;
	private HoStockPointService service;

	@BeforeEach
	void setUp() {
		ho = new InMemoryCatalogue(1);
		service = new HoStockPointService(ho.stockPointRepository(), ho.stockPointItemRepository(), ho.storeRepository());
	}

	private static StockPointDTO input(String code, String name, Boolean active) {
		StockPointDTO dto = new StockPointDTO();
		dto.setCode(code);
		dto.setName(name);
		dto.setActive(active);
		return dto;
	}

	private List<String> codes() {
		return service.findAll().stream().map(StockPointDTO::getCode).collect(Collectors.toList());
	}

	@Test
	@DisplayName("Created: code trimmed and uppercase, active by default, last in the list, counts at 0")
	void create() {
		StockPointDTO first = service.create(input(" franchise ", " Franchise ", null));
		assertEquals("FRANCHISE", first.getCode());
		assertEquals("Franchise", first.getName());
		assertTrue(first.getActive());
		assertEquals(1, first.getSortOrder());
		assertEquals(0L, first.getItemCount());
		assertEquals(0L, first.getStoreCount());
		StockPointDTO second = service.create(input("MAG01", "Store 1", false));
		assertFalse(second.getActive());
		assertEquals(2, second.getSortOrder());
		assertEquals(Arrays.asList("FRANCHISE", "MAG01"), codes());
	}

	@Test
	@DisplayName("Create refused: code or name missing, code too long (400), code that exists at any case (409)")
	void createRefused() {
		assertEquals(HoStockPointService.CODE_REQUIRED,
				assertThrows(IllegalArgumentException.class, () -> service.create(input("  ", "X", null))).getMessage());
		assertEquals(HoStockPointService.NAME_REQUIRED,
				assertThrows(IllegalArgumentException.class, () -> service.create(input("A", " ", null))).getMessage());
		assertThrows(IllegalArgumentException.class,
				() -> service.create(input("ABCDEFGHIJKLMNOPQRSTU", "Too long", null)));
		service.create(input("FRANCHISE", "Franchise", null));
		assertThrows(IllegalStateException.class, () -> service.create(input("franchise", "Again", null)));
		assertEquals(1, ho.stockPoints.size());
	}

	@Test
	@DisplayName("Renamed and deactivated; the code cannot change (400); unknown: empty")
	void update() {
		Long id = service.create(input("FRANCHISE", "Franchise", null)).getId();
		StockPointDTO renamed = service.update(id, input(null, "Franchise network", null)).get();
		assertEquals("Franchise network", renamed.getName());
		assertTrue(renamed.getActive());
		assertFalse(service.update(id, input("franchise", null, false)).get().getActive()); // same code at any case
		assertTrue(service.update(id, input(null, null, true)).get().getActive());
		assertEquals(HoStockPointService.CODE_IS_FINAL, assertThrows(IllegalArgumentException.class,
				() -> service.update(id, input("OTHER", null, null))).getMessage());
		assertThrows(IllegalArgumentException.class, () -> service.update(id, input(null, "  ", null)));
		assertFalse(service.update(999L, input(null, "X", null)).isPresent());
	}

	@Test
	@DisplayName("A point used by a store: deactivation and deletion refused (409); renaming still allowed")
	void usedByStore() {
		Long id = service.create(input("MAG01", "Store 1", null)).getId();
		Store store = ho.store("A");
		store.setStockPointId(id);
		assertEquals(1L, service.findById(id).get().getStoreCount());
		assertEquals(String.format(HoStockPointService.USED_BY_STORE, 1), assertThrows(IllegalStateException.class,
				() -> service.update(id, input(null, null, false))).getMessage());
		assertThrows(IllegalStateException.class, () -> service.delete(id));
		assertEquals("Store 1 bis", service.update(id, input(null, "Store 1 bis", null)).get().getName());
		assertTrue(ho.stockPoints.get(id).getActive());
	}

	@Test
	@DisplayName("Deleted only without items and stores; a point with items: 409, deactivate it instead")
	void delete() {
		Long empty = service.create(input("EMPTY", "Empty", null)).getId();
		assertTrue(service.delete(empty));
		assertFalse(service.delete(empty));
		Long id = service.create(input("FRANCHISE", "Franchise", null)).getId();
		HoStockPoint point = ho.stockPoints.get(id);
		ho.stockPointItem(point, ho.item("B001", 10.0, ho.subFamily("SF1", ho.family("F1"))), 10.0);
		assertEquals(1L, service.findById(id).get().getItemCount());
		assertEquals(String.format(HoStockPointService.HAS_ITEMS, 1),
				assertThrows(IllegalStateException.class, () -> service.delete(id)).getMessage());
		assertFalse(service.update(id, input(null, null, false)).get().getActive());
	}

	@Test
	@DisplayName("Order: every id once gives 1, 2, 3...; a missing, unknown or repeated id is refused (400)")
	void reorder() {
		Long a = service.create(input("A", "A", null)).getId();
		Long b = service.create(input("B", "B", null)).getId();
		Long c = service.create(input("C", "C", null)).getId();
		List<StockPointDTO> list = service.reorder(Arrays.asList(c, a, b));
		assertEquals(Arrays.asList("C", "A", "B"), list.stream().map(StockPointDTO::getCode).collect(Collectors.toList()));
		assertEquals(Arrays.asList(1, 2, 3), list.stream().map(StockPointDTO::getSortOrder).collect(Collectors.toList()));
		assertThrows(IllegalArgumentException.class, () -> service.reorder(Arrays.asList(c, a)));
		assertThrows(IllegalArgumentException.class, () -> service.reorder(Arrays.asList(c, a, a)));
		assertThrows(IllegalArgumentException.class, () -> service.reorder(Arrays.asList(c, a, 999L)));
		assertThrows(IllegalArgumentException.class, () -> service.reorder(null));
		assertEquals(Arrays.asList("C", "A", "B"), codes());
		assertEquals(4, service.create(input("D", "D", null)).getSortOrder());
	}
}
