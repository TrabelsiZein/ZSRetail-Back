package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * 3.1.1: headoffice.supply.source=NONE, a head office that supplies no store. Accepted only with
 * headoffice.stock.enabled=false and without headoffice.storeorders.post; otherwise the startup stops with a message
 * naming both keys, like the other "Invalid combination" checks. HEAD_OFFICE and ERP answer as before. A store never
 * reads the key.
 */
class SupplySourceNoneTest {

	private static MockEnvironment headOffice() {
		return new MockEnvironment().withProperty("node.type", "HEAD_OFFICE");
	}

	private static MockEnvironment none() {
		return headOffice().withProperty("headoffice.stock.enabled", "false")
				.withProperty("headoffice.supply.source", "NONE");
	}

	private static String refusal(MockEnvironment env) {
		return assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(env)).getMessage();
	}

	@Test
	@DisplayName("NONE with headoffice.stock.enabled=false: starts, on a head office without an ERP and with the catalogue only from the ERP; trimmed, any case")
	void accepted() {
		NodeOwnership.resolve(none());
		assertTrue(NodeOwnership.isSupplyNoneSet(none()));
		MockEnvironment catalogueOnly = none().withProperty("ownership.catalogue", "ERP");
		NodeOwnership.resolve(catalogueOnly);
		assertTrue(NodeOwnership.isSupplyNoneSet(catalogueOnly));
		assertTrue(Installation.HEAD_OFFICE_ERP_CATALOGUE_ONLY.isTrue(catalogueOnly));
		MockEnvironment lower = headOffice().withProperty("headoffice.stock.enabled", "false")
				.withProperty("headoffice.supply.source", " none ");
		NodeOwnership.resolve(lower);
		assertTrue(NodeOwnership.isSupplyNoneSet(lower));
		assertFalse(NodeOwnership.isSupplyFromErpSourceSet(lower));
	}

	@Test
	@DisplayName("NONE with headoffice.stock.enabled true or absent: the startup stops naming both keys")
	void refusedWithStock() {
		String expected = "Invalid combination: headoffice.supply.source=NONE with headoffice.stock.enabled=true (or"
				+ " absent). A head office that supplies no store keeps no stock and makes no purchases; set"
				+ " headoffice.stock.enabled to false, or set headoffice.supply.source to HEAD_OFFICE.";
		assertEquals(expected, refusal(headOffice().withProperty("headoffice.supply.source", "NONE")));
		assertEquals(expected, refusal(headOffice().withProperty("headoffice.supply.source", "NONE")
				.withProperty("headoffice.stock.enabled", "true")));
		MockEnvironment withStock = headOffice().withProperty("headoffice.supply.source", "NONE");
		assertThrows(IllegalStateException.class, () -> Installation.HEAD_OFFICE_OWN_SUPPLY.isTrue(withStock));
	}

	@Test
	@DisplayName("NONE with headoffice.storeorders.post: the startup stops naming both keys")
	void refusedWithStoreOrders() {
		assertEquals("Invalid combination: headoffice.supply.source=NONE with headoffice.storeorders.post=MANUAL. A head"
				+ " office that supplies no store takes no store order; remove headoffice.storeorders.post, or set"
				+ " headoffice.supply.source to HEAD_OFFICE.",
				refusal(none().withProperty("headoffice.storeorders.post", "manual")));
	}

	@Test
	@DisplayName("An unknown value names the three values; HEAD_OFFICE, absent and ERP keep their answers")
	void values() {
		assertTrue(refusal(headOffice().withProperty("headoffice.supply.source", "NOTHING"))
				.endsWith(": HEAD_OFFICE (the head office makes the BLs and invoices of its stores), ERP (the stores'"
						+ " invoices are read from the ERP) or NONE (the head office supplies no store)"));
		for (MockEnvironment env : new MockEnvironment[] { headOffice(),
				headOffice().withProperty("headoffice.supply.source", "HEAD_OFFICE") }) {
			NodeOwnership.resolve(env);
			assertFalse(NodeOwnership.isSupplyNoneSet(env));
			assertFalse(NodeOwnership.isSupplyFromErpSourceSet(env));
			assertTrue(Installation.HEAD_OFFICE_OWN_SUPPLY.isTrue(env));
			assertTrue(Installation.HEAD_OFFICE_SUPPLIES_STORES.isTrue(env));
		}
		assertFalse(Installation.HEAD_OFFICE_OWN_SUPPLY.isTrue(none()));
		assertFalse(Installation.HEAD_OFFICE_SUPPLIES_STORES.isTrue(none()));
	}

	@Test
	@DisplayName("A store never reads the key: NONE on a store starts and answers false")
	void storeIgnoresIt() {
		MockEnvironment store = new MockEnvironment().withProperty("headoffice.supply.source", "NONE");
		NodeOwnership.resolve(store);
		assertFalse(NodeOwnership.isSupplyNoneSet(store));
		assertFalse(Installation.HEAD_OFFICE_SUPPLIES_STORES.isTrue(store));
	}
}
