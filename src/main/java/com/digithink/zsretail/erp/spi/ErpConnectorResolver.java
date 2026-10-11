package com.digithink.zsretail.erp.spi;

import org.springframework.core.env.PropertyResolver;

/**
 * Code cleanup step 5: which ERP connector is in effect, as the application reads its configuration (erp.connector, or
 * the old enabled lines). The application gives it (META-INF/spring.factories), so erp-api names no key of the
 * application; {@link ErpConnectors#inEffect} asks it.
 */
public interface ErpConnectorResolver {

	/** The connector in effect: {@link ErpConnectors#NONE}, {@link ErpConnectors#EMTOP} or {@link ErpConnectors#HAPPYNESS}. */
	String inEffect(PropertyResolver env);
}
