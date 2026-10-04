package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a head office without an ERP (step 6): node.type=HEAD_OFFICE and no owner ERP
 * (preset headoffice).
 * Such a head office owns the catalogue it sends to its stores and its price lists. See docs/modules/head-office.md,
 * "Catalogue owned by the head office".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeWithoutErpCondition.class)
public @interface ConditionalOnHeadOfficeWithoutErp {
}
