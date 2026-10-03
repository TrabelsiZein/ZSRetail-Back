package com.digithink.zsretail.config;

import java.util.Map;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * Matches when headoffice.url is set and the domain named by {@link ConditionalOnHeadOfficeOwned} is owned by the head
 * office, read exactly as at startup ({@link NodeOwnership#isOwnedByHeadOffice}).
 */
public class OnHeadOfficeOwnedCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		Map<String, Object> attributes = metadata.getAnnotationAttributes(ConditionalOnHeadOfficeOwned.class.getName());
		if (attributes == null) {
			return false;
		}
		return NodeOwnership.isOwnedByHeadOffice(context.getEnvironment(), (DataDomain) attributes.get("value"));
	}
}
