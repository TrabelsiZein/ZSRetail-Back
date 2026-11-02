package com.digithink.zsretail.connector.parafendri.config;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import com.digithink.zsretail.erp.api.ErpConnectors;

/**
 * Head office catalogue: the bean exists only on a head office whose catalogue only comes from the ERP
 * ({@link ErpConnectors#isErpCatalogueOnlyHeadOffice}), as the catalogue engine (core gates it the same way, with the
 * installation HEAD_OFFICE_ERP_CATALOGUE_ONLY). ParaFendri is the first connector on stores and a head office: a store
 * (Gremda, Taniour...) keeps its ImportCatalogueJob path and never has the catalogue source.
 */
public class ParafendriHeadOfficeCondition implements Condition {

	@Override
	public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
		return ErpConnectors.isErpCatalogueOnlyHeadOffice(context.getEnvironment());
	}
}
