package com.digithink.zsretail.connector.happyness.config;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.happyness.HappynessConnectorDescriptor;
import com.digithink.zsretail.erp.api.CatalogueSettings;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

/**
 * Code cleanup step 3: the settings of the catalogue engine from this connector's own keys, unchanged:
 * erp.happyness.packet-size, dry-run and default-vat (read at each call, so a change of the settings object is seen).
 */
@Component
@ConditionalOnErpConnector(HappynessConnectorDescriptor.NAME)
public class NavPosPagesCatalogueSettings implements CatalogueSettings {

	private final NavPosPagesProperties properties;

	public NavPosPagesCatalogueSettings(NavPosPagesProperties properties) {
		this.properties = properties;
	}

	@Override
	public int packetSize() {
		return properties.getPacketSize();
	}

	@Override
	public boolean dryRun() {
		return properties.isDryRun();
	}

	@Override
	public Integer defaultVat() {
		return properties.getDefaultVat();
	}
}
