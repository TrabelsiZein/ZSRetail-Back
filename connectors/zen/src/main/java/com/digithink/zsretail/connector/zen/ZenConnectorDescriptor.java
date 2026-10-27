package com.digithink.zsretail.connector.zen;

import java.util.EnumSet;
import java.util.Set;

import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;

/**
 * Item variants (ZEN), step 8: this connector as it declares itself to erp-api and the application
 * (META-INF/spring.factories of this project): ZEN's NAV and its custom API api/sat/zsretail/v1.0, keys erp.zen.*, a
 * head office's catalogue by points de stock, one item per variant, read as the changes since a checkpoint. A new
 * connector: no old keys, no dead keys. Step 9: the supply invoices of the ERP (salesInvoices of ZEN's franchise API, ability
 * SUPPLY_INVOICES): headoffice.supply.source=ERP is accepted with it. 3.1.1: the stores' orders (variant stock, sales
 * orders, ability STORE_ORDERS): headoffice.storeorders.post=ERP is accepted with it.
 */
public class ZenConnectorDescriptor implements ErpConnectorDescriptor {

	/** Its value of erp.connector. */
	public static final String NAME = "zen";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Set<ErpAbility> abilities() {
		return EnumSet.of(ErpAbility.HEAD_OFFICE_STOCK_POINTS, ErpAbility.SUPPLY_INVOICES, ErpAbility.STORE_ORDERS);
	}

	/** The startup summary: "NAV" and the address of the NAV instance. */
	@Override
	public String summaryLabel() {
		return "NAV";
	}

	@Override
	public String summaryKey() {
		return keyPrefix() + ".base-url";
	}
}
