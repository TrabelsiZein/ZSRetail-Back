package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

import com.digithink.zsretail.model.enumeration.DataDomain;

/**
 * The bean exists only on a store whose domain is owned by its head office (step 3): headoffice.url is set and
 * ownership.&lt;domain&gt; is HEAD_OFFICE. Carried by the copies down handler of that domain. See
 * docs/modules/head-office.md, "Copies down".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnHeadOfficeOwnedCondition.class)
public @interface ConditionalOnHeadOfficeOwned {

	DataDomain value();
}
