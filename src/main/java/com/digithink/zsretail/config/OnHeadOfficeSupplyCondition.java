package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches on a store whose goods come from the head office by BL (step 7A), read exactly as at startup
 * ({@link NodeOwnership#isSupplyFromHeadOffice}). Used through {@link ConditionalOnHeadOfficeSupply}.
 */
public class OnHeadOfficeSupplyCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isSupplyFromHeadOffice(context.getEnvironment());
	}
}
