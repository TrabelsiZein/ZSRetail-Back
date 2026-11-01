package com.digithink.zsretail.connector.parafendri.config;

import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.erp.api.CatalogueSettings;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

/**
 * Head office catalogue: the settings of the catalogue engine, constants of the connector (no key): packets of
 * {@value #PACKET_SIZE} rows, never a dry run, the VAT {@value #DEFAULT_VAT} for the rows the engine keeps without one
 * (the stockPoints rows read carry their own vatPercent).
 */
@Component
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
@Conditional(ParafendriHeadOfficeCondition.class)
public class ParafendriCatalogueSettings implements CatalogueSettings {

	static final int PACKET_SIZE = 500;
	static final int DEFAULT_VAT = 19;

	@Override
	public int packetSize() {
		return PACKET_SIZE;
	}

	@Override
	public boolean dryRun() {
		return false;
	}

	@Override
	public Integer defaultVat() {
		return DEFAULT_VAT;
	}
}
