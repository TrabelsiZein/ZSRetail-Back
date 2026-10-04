package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches on a store whose catalogue is the head office's (step 6), read exactly as at startup
 * ({@link NodeOwnership#isCatalogueFromHeadOffice}). Used through {@link ConditionalOnHeadOfficeCatalogue}.
 */
public class OnHeadOfficeCatalogueCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isCatalogueFromHeadOffice(context.getEnvironment());
	}
}
