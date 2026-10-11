package com.digithink.zsretail.connector.happyness;

import java.util.EnumSet;
import java.util.Set;

import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;

/**
 * Code cleanup step 7: this connector as it declares itself to erp-api and the application
 * (META-INF/spring.factories of this project): Happyness's "POS pages", keys erp.navpospages.*: a head office's catalogue by points de stock and its supply invoices.
 */
public class HappynessConnectorDescriptor implements ErpConnectorDescriptor {

	/** Its value of erp.connector. */
	public static final String NAME = "happyness";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Set<ErpAbility> abilities() {
		return EnumSet.of(ErpAbility.HEAD_OFFICE_STOCK_POINTS, ErpAbility.SUPPLY_INVOICES);
	}

	@Override
	public String keyPrefix() {
		return "erp.navpospages";
	}

	/** The old line that still selects it when erp.connector is absent. */
	@Override
	public String enabledKey() {
		return "erp.navpospages.enabled";
	}
}
