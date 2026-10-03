package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a store that calls a head office (headoffice.url present and not blank, task 1.4). Without
 * the URL it is not created and the store behaves as before. See docs/modules/head-office.md.
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeLinkCondition.class)
public @interface ConditionalOnHeadOfficeLink {
}
