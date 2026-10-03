package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when headoffice.url is set and the sales upstreams include HEAD_OFFICE, read exactly as at startup
 * ({@link NodeOwnership#isHeadOfficeSalesPushSet}). Used through {@link ConditionalOnHeadOfficeSalesPush}.
 */
public class OnHeadOfficeSalesPushCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isHeadOfficeSalesPushSet(context.getEnvironment());
	}
}
