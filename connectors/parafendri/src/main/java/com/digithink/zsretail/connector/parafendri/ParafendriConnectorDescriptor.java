package com.digithink.zsretail.connector.parafendri;

import java.util.EnumSet;
import java.util.Set;

import com.digithink.zsretail.erp.api.ErpAbility;
import com.digithink.zsretail.erp.api.ErpConnectorDescriptor;

/**
 * ParaFendri, step 0: this connector as it declares itself to erp-api and the application
 * (META-INF/spring.factories of this project): ParaFendri's Business Central 28 on-prem and its custom API
 * api/sat/zsretail/v1.0, keys erp.parafendri.*, on the stores of its points de vente as EMTOP's. A new connector: no
 * old keys, no dead keys. Step 6: WHOLE_TICKET_EXPORT, a ticket and its lines posted in one call (salesOrders). Head
 * office catalogue: HEAD_OFFICE_STOCK_POINTS, the catalogue engine on a head office whose catalogue comes from the ERP
 * (its points de stock the points de vente); the first connector on stores and a head office: the engine's beans are
 * also gated by that installation, so a store keeps its job IMPORT_CATALOGUE.
 */
public class ParafendriConnectorDescriptor implements ErpConnectorDescriptor {

	/** Its value of erp.connector. */
	public static final String NAME = "parafendri";

	@Override
	public String name() {
		return NAME;
	}

	@Override
	public Set<ErpAbility> abilities() {
		return EnumSet.of(ErpAbility.WHOLE_TICKET_EXPORT, ErpAbility.HEAD_OFFICE_STOCK_POINTS);
	}

	/** The startup summary: "BC" and the address of the Business Central instance. */
	@Override
	public String summaryLabel() {
		return "BC";
	}

	@Override
	public String summaryKey() {
		return keyPrefix() + ".base-url";
	}
}
