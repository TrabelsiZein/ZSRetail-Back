package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when headoffice.url is set and at least one domain is owned by the head office, read exactly as at startup
 * ({@link NodeOwnership#isHeadOfficePullSet}). Used through {@link ConditionalOnHeadOfficePull}.
 */
public class OnHeadOfficePullCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isHeadOfficePullSet(context.getEnvironment());
	}
}
