package com.digithink.zsretail.core.controller;

import static com.digithink.zsretail.core.support.InMemoryLoyalty.UNHANDLED;
import static com.digithink.zsretail.core.support.InMemoryLoyalty.proxy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import com.digithink.zsretail.core.dto.ItemStockMovementDTO;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.StockMovement;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model.enumeration.StockMovementDirection;
import com.digithink.zsretail.core.model.enumeration.StockMovementType;
import com.digithink.zsretail.core.repository.StockMovementRepository;
import com.digithink.zsretail.core.security.CurrentUserProvider;

/**
 * 3.1.2, the store's item card: GET /item/{id}/stock-movements, the item's last movements newest first. The unit prices
 * and the CMP adjustments (their notes name the costs) only for a role with write:admin-item-cost or
 * read:admin-margins; the size between 1 and 100.
 */
class ItemStockMovementsTest {

	private final List<Pageable> asked = new ArrayList<>();

	private static StockMovement movement(long id, StockMovementType type, String direction, String quantity, Double price,
			String notes) {
		StockMovement m = new StockMovement();
		m.setId(id);
		m.setCreatedAt(LocalDateTime.of(2026, 11, 2, 10, 0).plusMinutes(id));
		m.setMovementType(type);
		m.setDirection(StockMovementDirection.valueOf(direction));
		m.setQuantity(new BigDecimal(quantity));
		m.setUnitPriceHt(price);
		m.setReferenceType("SALE");
		m.setReferenceId(40L + id);
		m.setNotes(notes);
		m.setCreatedBy("cashier");
		return m;
	}

	/** Newest first, as the repository gives them. */
	private final List<StockMovement> stored = Arrays.asList(
			movement(3, StockMovementType.COST_ADJUSTMENT, "IN", "0", 8.5, "CMP 7.2 → 8.5 : inventaire"),
			movement(2, StockMovementType.SALE, "OUT", "2", 12.0, null),
			movement(1, StockMovementType.PURCHASE_RECEPTION, "IN", "10", 7.2, null));

	private ItemAPI api(String... permissions) throws Exception {
		AppRole role = new AppRole();
		role.setPermissions(new HashSet<>(Arrays.asList(permissions)));
		UserAccount user = new UserAccount();
		user.setUsername("user");
		user.setAppRole(role);
		ItemAPI api = new ItemAPI();
		set(api, "currentUserProvider", new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return user;
			}
		});
		set(api, "stockMovementRepository", proxy(StockMovementRepository.class, (method, args) -> {
			if (!"findByItem_IdOrderByIdDesc".equals(method)) {
				return UNHANDLED;
			}
			asked.add((Pageable) args[1]);
			return 7L == (Long) args[0] ? stored : new ArrayList<>();
		}));
		return api;
	}

	private static void set(Object target, String name, Object value) throws Exception {
		Field field = ItemAPI.class.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}

	@Test
	@DisplayName("With a cost right: every movement newest first, the prices and the CMP adjustment with its notes")
	void withCostRight() throws Exception {
		for (String right : new String[] { "write:admin-item-cost", "read:admin-margins" }) {
			List<ItemStockMovementDTO> rows = api(right).stockMovements(7L, 20).getBody();
			assertEquals(Arrays.asList(3L, 2L, 1L), rows.stream().map(ItemStockMovementDTO::getId).collect(Collectors.toList()));
			ItemStockMovementDTO cost = rows.get(0);
			assertEquals("COST_ADJUSTMENT", cost.getType());
			assertEquals("CMP 7.2 → 8.5 : inventaire", cost.getNotes());
			assertEquals(7.2, rows.get(2).getUnitPriceHt(), 0.0);
			assertEquals("OUT", rows.get(1).getDirection());
			assertEquals(0, new BigDecimal("2").compareTo(rows.get(1).getQuantity()));
			assertEquals("cashier", rows.get(1).getCreatedBy());
			assertEquals(Long.valueOf(42L), rows.get(1).getReferenceId());
		}
	}

	@Test
	@DisplayName("Without a cost right: no CMP adjustment and no price")
	void withoutCostRight() throws Exception {
		List<ItemStockMovementDTO> rows = api("read:admin-item-management").stockMovements(7L, 20).getBody();
		assertEquals(Arrays.asList("SALE", "PURCHASE_RECEPTION"),
				rows.stream().map(ItemStockMovementDTO::getType).collect(Collectors.toList()));
		rows.forEach(row -> assertNull(row.getUnitPriceHt(), row.getType()));
		assertFalse(ItemAPI.mayReadCosts(null), "no user: no cost");
		assertTrue(ItemAPI.mayReadCosts(new CurrentUserProvider() {
			@Override
			public boolean currentUserHasPermission(String permissionKey) {
				return "read:admin-margins".equals(permissionKey);
			}
		}));
	}

	@Test
	@DisplayName("The size: 20 by default, at least 1, at most 100; the first page; an item without movement: empty")
	void size() throws Exception {
		ItemAPI api = api("write:admin-item-cost");
		api.stockMovements(7L, 20);
		api.stockMovements(7L, 0);
		api.stockMovements(7L, 5000);
		assertEquals(Arrays.asList(20, 1, 100), asked.stream().map(Pageable::getPageSize).collect(Collectors.toList()));
		asked.forEach(page -> assertEquals(0, page.getPageNumber()));
		assertTrue(api.stockMovements(8L, 20).getBody().isEmpty());
	}
}
