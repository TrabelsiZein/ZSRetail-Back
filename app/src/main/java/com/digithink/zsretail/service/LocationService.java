package com.digithink.zsretail.service;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.model.Location;
import com.digithink.zsretail.repository.LocationRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class LocationService extends _BaseService<Location, Long> {

	@Autowired
	private LocationRepository locationRepository;

	@Autowired
	private GeneralSetupService generalSetupService;

	@Override
	protected _BaseRepository<Location, Long> getRepository() {
		return locationRepository;
	}

	@Transactional
	public Location setAsDefault(Long locationId) {
		Optional<Location> locationOpt = locationRepository.findById(locationId);
		if (!locationOpt.isPresent()) {
			throw new RuntimeException("Location not found with id: " + locationId);
		}

		Location location = locationOpt.get();

		// Unset all other default locations
		locationRepository.findByIsDefaultTrue().ifPresent(currentDefault -> {
			if (!currentDefault.getId().equals(locationId)) {
				currentDefault.setIsDefault(false);
				locationRepository.save(currentDefault);
			}
		});

		// Set this location as default
		location.setIsDefault(true);
		Location savedLocation = locationRepository.save(location);

		// Update GeneralSetup
		generalSetupService.updateValue("DEFAULT_LOCATION", location.getLocationCode());

		return savedLocation;
	}
}

