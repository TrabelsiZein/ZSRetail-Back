package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a store whose goods come from the head office by BL (step 7A): headoffice.url set, an
 * explicit ownership.supply=HEAD_OFFICE (accepted only without an ERP). See docs/modules/head-office.md, "BLs at the
 * store".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeSupplyCondition.class)
public @interface ConditionalOnHeadOfficeSupply {
}
