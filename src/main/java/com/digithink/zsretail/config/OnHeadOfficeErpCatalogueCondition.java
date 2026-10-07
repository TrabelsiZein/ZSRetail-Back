package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches on a head office whose catalogue only comes from the ERP (ERP catalogue, step 2): node.type=HEAD_OFFICE,
 * ownership.catalogue=ERP, customers and supply not the ERP's, resolved like the startup
 * ({@link NodeOwnership#isErpCatalogueOnlySet}). Never on a store. Used through {@link ConditionalOnHeadOfficeErpCatalogue}.
 */
public class OnHeadOfficeErpCatalogueCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isErpCatalogueOnlySet(context.getEnvironment());
	}
}
