package com.digithink.zsretail.erp.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Code cleanup, step 2: the bean exists only when the ERP connector in effect is this one ({@link ErpConnectors}: none,
 * or a connector found, {@link ErpConnectors#inEffect}), so exactly one ErpConnector bean exists. Used by the
 * ErpConnector classes only: the application asks for an ability (ConditionalOnErpAbility), never for a connector. Code cleanup
 * step 5: in erp-api, so a connector declares it without using the application.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnErpConnectorCondition.class)
public @interface ConditionalOnErpConnector {

	/** The connector name, as erp.connector gives it. */
	String value();
}
