package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.core.support.TestModes;
import com.digithink.zsretail.support.Installations;

/**
 * Item variants (ZEN), step 11a: ApplicationModeService.keepsOwnStock (the stock columns of the "NAV items" page) on the
 * deploy files of configs/customers, read over their type file as at a start: no new key, the answer of
 * ownership.supply and headoffice.stock.enabled.
 */
class KeepsOwnStockTest {

	private static boolean keepsOwnStock(String machineFile) {
		return TestModes.of(Installations.machine(machineFile)).keepsOwnStock();
	}

	@Test
	@DisplayName("ZEN head office (headoffice.stock.enabled=false): no stock")
	void zenHeadOffice() {
		assertFalse(keepsOwnStock(Installations.ZEN_HEAD_OFFICE));
	}

	@Test
	@DisplayName("ZEN franchise store (erp.connector=none, supply from the head office): its own stock")
	void zenFranchiseStore() {
		assertTrue(keepsOwnStock("customers/zen-store.properties"));
	}

	@Test
	@DisplayName("A future ZEN own store (catalogue, customers and supply the ERP's): no stock")
	void zenOwnStore() {
		Properties file = new Properties();
		file.setProperty("ownership.catalogue", "ERP");
		file.setProperty("ownership.customers", "ERP");
		file.setProperty("ownership.supply", "ERP");
		file.setProperty("sales.upstream", "ERP");
		assertFalse(TestModes.of(Installations.over("store", file)).keepsOwnStock());
	}

	@Test
	@DisplayName("EMTOP store (supply the ERP's): no stock; the two type files alone keep theirs")
	void others() {
		assertFalse(keepsOwnStock("customers/erp-prod.properties"));
		assertTrue(TestModes.of(Installations.type("store")).keepsOwnStock());
		assertTrue(TestModes.of(Installations.type("headoffice")).keepsOwnStock());
	}
}
