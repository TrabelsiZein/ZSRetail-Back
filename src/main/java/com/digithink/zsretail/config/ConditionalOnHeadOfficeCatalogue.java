package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

/**
 * The bean exists only on a store whose catalogue is the head office's (step 6): headoffice.url set,
 * ownership.catalogue=HEAD_OFFICE, application.standalone=true. See
 * docs/modules/head-office.md, "Catalogue owned by the head office".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeCatalogueCondition.class)
public @interface ConditionalOnHeadOfficeCatalogue {
}
