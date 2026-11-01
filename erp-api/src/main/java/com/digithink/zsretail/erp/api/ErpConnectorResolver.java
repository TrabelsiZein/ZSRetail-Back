package com.digithink.zsretail.erp.api;

import org.springframework.core.env.PropertyResolver;

/**
 * Code cleanup step 5: what erp-api asks the application about the installation: which ERP connector is in effect, as
 * the application reads its configuration (erp.connector), and whether it is a head office
 * whose catalogue only comes from the ERP. The application gives it (META-INF/spring.factories), so erp-api names no key of the
 * application; {@link ErpConnectors#inEffect} asks it.
 */
public interface ErpConnectorResolver {

	/** The connector in effect: {@link ErpConnectors#NONE} or the name of a connector found ({@link ErpConnectorDescriptor}). */
	String inEffect(PropertyResolver env);

	/**
	 * True on a head office whose catalogue only comes from the ERP (its customers and supply not the ERP's), as the
	 * startup resolves it: the only installation where the catalogue engine of a connector with the ability
	 * HEAD_OFFICE_STOCK_POINTS exists (its beans gated by that ability and by this installation: ParaFendri's connector
	 * also serves stores, which keep their job IMPORT_CATALOGUE). Throws like the startup on an invalid configuration.
	 */
	boolean isErpCatalogueOnlyHeadOffice(PropertyResolver env);
}
