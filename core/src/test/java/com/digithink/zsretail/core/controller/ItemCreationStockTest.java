package com.digithink.zsretail.core.controller;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCostHistory;
import com.digithink.zsretail.core.model.StockMovement;
import com.digithink.zsretail.core.model.enumeration.CostChangeSource;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
import com.digithink.zsretail.core.repository.ItemCostHistoryRepository;
import com.digithink.zsretail.core.repository.ItemRepository;
import com.digithink.zsretail.core.repository.StockMovementRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.CostHistoryService;
import com.digithink.zsretail.core.service.ItemService;
import com.digithink.zsretail.core.service.StockMovementService;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.core.support.TestModes;

/**
 * 3.1.2, the stock value at a date: POST /item with a stock writes its OPENING_STOCK movement (direction IN, the
 * quantity, note "Création de l'article", created by the user) with the item; without a stock or with 0, none; with an ERP,
 * none. Real ItemAPI, ItemService and StockMovementService over stubs.
 */
class ItemCreationStockTest {

	private final Map<Long, Item> items = new LinkedHashMap<>();
	private final List<StockMovement> movements = new ArrayList<>();
	private final List<ItemCostHistory> history = new ArrayList<>();
	private long nextId = 1;

	private ItemAPI api(ApplicationModeService mode) throws Exception {
		ItemRepository itemRepository = proxy(ItemRepository.class, (method, args) -> {
			switch (method) {
				case "findById":
					return Optional.ofNullable(items.get(args[0]));
				case "save": {
					Item saved = (Item) args[0];
					if (saved.getId() == null) {
						saved.setId(nextId++);
					}
					items.put(saved.getId(), saved);
					return saved;
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
		CurrentUserProvider user = new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "manager";
			}
		};
		StockMovementService stockMovements = new StockMovementService();
		set(stockMovements, StockMovementService.class, "applicationModeService", mode);
		set(stockMovements, StockMovementService.class, "stockMovementRepository", movementRepository);
		set(stockMovements, StockMovementService.class, "itemRepository", itemRepository);
		ItemService service = new ItemService();
		set(service, ItemService.class, "itemRepository", itemRepository);
		set(service, ItemService.class, "stockMovementService", stockMovements);
		CostHistoryService costHistory = new CostHistoryService();
		set(costHistory, CostHistoryService.class, "applicationModeService", mode);
		set(costHistory, CostHistoryService.class, "repository", proxy(ItemCostHistoryRepository.class, (method, args) -> {
			if ("save".equals(method)) {
				history.add((ItemCostHistory) args[0]);
				return args[0];
			}
			return UNHANDLED;
		}));
		set(service, ItemService.class, "costHistoryService", costHistory);
		set(service, _BaseService.class, "currentUserProvider", user);
		ItemAPI api = new ItemAPI();
		set(api, _BaseController.class, "service", service);
		set(api, ItemAPI.class, "applicationModeService", mode);
		set(api, ItemAPI.class, "currentUserProvider", user);
		return api;
	}

	private static void set(Object target, Class<?> declaring, String name, Object value) throws Exception {
		Field field = declaring.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	private static Item item(String code, BigDecimal stock) {
		Item item = new Item();
		item.setItemCode(code);
		item.setName(code);
		item.setUnitPrice(10.0);
		item.setStockQuantity(stock);
		return item;
	}

	@Test
	@DisplayName("POST /item with a stock: one OPENING_STOCK IN of that quantity, the note, the user")
	void openingStock() throws Exception {
		ResponseEntity<?> response = api(TestModes.standalone()).create(item("N1", new BigDecimal("4")));
		assertEquals(201, response.getStatusCodeValue());
		assertEquals(1, movements.size());
		StockMovement movement = movements.get(0);
		assertEquals(StockMovementType.OPENING_STOCK, movement.getMovementType());
		assertEquals(StockMovementDirection.IN, movement.getDirection());
		assertEquals(0, new BigDecimal("4").compareTo(movement.getQuantity()));
		assertEquals("Création de l'article", movement.getNotes());
		assertEquals("OPENING_STOCK", movement.getReferenceType());
		assertEquals("manager", movement.getCreatedBy());
		assertEquals(((Item) response.getBody()).getId(), movement.getItem().getId());
	}

	@Test
	@DisplayName("POST /item without a stock or with 0: no movement")
	void noStock() throws Exception {
		ItemAPI api = api(TestModes.standalone());
		assertEquals(201, api.create(item("N1", null)).getStatusCodeValue());
		assertEquals(201, api.create(item("N2", BigDecimal.ZERO)).getStatusCodeValue());
		assertTrue(movements.isEmpty());
		assertTrue(history.isEmpty(), "no cost: no cost history");
	}

	@Test
	@DisplayName("POST /item with a cost: its first cost history row (ITEM_CREATION, no old cost, the stock, the user)")
	void costAtCreation() throws Exception {
		Item body = item("N1", new BigDecimal("4"));
		body.setCostPrice(6.5);
		Item created = (Item) api(TestModes.standalone()).create(body).getBody();
		assertEquals(1, history.size());
		ItemCostHistory row = history.get(0);
		assertEquals(created.getId(), row.getItemId());
		assertEquals(CostChangeSource.ITEM_CREATION, row.getSource());
		assertNull(row.getOldCost());
		assertEquals(6.5, row.getNewCost());
		assertEquals(0, new BigDecimal("4").compareTo(row.getQtyIn()));
		assertEquals("manager", row.getChangedBy());
	}

	@Test
	@DisplayName("A negative stock at creation: OPENING_STOCK OUT of its absolute value")
	void negativeStock() throws Exception {
		api(TestModes.standalone()).create(item("N1", new BigDecimal("-2")));
		assertEquals(1, movements.size());
		assertEquals(StockMovementDirection.OUT, movements.get(0).getDirection());
		assertEquals(0, new BigDecimal("2").compareTo(movements.get(0).getQuantity()));
	}

	@Test
	@DisplayName("With an ERP (supply and catalogue): POST /item refused as before, an opening stock writes nothing")
	void withErp() throws Exception {
		ApplicationModeService mode = TestModes.erp();
		assertEquals(403, api(mode).create(item("N1", new BigDecimal("4"))).getStatusCodeValue());
		StockMovementService stockMovements = new StockMovementService();
		set(stockMovements, StockMovementService.class, "applicationModeService", mode);
		stockMovements.recordOpeningStock(1L, new BigDecimal("4"), "x", "manager");
		assertTrue(movements.isEmpty());
	}
}
