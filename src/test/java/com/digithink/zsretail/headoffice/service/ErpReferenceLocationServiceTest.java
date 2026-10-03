package com.digithink.zsretail.headoffice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.digithink.zsretail.model.Location;
import com.digithink.zsretail.repository.LocationRepository;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.LocationService;

/**
 * Head office plan, task 3.4: the ERP reference location of a head office with an ERP. Chosen among the locations
 * imported from the ERP by code (any case); choosing is the store's set-as-default (DEFAULT_LOCATION and the default
 * flag the price import reads); a blank or unknown code is refused and changes nothing. In-memory locations.
 */
class ErpReferenceLocationServiceTest {

	private final List<Location> locations = new ArrayList<>();
	private final Map<String, String> setup = new HashMap<>();
	private final List<Long> setAsDefault = new ArrayList<>();
	private ErpReferenceLocationService service;

	@BeforeEach
	void setUp() {
		locations.clear();
		setup.clear();
		setAsDefault.clear();
		locations.add(location(2L, "MAG02", "Magasin Sfax", "RC-SFAX"));
		locations.add(location(1L, "DEPOT", "Depot central", "RC-CENTRE"));
		LocationRepository repository = InMemoryDownTables.proxy(LocationRepository.class, (method, args) -> {
			if ("findAll".equals(method)) {
				return new ArrayList<>(locations);
			}
			throw new UnsupportedOperationException(method);
		});
		LocationService locationService = new LocationService() {
			@Override
			public Location setAsDefault(Long id) {
				setAsDefault.add(id);
				Location chosen = null;
				for (Location location : locations) {
					location.setIsDefault(location.getId().equals(id));
					if (location.getId().equals(id)) {
						chosen = location;
					}
				}
				setup.put("DEFAULT_LOCATION", chosen.getLocationCode());
				return chosen;
			}
		};
		GeneralSetupService generalSetup = new GeneralSetupService() {
			@Override
			public String findValueByCode(String code) {
				return setup.get(code);
			}
		};
		service = new ErpReferenceLocationService(repository, locationService, generalSetup);
	}

	private static Location location(Long id, String code, String name, String responsibilityCenter) {
		Location location = new Location();
		location.setId(id);
		location.setLocationCode(code);
		location.setName(name);
		location.setResponsibilityCenter(responsibilityCenter);
		return location;
	}

	@SuppressWarnings("unchecked")
	private static List<String> codes(Map<String, Object> view) {
		List<String> codes = new ArrayList<>();
		for (Map<String, Object> option : (List<Map<String, Object>>) view.get("locations")) {
			codes.add((String) option.get("locationCode"));
		}
		return codes;
	}

	@Test
	@DisplayName("None chosen yet: no code, the imported locations by code with name and responsibility center")
	void noneChosen() {
		Map<String, Object> view = service.view();
		assertNull(view.get("locationCode"));
		assertNull(view.get("responsibilityCenter"));
		assertEquals(Arrays.asList("DEPOT", "MAG02"), codes(view));
		assertEquals(Arrays.asList("locationCode", "name", "responsibilityCenter", "locations"),
				new ArrayList<>(view.keySet()));
		setup.put("DEFAULT_LOCATION", "  ");
		assertNull(service.view().get("locationCode"), "blank is none");
	}

	@Test
	@DisplayName("Choose by code (any case): the store's set-as-default; the view gives its name and responsibility center")
	void choose() {
		Map<String, Object> view = service.choose(" depot ");
		assertEquals(Arrays.asList(1L), setAsDefault);
		assertEquals("DEPOT", view.get("locationCode"));
		assertEquals("Depot central", view.get("name"));
		assertEquals("RC-CENTRE", view.get("responsibilityCenter"));
	}

	@Test
	@DisplayName("Blank or unknown code refused, nothing changed; a DEFAULT_LOCATION not imported shows its code only")
	void refused() {
		assertThrows(IllegalArgumentException.class, () -> service.choose(null));
		assertThrows(IllegalArgumentException.class, () -> service.choose(" "));
		IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class, () -> service.choose("MAG99"));
		assertEquals("Unknown ERP location 'MAG99': choose one of the locations imported from the ERP (job IMPORT_LOCATIONS)",
				unknown.getMessage());
		assertEquals(0, setAsDefault.size());

		setup.put("DEFAULT_LOCATION", "OLD");
		Map<String, Object> view = service.view();
		assertEquals("OLD", view.get("locationCode"));
		assertNull(view.get("name"));
	}
}
