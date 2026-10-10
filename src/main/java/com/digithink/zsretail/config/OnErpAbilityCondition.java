package com.digithink.zsretail.config;

import java.util.Map;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.digithink.zsretail.erp.spi.ErpAbilities;
import com.digithink.zsretail.erp.spi.ErpAbility;

/**
 * Matches when the ERP connector in effect has the ability named by {@link ConditionalOnErpAbility}
 * ({@link ErpAbilities#has}).
 */
public class OnErpAbilityCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnErpAbility.class.getName());
		return ErpAbilities.has(context.getEnvironment(), (ErpAbility) attributes.get("value"));
	}
}
