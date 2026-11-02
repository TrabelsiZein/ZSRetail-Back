package com.digithink.zsretail.store.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;

import com.digithink.zsretail.core.config.ApplicationModeService;
import com.digithink.zsretail.core.model.AppRole;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.repository.ItemCatalogueQuery;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.core.service.ItemCatalogueService;
import com.digithink.zsretail.core.support.TestModes;

/**
 * 3.1.2, the store's Articles page: GET /item-catalogue gives the weighted average cost (withCost, costMin / costMax)
 * only where the store keeps its own stock and to a role with write:admin-item-cost or read:admin-margins; checked by
 * the API, whatever the page shows. The filter "Sans coût" (withoutCost) on the same rule.
 */
class StoreItemCatalogueCostTest {

	private ItemCatalogueService lastService;

	private static StoreItemCatalogueAPI api(ApplicationModeService mode, String... permissions) {
		return api(null, mode, permissions);
	}

	private static StoreItemCatalogueAPI api(ItemCatalogueService service, ApplicationModeService mode,
			String... permissions) {
		AppRole role = new AppRole();
		role.setPermissions(new HashSet<>(Arrays.asList(permissions)));
		UserAccount user = new UserAccount();
		user.setUsername("user");
		user.setAppRole(role);
		return new StoreItemCatalogueAPI(service, mode, new CurrentUserProvider() {
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

	@Test
	@DisplayName("Sans coût: the items without a CMP (services, discount items and packs left out), only with the cost; ignored otherwise")
	void withoutCostFilter() {
		assertEquals(Collections.singletonList(StoreItemCatalogueAPI.WITHOUT_COST),
				conditions(api(service(), TestModes.standalone(), "write:admin-item-cost"), true));
		assertTrue(conditions(api(service(), TestModes.standalone(), "write:admin-item-cost"), null).isEmpty(),
				"not asked: no condition");
		assertTrue(conditions(api(service(), TestModes.standalone(), "read:admin-item-management"), true).isEmpty(),
				"without a cost right: the filter ignored, the costs not revealed");
		assertTrue(conditions(api(service(), TestModes.erp(), "write:admin-item-cost"), true).isEmpty(),
				"where the ERP keeps the stock: ignored");
		assertTrue(StoreItemCatalogueAPI.WITHOUT_COST.contains("NOT IN ('SERVICE', 'DISCOUNT', 'PACKAGE')"));
	}

	private ItemCatalogueService service() {
		lastService = mock(ItemCatalogueService.class);
		when(lastService.page(any(), anyInt(), anyInt())).thenReturn(new PageImpl<>(Collections.emptyList()));
		return lastService;
	}

	/** The item conditions the page asked the catalogue for. */
	private List<String> conditions(StoreItemCatalogueAPI api, Boolean withoutCost) {
		api.page(0, 20, null, null, null, null, null, null, null, null, null, null, withoutCost);
		ArgumentCaptor<ItemCatalogueQuery.Criteria> criteria = ArgumentCaptor.forClass(ItemCatalogueQuery.Criteria.class);
		verify(lastService).page(criteria.capture(), anyInt(), anyInt());
		return criteria.getValue().getItemConditions();
	}
}
