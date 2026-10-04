package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a head office with an ERP (task 3.4): node.type=HEAD_OFFICE and the ERP owning the catalogue,
 * the customers and the supply (type headoffice with the ERP owners).
 * See docs/modules/head-office.md, "Head office with an ERP".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeErpCondition.class)
public @interface ConditionalOnHeadOfficeErp {
}
