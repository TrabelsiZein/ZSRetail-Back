package com.digithink.zsretail.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.digithink.zsretail.erp.api.ErpAbilities;
import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.support.Installations;

/**
 * 3.1.1, headoffice.storeorders.post (docs/modules/store-orders.md): absent, the head office takes no store order (the
 * two questions false: no bean); MANUAL or ERP, any case, it takes them; ERP needs a connector with
 * ErpAbility.STORE_ORDERS (zen only), else the startup stops naming the key; any other value stops it too; a store never
 * reads the key. The real files: the ZEN head office posts by ERP, Happyness's and the dev head offices take none.
 */
class StoreOrdersPostKeyTest {

	private static MockEnvironment zenHeadOffice() {
		MockEnvironment env = Installations.type("headoffice");
		env.setProperty("ownership.catalogue", "ERP");
		env.setProperty("erp.connector", "zen");
		return env;
	}

	@Test
	@DisplayName("Absent: no store order; MANUAL: taken, typed; ERP: taken, posted by the job (any case, trimmed)")
	void values() {
		MockEnvironment none = zenHeadOffice();
		NodeOwnership.resolve(none);
		assertFalse(NodeOwnership.isStoreOrdersSet(none));
		assertFalse(NodeOwnership.isStoreOrdersByErpSet(none));

		MockEnvironment manual = zenHeadOffice().withProperty("headoffice.storeorders.post", " manual ");
		NodeOwnership.resolve(manual);
		assertTrue(NodeOwnership.isStoreOrdersSet(manual));
		assertFalse(NodeOwnership.isStoreOrdersByErpSet(manual));
		assertTrue(Installation.HEAD_OFFICE_STORE_ORDERS.isTrue(manual));
		assertFalse(Installation.HEAD_OFFICE_STORE_ORDERS_ERP.isTrue(manual));

		MockEnvironment erp = zenHeadOffice().withProperty("headoffice.storeorders.post", "ERP");
		NodeOwnership.resolve(erp);
		assertTrue(Installation.HEAD_OFFICE_STORE_ORDERS.isTrue(erp));
		assertTrue(Installation.HEAD_OFFICE_STORE_ORDERS_ERP.isTrue(erp));
	}

	@Test
	@DisplayName("ERP without a connector that posts the orders, or another value: the startup stops naming the key")
	void refused() {
		MockEnvironment happyness = zenHeadOffice().withProperty("erp.connector", "happyness")
				.withProperty("headoffice.storeorders.post", "ERP");
		IllegalStateException noAbility = assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(happyness));
		assertTrue(noAbility.getMessage().startsWith("Invalid combination: headoffice.storeorders.post=ERP without a"
				+ " connector that posts the stores' orders"), noAbility.getMessage());
		assertTrue(noAbility.getMessage().contains("zen"), noAbility.getMessage());
		NodeOwnership.resolve(zenHeadOffice().withProperty("erp.connector", "happyness")
				.withProperty("headoffice.storeorders.post", "MANUAL")); // MANUAL needs no connector

		MockEnvironment wrong = zenHeadOffice().withProperty("headoffice.storeorders.post", "AUTO");
		IllegalStateException invalid = assertThrows(IllegalStateException.class, () -> NodeOwnership.resolve(wrong));
		assertTrue(invalid.getMessage().startsWith("Invalid value 'AUTO' for property headoffice.storeorders.post"),
				invalid.getMessage());
		assertEquals(java.util.Collections.singletonList("zen"), ErpAbilities.connectorsWith(ErpAbility.STORE_ORDERS));
	}

	@Test
	@DisplayName("A store never reads the key; the real files: ZEN's head office by ERP, the others none")
	void storesAndFiles() {
		MockEnvironment store = Installations.type("store").withProperty("headoffice.storeorders.post", "anything");
		NodeOwnership.resolve(store);
		assertFalse(NodeOwnership.isStoreOrdersSet(store));

		MockEnvironment zen = Installations.machine(Installations.ZEN_HEAD_OFFICE);
		assertTrue(NodeOwnership.isStoreOrdersByErpSet(zen));
		for (String other : new String[] { Installations.HAPPYNESS_HEAD_OFFICE, "dev/headoffice.properties",
				"dev/headoffice-erp.properties" }) {
			assertFalse(NodeOwnership.isStoreOrdersSet(Installations.machine(other)), other);
		}
	}
}
