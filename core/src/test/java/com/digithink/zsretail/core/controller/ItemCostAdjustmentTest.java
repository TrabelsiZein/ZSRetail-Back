package com.digithink.zsretail.core.controller;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.dto.AdjustCostRequestDTO;
import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemCostHistory;
import com.digithink.zsretail.core.model.StockMovement;
import com.digithink.zsretail.core.model.enumeration.CostChangeSource;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
import com.digithink.zsretail.core.repository.ItemCostHistoryRepository;
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
 * StockMovementService over in-memory tables. 3.1.2: the change in the cost history (MANUAL, old and new, the user, the
 * reason as its note), and
 * GET /item/{id}/cost-history: 403 without a cost right (write:admin-item-cost or read:admin-margins) or where the
 * installation keeps no stock of its own, else the item's changes newest first.
 */
class ItemCostAdjustmentTest {

	private InMemoryItems db;
	private InMemoryStock stock;
	private Item item;
	private boolean hasRight;
	/** 3.1.2: the role has read:admin-margins (and not the right to adjust). */
	private boolean readsMargins;

	@BeforeEach
	void setUp() {
		db = new InMemoryItems(100_000);
		stock = new InMemoryStock(db);
		item = db.item("B001", 10.0, null);
		item.setCostPrice(9.0);
		item.setStockQuantity(BigDecimal.valueOf(10));
		hasRight = true;
		readsMargins = false;
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
				return hasRight && ItemAPI.ADJUST_COST_PERMISSION.equals(permissionKey)
						|| readsMargins && ItemAPI.MARGINS_PERMISSION.equals(permissionKey);
			}
		};
		ItemService service = new ItemService();
		set(service, ItemService.class, "itemRepository", stock.itemRepository());
		set(service, ItemService.class, "stockService", stock.stockService());
		set(service, ItemService.class, "stockMovementService", stock.stockMovementService());
		set(service, ItemService.class, "costHistoryService", stock.costHistoryService());
		set(service, _BaseService.class, "currentUserProvider", user);
		ItemAPI api = new ItemAPI();
		set(api, _BaseController.class, "service", service);
		set(api, ItemAPI.class, "applicationModeService", mode);
		set(api, ItemAPI.class, "currentUserProvider", user);
		set(api, ItemAPI.class, "costHistoryRepository", proxy(ItemCostHistoryRepository.class, (method, args) -> {
			if ("findByItemIdOrderByIdDesc".equals(method)) {
				List<ItemCostHistory> rows = stock.costHistory.stream().filter(h -> h.getItemId().equals(args[0]))
						.sorted((a, b) -> Long.compare(b.getId(), a.getId())).collect(Collectors.toList());
				return rows.subList(0, Math.min(rows.size(), ((Pageable) args[1]).getPageSize()));
			}
			return UNHANDLED;
		}));
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
		assertEquals(1, stock.costHistory.size());
		ItemCostHistory row = stock.costHistory.get(0);
		assertEquals(CostChangeSource.MANUAL, row.getSource());
		assertEquals(9.0, row.getOldCost());
		assertEquals(8.123, row.getNewCost());
		assertEquals("admin", row.getChangedBy());
		assertNull(row.getDocumentNumber());
		assertEquals("Inventaire annuel", row.getNote(), "the reason typed, as the movement's");
	}

	@Test
	@DisplayName("An item without a CMP yet (3.1.2, the initial cost): the note says -, the history row null → the value, the reason kept")
	void firstValue() throws Exception {
		item.setCostPrice(null);
		assertEquals(200, api(TestModes.standalone()).adjustCost(item.getId(), request(5.0, "Coût initial"))
				.getStatusCodeValue());
		assertEquals(5.0, item.getCostPrice());
		assertEquals("CMP - → 5 : Coût initial", stock.movements.get(0).getNotes());
		assertEquals(1, stock.costHistory.size());
		ItemCostHistory row = stock.costHistory.get(0);
		assertEquals(CostChangeSource.MANUAL, row.getSource());
		assertNull(row.getOldCost(), "no CMP before");
		assertEquals(5.0, row.getNewCost());
		assertEquals("admin", row.getChangedBy());
		assertEquals("Coût initial", row.getNote());
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
		assertTrue(stock.costHistory.isEmpty());
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

	@Test
	@DisplayName("Ajuster le CMP to the same value: the movement as before, no cost history row (nothing changed)")
	void sameValueNoHistory() throws Exception {
		assertEquals(200, api(TestModes.standalone()).adjustCost(item.getId(), request(9.0, "Contrôle")).getStatusCodeValue());
		assertEquals(1, stock.movements.size());
		assertTrue(stock.costHistory.isEmpty());
	}

	@Test
	@DisplayName("Cost history: a reason longer than the note column is cut to 500")
	void longReasonCut() throws Exception {
		StringBuilder reason = new StringBuilder();
		while (reason.length() < 600) {
			reason.append("Recalcul ");
		}
		assertEquals(200, api(TestModes.standalone()).adjustCost(item.getId(), request(8.0, reason.toString()))
				.getStatusCodeValue());
		assertEquals(ItemCostHistory.NOTE_LENGTH, stock.costHistory.get(0).getNote().length());
	}

	@Test
	@DisplayName("GET /item/{id}/cost-history: newest first with a cost right (either), 403 without one or without stock here")
	@SuppressWarnings("unchecked")
	void costHistory() throws Exception {
		ItemAPI api = api(TestModes.standalone());
		api.adjustCost(item.getId(), request(8.0, "a"));
		api.adjustCost(item.getId(), request(7.0, "b"));
		ResponseEntity<?> answer = api.costHistory(item.getId(), 20);
		assertEquals(200, answer.getStatusCodeValue());
		List<ItemCostHistory> rows = (List<ItemCostHistory>) answer.getBody();
		assertEquals(Arrays.asList(7.0, 8.0), rows.stream().map(ItemCostHistory::getNewCost).collect(Collectors.toList()));
		assertEquals(1, ((List<?>) api.costHistory(item.getId(), 1).getBody()).size(), "size");

		hasRight = false;
		readsMargins = true;
		assertEquals(200, api.costHistory(item.getId(), 20).getStatusCodeValue(), "read:admin-margins is enough");
		readsMargins = false;
		assertEquals(403, api.costHistory(item.getId(), 20).getStatusCodeValue());
		hasRight = true;
		assertEquals(403, api(TestModes.erp()).costHistory(item.getId(), 20).getStatusCodeValue());
		MockEnvironment headOfficeWithoutStock = new MockEnvironment().withProperty("node.type", "HEAD_OFFICE")
				.withProperty("headoffice.stock.enabled", "false");
		assertEquals(403, api(TestModes.of(headOfficeWithoutStock)).costHistory(item.getId(), 20).getStatusCodeValue());
	}
}
