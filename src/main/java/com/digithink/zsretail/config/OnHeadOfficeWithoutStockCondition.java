package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/**
 * Matches on a head office that keeps no stock: node.type=HEAD_OFFICE and headoffice.stock.enabled=false, resolved like
 * the startup ({@link NodeOwnership#isHeadOfficeWithoutStockSet}). Never on a store, whatever the key says. Used through
 * {@link ConditionalOnHeadOfficeWithoutStock}.
 */
public class OnHeadOfficeWithoutStockCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.isHeadOfficeWithoutStockSet(context.getEnvironment());
	}
}
