package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a head office that makes the BLs and supply invoices of its stores (step 7): a head office
 * without an ERP, or with the catalogue only from the ERP, whose headoffice.supply.source is HEAD_OFFICE (the default).
 * Never on a store, on a head office whose ERP owns all three, or with headoffice.supply.source=ERP. See
 * docs/modules/head-office.md, "Invoices from the ERP".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeOwnSupplyCondition.class)
public @interface ConditionalOnHeadOfficeOwnSupply {
}
