package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches when headoffice.url is present and not blank, read exactly as at startup
 * ({@link NodeOwnership#isHeadOfficeLinkSet}). Used through {@link ConditionalOnHeadOfficeLink}.
 */
public class OnHeadOfficeLinkCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isHeadOfficeLinkSet(context.getEnvironment());
	}
}
