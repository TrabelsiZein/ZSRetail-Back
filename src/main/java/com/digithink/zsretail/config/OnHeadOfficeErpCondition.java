package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches on a head office with an ERP (task 3.4, decision D2): node.type=HEAD_OFFICE and an owner ERP (preset
 * headoffice-erp), resolved like the startup ({@link NodeOwnership#isHeadOfficeErpSet}). Used through {@link ConditionalOnHeadOfficeErp}.
 */
public class OnHeadOfficeErpCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isHeadOfficeErpSet(context.getEnvironment());
	}
}
