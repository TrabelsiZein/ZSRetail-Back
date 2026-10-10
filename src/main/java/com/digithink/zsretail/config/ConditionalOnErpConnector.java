package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Code cleanup, step 2: the bean exists only when the ERP connector in effect is this one (none, dynamicsnav or
 * navpospages, {@link com.digithink.zsretail.erp.spi.ErpAbilities#connectorInEffect}), so exactly one ErpConnector bean
 * exists. Used by the three ErpConnector classes only: the application asks for an ability
 * ({@link ConditionalOnErpAbility}), never for a connector.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnErpConnectorCondition.class)
public @interface ConditionalOnErpConnector {

	/** The connector name, as erp.connector gives it. */
	String value();
}
