package com.digithink.zsretail.connector.emtop;

import java.util.EnumSet;
import java.util.Set;

import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;

/**
 * Code cleanup step 7: this connector as it declares itself to erp-api and the application
 * (META-INF/spring.factories of this project): EMTOP's Dynamics NAV / Business Central, keys erp.emtop.*, no ability
 * of its own; the startup summary shows its NAV address. Code cleanup step 8: chosen by erp.connector=emtop only; its
 * keys were renamed from its old prefix, and every old key is refused.
 */
public class EmtopConnectorDescriptor implements ErpConnectorDescriptor {

	/** Its value of erp.connector. */
	public static final String NAME = "emtop";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Set<ErpAbility> abilities() {
		return EnumSet.noneOf(ErpAbility.class);
	}

	/** Its keys up to 2.2.2 (since code cleanup step 8 erp.<name>.*): an old key refuses the startup, naming its new name. */
	@Override
	public String oldKeyPrefix() {
		return "erp.dynamicsnav";
	}

	@Override
	public String summaryLabel() {
		return "NAV";
	}

	@Override
	public String summaryKey() {
		return keyPrefix() + ".base-url";
	}
}
