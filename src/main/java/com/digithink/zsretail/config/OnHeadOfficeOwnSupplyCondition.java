package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches on a head office that makes the supply documents of its stores itself: a head office without an ERP
 * ({@link NodeOwnership#isHeadOfficeWithoutErpSet}) whose headoffice.supply.source is not ERP. Used through
 * {@link ConditionalOnHeadOfficeOwnSupply}.
 */
public class OnHeadOfficeOwnSupplyCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isHeadOfficeWithoutErpSet(context.getEnvironment())
				&& !NodeOwnership.isSupplyFromErpSourceSet(context.getEnvironment());
	}
}
