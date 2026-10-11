package com.digithink.zsretail.connector.happyness;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;

/**
 * Code cleanup step 7: this connector as it declares itself to erp-api and the application
 * (META-INF/spring.factories of this project): Happyness's "POS pages", keys erp.happyness.*: a head office's
 * catalogue by points de stock and its supply invoices. Code cleanup step 8: chosen by erp.connector=happyness only;
 * its keys were renamed from its old prefix, and every old key is refused.
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

	/** Its keys up to 2.2.2 (since code cleanup step 8 erp.<name>.*): an old key refuses the startup, naming its new name. */
	@Override
	public String oldKeyPrefix() {
		return "erp.navpospages";
	}

	/**
	 * Read by nothing any more: location-code (release 2.2, the head office's points de stock), invoices.years and
	 * invoices.start-number (release 2.2, the General Setup "Read ERP invoices after number"), deactivate-guard-percent
	 * (2026-10-08). Refused, under either prefix, since code cleanup step 8.
	 */
	@Override
	public Set<String> deadKeys() {
		return new LinkedHashSet<>(Arrays.asList("location-code", "invoices.years", "invoices.start-number",
				"deactivate-guard-percent"));
	}
}
