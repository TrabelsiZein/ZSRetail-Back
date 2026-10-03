package com.digithink.zsretail.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.digithink.zsretail.model.enumeration.NodeType;

/**
 * Matches when node.type is HEAD_OFFICE, read exactly as at startup ({@link NodeOwnership#nodeTypeOf}: trimmed,
 * case-insensitive, STORE when absent). Used through {@link ConditionalOnHeadOffice}.
 */
public class OnHeadOfficeCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return NodeOwnership.nodeTypeOf(context.getEnvironment()) == NodeType.HEAD_OFFICE;
	}
}
