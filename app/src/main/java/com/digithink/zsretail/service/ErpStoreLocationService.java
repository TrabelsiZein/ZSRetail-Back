package com.digithink.zsretail.service;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.digithink.zsretail.erp.api.ErpStoreLocation;
import com.digithink.zsretail.model.Location;

/**
 * Code cleanup step 5.8d: the application's answer to {@link ErpStoreLocation}, read as the emtop connector's client
 * read it before (General Setup DEFAULT_LOCATION; the locations with isDefault = true).
 */
@Service
public class ErpStoreLocationService implements ErpStoreLocation {

	private final GeneralSetupService generalSetupService;
	private final LocationService locationService;

	public ErpStoreLocationService(GeneralSetupService generalSetupService, LocationService locationService) {
		this.generalSetupService = generalSetupService;
		this.locationService = locationService;
	}

	@Override
	public String defaultLocationCode() {
		return generalSetupService.findValueByCode("DEFAULT_LOCATION");
	}

	@Override
	public List<String> defaultResponsibilityCenters() {
		return locationService.findByField("isDefault", "=", true).stream().map(Location::getResponsibilityCenter)
				.collect(Collectors.toList());
	}
}
