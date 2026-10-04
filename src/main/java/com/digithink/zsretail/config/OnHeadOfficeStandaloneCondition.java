package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches on a head office without an ERP (step 6): node.type=HEAD_OFFICE and application.standalone=true, read like the
 * startup ({@link NodeOwnership#nodeTypeOf}) and like ApplicationModeService. Used through
 * {@link ConditionalOnHeadOfficeStandalone}.
 */
public class OnHeadOfficeStandaloneCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isHeadOfficeStandaloneSet(context.getEnvironment());
	}
}
