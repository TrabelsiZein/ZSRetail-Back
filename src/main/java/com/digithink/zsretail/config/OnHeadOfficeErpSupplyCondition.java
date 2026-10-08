package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches on a head office whose catalogue only comes from the ERP ({@link NodeOwnership#isErpCatalogueOnlySet}) with
 * headoffice.supply.source=ERP ({@link NodeOwnership#isSupplyFromErpSourceSet}). Never on a store. Used through
 * {@link ConditionalOnHeadOfficeErpSupply}.
 */
public class OnHeadOfficeErpSupplyCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isErpCatalogueOnlySet(context.getEnvironment())
				&& NodeOwnership.isSupplyFromErpSourceSet(context.getEnvironment());
	}
}
