package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErp;
import com.digithink.zsretail.model.Location;
import com.digithink.zsretail.repository.LocationRepository;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.LocationService;

/**
 * Head office plan, task 3.4: the ERP reference location of a head office with an ERP. The ERP item import filters on
 * one location (DEFAULT_LOCATION) and the price import on that location's responsibility center (the location marked
 * default): a head office chooses one among the locations imported from the ERP (job IMPORT_LOCATIONS), without the
 * store's Locations page. Choosing is the store's "set as default" (LocationService.setAsDefault): DEFAULT_LOCATION and
 * the default flag, nothing else. In the head office the word "store" is kept for the stores list.
 */
@Service
@ConditionalOnHeadOfficeErp
public class ErpReferenceLocationService {

	static final String SETTING = "DEFAULT_LOCATION";

	private final LocationRepository locations;
	private final LocationService locationService;
	private final GeneralSetupService generalSetup;

	public ErpReferenceLocationService(LocationRepository locations, LocationService locationService,
			GeneralSetupService generalSetup) {
		this.locations = locations;
		this.locationService = locationService;
		this.generalSetup = generalSetup;
	}

	/**
	 * {locationCode, name, responsibilityCenter, locations: [{locationCode, name, responsibilityCenter}]}: the reference
	 * location (DEFAULT_LOCATION, null when none; name and responsibility center null when that code is not among the
	 * imported locations) and the locations to choose from, by code.
	 */
	@Transactional(readOnly = true)
	public Map<String, Object> view() {
		String code = generalSetup.findValueByCode(SETTING);
		String current = code == null || code.trim().isEmpty() ? null : code.trim();
		List<Location> all = new ArrayList<>(locations.findAll());
		all.sort(Comparator.comparing(Location::getLocationCode, String.CASE_INSENSITIVE_ORDER));
		List<Map<String, Object>> options = new ArrayList<>();
		Location chosen = null;
		for (Location location : all) {
			options.add(option(location));
			if (current != null && current.equalsIgnoreCase(location.getLocationCode())) {
				chosen = location;
			}
		}
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("locationCode", current);
		answer.put("name", chosen == null ? null : chosen.getName());
		answer.put("responsibilityCenter", chosen == null ? null : chosen.getResponsibilityCenter());
		answer.put("locations", options);
		return answer;
	}

	/**
	 * Chooses the reference location by its code (trimmed, any case) among the imported locations. Throws
	 * IllegalArgumentException when the code is blank or not among them.
	 */
	@Transactional
	public Map<String, Object> choose(String locationCode) {
		if (locationCode == null || locationCode.trim().isEmpty()) {
			throw new IllegalArgumentException("locationCode is required");
		}
		String code = locationCode.trim();
		Optional<Location> location = locations.findAll().stream()
				.filter(l -> code.equalsIgnoreCase(l.getLocationCode())).findFirst();
		if (!location.isPresent()) {
			throw new IllegalArgumentException("Unknown ERP location '" + code
					+ "': choose one of the locations imported from the ERP (job IMPORT_LOCATIONS)");
		}
		locationService.setAsDefault(location.get().getId());
		return view();
	}

	private static Map<String, Object> option(Location location) {
		Map<String, Object> option = new LinkedHashMap<>();
		option.put("locationCode", location.getLocationCode());
		option.put("name", location.getName());
		option.put("responsibilityCenter", location.getResponsibilityCenter());
		return option;
	}
}
