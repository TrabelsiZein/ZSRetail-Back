package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a head office whose catalogue only comes from the ERP (ERP catalogue, step 2):
 * node.type=HEAD_OFFICE, ownership.catalogue=ERP, customers and supply not the ERP's. Never on a store, on a head office
 * without an ERP, or on a head office whose ERP owns the catalogue, the customers and the supply. See
 * docs/modules/head-office.md, "Head office with the catalogue only from the ERP".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeErpCatalogueCondition.class)
public @interface ConditionalOnHeadOfficeErpCatalogue {
}
