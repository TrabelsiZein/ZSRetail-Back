package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a store that pulls copies down from its head office (step 3): headoffice.url is set and at
 * least one domain is owned by the head office. See docs/modules/head-office.md, "Copies down".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficePullCondition.class)
public @interface ConditionalOnHeadOfficePull {
}
