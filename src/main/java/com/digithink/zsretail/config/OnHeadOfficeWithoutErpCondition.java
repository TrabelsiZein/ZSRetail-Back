package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches on a head office without an ERP (step 6): node.type=HEAD_OFFICE and no owner ERP (preset
 * headoffice), resolved like the startup ({@link NodeOwnership#isHeadOfficeWithoutErpSet}). Used through
 * {@link ConditionalOnHeadOfficeWithoutErp}.
 */
public class OnHeadOfficeWithoutErpCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isHeadOfficeWithoutErpSet(context.getEnvironment());
	}
}
