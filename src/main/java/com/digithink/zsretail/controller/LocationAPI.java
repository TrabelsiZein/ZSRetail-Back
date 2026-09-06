package com.digithink.zsretail.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.Location;
import com.digithink.zsretail.service.LocationService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("location")
@Log4j2
public class LocationAPI extends _BaseController<Location, Long, LocationService> {

	private final ApplicationModeService applicationModeService;

	public LocationAPI(ApplicationModeService applicationModeService) {
		this.applicationModeService = applicationModeService;
	}

	@PutMapping("/{id}/set-default")
	public ResponseEntity<?> setAsDefault(@PathVariable Long id) {
		try {
			log.info(this.getClass().getSimpleName() + "::setAsDefault::" + id);
			Location location = service.setAsDefault(id);
			return ResponseEntity.ok(location);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error(this.getClass().getSimpleName() + "::setAsDefault:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * Create location. Only allowed in standalone mode.
	 */
	@PostMapping
	@Override
	public ResponseEntity<?> create(@RequestBody Location entity) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Location creation is only available in standalone mode. In ERP mode locations are synchronized from the ERP."));
		}
		try {
			log.info("LocationAPI::create");
			Location created = service.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (Exception e) {
			log.error("LocationAPI::create:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Update location. Only allowed in standalone mode.
	 */
	@PutMapping("/{id}")
	@Override
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Location entity) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Location update is only available in standalone mode. In ERP mode locations are synchronized from the ERP."));
		}
		try {
			log.info("LocationAPI::update::" + id);
			java.util.Optional<Location> existing = service.findById(id);
			if (!existing.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			entity.setId(existing.get().getId());
			Location updated = service.save(entity);
			return ResponseEntity.ok(updated);
		} catch (Exception e) {
			log.error("LocationAPI::update:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * Delete location. Only allowed in standalone mode.
	 */
	@DeleteMapping("/{id}")
	@Override
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Location deletion is only available in standalone mode. In ERP mode locations are synchronized from the ERP."));
		}
		try {
			log.info("LocationAPI::deleteById::" + id);
			service.deleteById(id);
			return ResponseEntity.noContent().build();
		} catch (Exception e) {
			log.error("LocationAPI::deleteById:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}
