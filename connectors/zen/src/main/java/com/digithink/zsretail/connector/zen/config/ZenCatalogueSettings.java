package com.digithink.zsretail.connector.zen.config;

import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.erp.api.CatalogueSettings;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

/**
 * Item variants (ZEN), step 8: the settings of the catalogue engine from this connector's keys: erp.zen.packet-size,
 * dry-run and default-vat (read at each call, so a change of the settings object is seen).
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
public class ZenCatalogueSettings implements CatalogueSettings {

	private final ZenProperties properties;

	public ZenCatalogueSettings(ZenProperties properties) {
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
