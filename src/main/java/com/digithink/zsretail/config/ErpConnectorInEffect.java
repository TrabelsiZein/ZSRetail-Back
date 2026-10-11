package com.digithink.zsretail.config;

import org.springframework.core.env.PropertyResolver;

import com.digithink.zsretail.erp.spi.ErpConnectorResolver;

/**
 * Code cleanup step 5: the application's answer to erp-api's question "which connector is in effect"
 * ({@link ErpConnectorEnvironmentPostProcessor#inEffect}: erp.connector, or the old enabled lines). Registered in
 * META-INF/spring.factories.
 */
public class ErpConnectorInEffect implements ErpConnectorResolver {

	@Override
	public String inEffect(PropertyResolver env) {
		return ErpConnectorEnvironmentPostProcessor.inEffect(env);
	}
}
