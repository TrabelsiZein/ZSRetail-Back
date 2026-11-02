package com.digithink.zsretail.store.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.support.TestModes;

/**
 * 3.1.2, the store's Articles page: GET /item-catalogue gives the weighted average cost (withCost, costMin / costMax)
 * only where the store keeps its own stock and to a role with write:admin-item-cost or read:admin-margins; checked by
 * the API, whatever the page shows.
 */
class StoreItemCatalogueCostTest {

	private static StoreItemCatalogueAPI api(ApplicationModeService mode, String... permissions) {
		AppRole role = new AppRole();
		role.setPermissions(new HashSet<>(Arrays.asList(permissions)));
		UserAccount user = new UserAccount();
		user.setUsername("user");
		user.setAppRole(role);
		return new StoreItemCatalogueAPI(null, mode, new CurrentUserProvider() {
			@Override
			public UserAccount getCurrentUser() {
				return user;
			}
		});
	}

	@Test
	@DisplayName("Stock kept here: the cost with write:admin-item-cost or read:admin-margins, not without either")
	void rights() {
		assertTrue(api(TestModes.standalone(), "write:admin-item-cost").withCost());
		assertTrue(api(TestModes.standalone(), "read:admin-margins").withCost());
		assertFalse(api(TestModes.standalone(), "read:admin-item-management", "read:pos").withCost());
		assertFalse(api(TestModes.standalone()).withCost());
	}

	@Test
	@DisplayName("Where the ERP keeps the stock: never, even with both rights")
	void erpKeepsTheStock() {
		assertFalse(api(TestModes.erp(), "write:admin-item-cost", "read:admin-margins").withCost());
	}

	@Test
	@DisplayName("No user (no provider): no cost")
	void noUser() {
		assertFalse(new StoreItemCatalogueAPI(null, TestModes.standalone(), null).withCost());
	}
}
