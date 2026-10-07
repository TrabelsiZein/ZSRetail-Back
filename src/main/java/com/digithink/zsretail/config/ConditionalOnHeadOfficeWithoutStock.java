package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a head office that keeps no stock and makes no purchases: node.type=HEAD_OFFICE and
 * headoffice.stock.enabled=false. Never on a store, nor on a head office that keeps its stock (the default). See
 * docs/modules/head-office.md, "Head office without stock".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeWithoutStockCondition.class)
public @interface ConditionalOnHeadOfficeWithoutStock {
}
