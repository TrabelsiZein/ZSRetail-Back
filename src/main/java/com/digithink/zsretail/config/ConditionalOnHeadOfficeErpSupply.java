package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a head office whose stores' supply documents are the ERP's invoices (invoices from the ERP):
 * the catalogue only from the ERP and headoffice.supply.source=ERP. Never on a store or any other head office. See
 * docs/modules/head-office.md, "Invoices from the ERP".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeErpSupplyCondition.class)
public @interface ConditionalOnHeadOfficeErpSupply {
}
