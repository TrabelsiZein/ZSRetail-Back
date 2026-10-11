package com.digithink.zsretail.config;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.context.annotation.Conditional;

import com.digithink.zsretail.erp.api.ErpAbility;

/**
 * Code cleanup, step 2: the bean exists only when the ERP connector in effect has this ability
 * ({@link com.digithink.zsretail.erp.api.ErpAbilities}), whichever way the connector was chosen. See
 * docs/modules/erp-sync.md, "Abilities".
 */
@Target({ ElementType.TYPE, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(OnErpAbilityCondition.class)
public @interface ConditionalOnErpAbility {

	ErpAbility value();
}
