package com.digithink.zsretail.erp.navpospages.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.engine.CatalogueSettings;

/**
 * Code cleanup step 3: the settings of the catalogue engine from this connector's own keys, unchanged:
 * erp.navpospages.packet-size, dry-run and default-vat (read at each call, so a change of the settings object is seen).
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
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
