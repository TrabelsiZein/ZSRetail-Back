package com.digithink.zsretail.core.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * Code cleanup step 4: the bean exists only when this question about the installation is true ({@link Installation}:
 * the list of every question, each with its plain sentence). Replaces the thirteen ConditionalOnHeadOffice...
 * annotations. The ERP's own questions stay {@link ConditionalOnErpAbility} and {@link ConditionalOnErpConnector}.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnInstallationCondition.class)
public @interface ConditionalOnInstallation {

	Installation value();
}
