package com.digithink.zsretail.controller;

import static com.digithink.zsretail.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.dto.AdjustStockRequestDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.StockMovement;
import com.digithink.zsretail.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.model.enumeration.StockMovementType;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.StockMovementRepository;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.ItemService;
import com.digithink.zsretail.service.StockMovementService;
import com.digithink.zsretail.service.StockService;
import com.digithink.zsretail.service._BaseService;
import com.digithink.zsretail.support.TestModes;

/**
 * Head office plan, step 7A (decision 6): POST /item/{id}/adjust-stock changes the quantity exactly as before and now
 * also writes its ADJUSTMENT_IN or ADJUSTMENT_OUT movement, with the reason as note; outside standalone mode it is
 * still refused and writes nothing. An item edit (PUT /item/{id}) keeps the stored stock. Real ItemAPI, ItemService, StockService and StockMovementService over stubs.
 */
class ItemStockAdjustmentTest {

	private final Map<Long, Item> items = new LinkedHashMap<>();
	private final List<StockMovement> movements = new ArrayList<>();
	private final List<String> stockUpdates = new ArrayList<>();
	private Item item;

	@BeforeEach
	void setUp() {
		item = new Item();
		item.setId(7L);
		item.setItemCode("B001");
		item.setStockQuantity(10);
		items.put(7L, item);
	}

	private ItemAPI api(boolean standalone) throws Exception {
		ApplicationModeService mode = TestModes.of(standalone);
		ItemRepository itemRepository = proxy(ItemRepository.class, (method, args) -> {
			switch (method) {
				case "findById":
					return Optional.ofNullable(items.get(args[0]));
				case "save": {
					Item saved = (Item) args[0];
					items.put(saved.getId(), saved);
					return saved;
				}
				case "addToStockQuantity": {
					Item row = items.get(args[0]);
					int delta = (Integer) args[1];
					row.setStockQuantity((row.getStockQuantity() == null ? 0 : row.getStockQuantity()) + delta);
					stockUpdates.add(row.getItemCode() + " " + delta);
					return 1;
				}
				default:
					return UNHANDLED;
			}
		});
		StockMovementRepository movementRepository = proxy(StockMovementRepository.class, (method, args) -> {
			if ("save".equals(method)) {
				movements.add((StockMovement) args[0]);
				return args[0];
			}
			return UNHANDLED;
		});
		StockService stock = new StockService();
		set(stock, StockService.class, "applicationModeService", mode);
		set(stock, StockService.class, "itemRepository", itemRepository);
		StockMovementService stockMovements = new StockMovementService();
		set(stockMovements, StockMovementService.class, "applicationModeService", mode);
		set(stockMovements, StockMovementService.class, "stockMovementRepository", movementRepository);
		set(stockMovements, StockMovementService.class, "itemRepository", itemRepository);
		ItemService service = new ItemService();
		set(service, ItemService.class, "itemRepository", itemRepository);
		set(service, ItemService.class, "applicationModeService", mode);
		set(service, ItemService.class, "stockService", stock);
		set(service, ItemService.class, "stockMovementService", stockMovements);
		set(service, _BaseService.class, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}
		});
		ItemAPI api = new ItemAPI();
		set(api, _BaseController.class, "service", service);
		set(api, ItemAPI.class, "applicationModeService", mode);
		return api;
	}

	private static AdjustStockRequestDTO request(Integer delta, String reason) {
		AdjustStockRequestDTO request = new AdjustStockRequestDTO();
		request.setDelta(delta);
		request.setReason(reason);
		return request;
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test
	@DisplayName("An edit keeps the stored stock: the quantity the page sends back (read-only there) is ignored, the other fields saved")
	void editKeepsStoredStock() throws Exception {
		Item body = new Item();
		body.setItemCode("B001");
		body.setName("Renamed");
		body.setUnitPrice(12.0);
		body.setStockQuantity(3); // loaded before a sale took the stock from 13 to 10

		assertEquals(200, api(true).update(7L, body).getStatusCodeValue());

		Item stored = items.get(7L);
		assertEquals("Renamed", stored.getName());
		assertEquals(10, stored.getStockQuantity());
		assertTrue(stockUpdates.isEmpty());
		assertTrue(movements.isEmpty());
	}

	@Test
	@DisplayName("A positive delta: stock +5 in one update, one ADJUSTMENT_IN movement of 5 with the reason")
	void positiveDelta() throws Exception {
		assertEquals(200, api(true).adjustStock(7L, request(5, "count")).getStatusCodeValue());

		assertEquals(15, item.getStockQuantity());
		assertEquals(1, stockUpdates.size(), "one update, as before");
		assertEquals(1, movements.size());
		StockMovement movement = movements.get(0);
		assertEquals(StockMovementType.ADJUSTMENT_IN, movement.getMovementType());
		assertEquals(StockMovementDirection.IN, movement.getDirection());
		assertEquals(5, movement.getQuantity());
		assertEquals("ADJUSTMENT", movement.getReferenceType());
		assertEquals("COUNT", movement.getNotes());
		assertEquals(item, movement.getItem());
	}

	@Test
	@DisplayName("A negative delta: stock -3, one ADJUSTMENT_OUT movement of 3; an unknown reason is CORRECTION as before")
	void negativeDelta() throws Exception {
		assertEquals(200, api(true).adjustStock(7L, request(-3, "whatever")).getStatusCodeValue());

		assertEquals(7, item.getStockQuantity());
		assertEquals(1, movements.size());
		assertEquals(StockMovementType.ADJUSTMENT_OUT, movements.get(0).getMovementType());
		assertEquals(StockMovementDirection.OUT, movements.get(0).getDirection());
		assertEquals(3, movements.get(0).getQuantity());
		assertEquals("CORRECTION", movements.get(0).getNotes());
	}

	@Test
	@DisplayName("Refusals as before: delta 0 or missing 400, unknown item 400, ERP mode 403; nothing written")
	void refusalsWriteNothing() throws Exception {
		assertEquals(400, api(true).adjustStock(7L, request(0, "COUNT")).getStatusCodeValue());
		assertEquals(400, api(true).adjustStock(7L, request(null, "COUNT")).getStatusCodeValue());
		assertEquals(400, api(true).adjustStock(99L, request(2, "COUNT")).getStatusCodeValue());
		assertEquals(403, api(false).adjustStock(7L, request(2, "COUNT")).getStatusCodeValue());

		assertEquals(10, item.getStockQuantity());
		assertTrue(stockUpdates.isEmpty());
		assertTrue(movements.isEmpty());
	}
}
