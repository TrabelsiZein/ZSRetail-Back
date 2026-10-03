package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a store that copies its sales to a head office (task 2.1, decision 4): headoffice.url is set
 * and the sales upstreams include HEAD_OFFICE. A linked store without that upstream keeps the heartbeat only. See
 * docs/modules/head-office.md, "Sales copies".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeSalesPushCondition.class)
public @interface ConditionalOnHeadOfficeSalesPush {
}
