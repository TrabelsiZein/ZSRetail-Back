package com.digithink.zsretail.config;

import org.springframework.core.env.PropertyResolver;

import com.digithink.zsretail.erp.api.ErpConnectorResolver;

/**
 * Code cleanup step 5: the application's answers to erp-api's questions: which connector is in effect
 * ({@link ErpConnectorEnvironmentPostProcessor#inEffect}: erp.connector, or the old enabled lines) and whether this is a
 * head office whose catalogue only comes from the ERP ({@link NodeOwnership#isErpCatalogueOnlySet}). Registered in
 * META-INF/spring.factories.
 */
public class ErpConnectorInEffect implements ErpConnectorResolver {

	@Override
	public String inEffect(PropertyResolver env) {
		return ErpConnectorEnvironmentPostProcessor.inEffect(env);
	}

	@Override
	public boolean isErpCatalogueOnlyHeadOffice(PropertyResolver env) {
		return NodeOwnership.isErpCatalogueOnlySet(env);
	}
}
