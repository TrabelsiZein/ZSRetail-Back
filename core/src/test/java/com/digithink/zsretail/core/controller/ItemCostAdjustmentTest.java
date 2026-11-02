package com.digithink.zsretail.core.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.AdjustCostRequestDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.StockMovement;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.ItemService;
import com.digithink.zsretail.core.service._BaseService;
import com.digithink.zsretail.core.support.InMemoryItems;
import com.digithink.zsretail.core.support.InMemoryStock;
import com.digithink.zsretail.core.support.TestModes;

/**
 * CMP, the item card: an edit (PUT /item/{id}) keeps the stored cost price (the weighted average, read-only on the form,
 * like the stock); POST /item/{id}/adjust-cost ("Ajuster le CMP") sets it by hand with a mandatory reason, rounded to 3
 * decimals, and writes one COST_ADJUSTMENT movement (quantity 0, the new CMP, "CMP old → new : reason", the user); it
 * answers 403 without the right write:admin-item-cost (checked here, not only in the menu) and where the installation
 * keeps no stock of its own, 400 for a missing reason or a bad cost. Real ItemAPI, ItemService, StockService and
 * StockMovementService over in-memory tables.
 */
class ItemCostAdjustmentTest {

	private InMemoryItems db;
	private InMemoryStock stock;
	private Item item;
	private boolean hasRight;

	@BeforeEach
	void setUp() {
		db = new InMemoryItems(100_000);
		stock = new InMemoryStock(db);
		item = db.item("B001", 10.0, null);
		item.setCostPrice(9.0);
		item.setStockQuantity(BigDecimal.valueOf(10));
		hasRight = true;
	}

	private ItemAPI api(ApplicationModeService mode) throws Exception {
		stock.mode = mode;
		CurrentUserProvider user = new CurrentUserProvider() {
			@Override
			public String getCurrentUserName() {
				return "admin";
			}

			@Override
			public boolean currentUserHasPermission(String permissionKey) {
				return hasRight && ItemAPI.ADJUST_COST_PERMISSION.equals(permissionKey);
			}
		};
		ItemService service = new ItemService();
		set(service, ItemService.class, "itemRepository", stock.itemRepository());
		set(service, ItemService.class, "stockService", stock.stockService());
		set(service, ItemService.class, "stockMovementService", stock.stockMovementService());
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

	private static AdjustCostRequestDTO request(Double cost, String reason) {
		AdjustCostRequestDTO request = new AdjustCostRequestDTO();
		request.setCostPrice(cost);
		request.setReason(reason);
		return request;
	}

	@Test
	@DisplayName("An edit keeps the stored CMP: the value the page sends back (loaded before a reception) is ignored")
	void editKeepsTheCmp() throws Exception {
		Item body = new Item();
		body.setItemCode("B001");
		body.setName("Renamed");
		body.setUnitPrice(12.0);
		body.setCostPrice(4.0);
		item.setCostPrice(7.875); // a reception wrote it after the page was loaded

		assertEquals(200, api(TestModes.standalone()).update(item.getId(), body).getStatusCodeValue());

		Item stored = db.items.get(item.getId());
		assertEquals("Renamed", stored.getName());
		assertEquals(7.875, stored.getCostPrice());
	}

	@Test
	@DisplayName("Ajuster le CMP: the new value rounded to 3 decimals, one COST_ADJUSTMENT movement with old, new, reason and user; the stock untouched")
	void adjusted() throws Exception {
		assertEquals(200, api(TestModes.standalone()).adjustCost(item.getId(), request(8.12345, " Inventaire annuel "))
				.getStatusCodeValue());

		assertEquals(8.123, item.getCostPrice());
		assertEquals(0, BigDecimal.valueOf(10).compareTo(item.getStockQuantity()));
		assertEquals(1, stock.movements.size());
		StockMovement movement = stock.movements.get(0);
		assertEquals(StockMovementType.COST_ADJUSTMENT, movement.getMovementType());
		assertEquals(StockMovementDirection.IN, movement.getDirection());
		assertEquals(0, BigDecimal.ZERO.compareTo(movement.getQuantity()));
		assertEquals(8.123, movement.getUnitPriceHt());
		assertEquals("CMP 9 → 8.123 : Inventaire annuel", movement.getNotes());
		assertEquals("admin", movement.getCreatedBy());
		assertEquals(0, db.itemSaves, "no save of the item");
	}

	@Test
	@DisplayName("An item without a CMP yet: the note says -")
	void firstValue() throws Exception {
		item.setCostPrice(null);
		assertEquals(200, api(TestModes.standalone()).adjustCost(item.getId(), request(5.0, "Valeur initiale"))
				.getStatusCodeValue());
		assertEquals(5.0, item.getCostPrice());
		assertEquals("CMP - → 5 : Valeur initiale", stock.movements.get(0).getNotes());
	}

	@Test
	@DisplayName("Refused, nothing written: no reason or a blank one 400, no cost or below 0 400, unknown item 400, without the right 403")
	void refusals() throws Exception {
		ItemAPI api = api(TestModes.standalone());
		assertEquals(400, api.adjustCost(item.getId(), request(8.0, null)).getStatusCodeValue());
		assertEquals(400, api.adjustCost(item.getId(), request(8.0, "   ")).getStatusCodeValue());
		assertEquals(400, api.adjustCost(item.getId(), request(null, "x")).getStatusCodeValue());
		assertEquals(400, api.adjustCost(item.getId(), request(-1.0, "x")).getStatusCodeValue());
		assertEquals(400, api.adjustCost(item.getId(), null).getStatusCodeValue());
		assertEquals(400, api.adjustCost(999L, request(8.0, "x")).getStatusCodeValue());
		hasRight = false;
		assertEquals(403, api.adjustCost(item.getId(), request(8.0, "x")).getStatusCodeValue());
		assertEquals(9.0, item.getCostPrice());
		assertTrue(stock.movements.isEmpty());
	}

	@Test
	@DisplayName("Where the installation keeps no stock of its own (the ERP keeps it, a head office without stock): 403, nothing written")
	void noStockHere() throws Exception {
		assertEquals(403, api(TestModes.erp()).adjustCost(item.getId(), request(8.0, "x")).getStatusCodeValue());
		MockEnvironment headOfficeWithoutStock = new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")
				.withProperty("headoffice.stock.enabled", "false");
		assertEquals(403, api(TestModes.of(headOfficeWithoutStock)).adjustCost(item.getId(), request(8.0, "x"))
				.getStatusCodeValue());
		assertEquals(200, api(TestModes.of(new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")))
				.adjustCost(item.getId(), request(8.0, "x")).getStatusCodeValue(), "a head office with its stock");
		assertEquals(8.0, item.getCostPrice());
	}
}
