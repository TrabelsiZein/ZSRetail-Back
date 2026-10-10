package com.digithink.zsretail.config;

import java.util.Map;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.digithink.zsretail.erp.spi.ErpAbilities;

/** Matches when the ERP connector in effect is the one named by {@link ConditionalOnErpConnector}. */
public class OnErpConnectorCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnErpConnector.class.getName());
		return ErpAbilities.connectorInEffect(context.getEnvironment()).equals(attributes.get("value"));
	}
}
