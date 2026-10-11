package com.digithink.zsretail.core.config;

import java.util.Map;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Matches when the question named by {@link ConditionalOnInstallation} is true for this configuration. */
public class OnInstallationCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnInstallation.class.getName());
		return attributes != null && ((Installation) attributes.get("value")).isTrue(context.getEnvironment());
	}
}
