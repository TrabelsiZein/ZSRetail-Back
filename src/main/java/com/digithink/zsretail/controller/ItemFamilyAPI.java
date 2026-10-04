package com.digithink.zsretail.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.service.CatalogueCodeChangeException;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.ItemFamilyService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("item-family")
@Log4j2
public class ItemFamilyAPI extends _BaseController<ItemFamily, Long, ItemFamilyService> {

	private final ApplicationModeService applicationModeService;
	private final GeneralSetupService generalSetupService;

	public ItemFamilyAPI(ApplicationModeService applicationModeService,
	                     GeneralSetupService generalSetupService) {
		this.applicationModeService = applicationModeService;
		this.generalSetupService = generalSetupService;
	}

	/** Override getAll to strip imageFilename when POS_SHOW_IMAGES is disabled. */
	@Override
	@GetMapping
	public ResponseEntity<?> getAll() {
		try {
			log.info("ItemFamilyAPI::getAll");
			List<ItemFamily> families = service.findAll();
			if (!isPosShowImages()) {
				families.forEach(f -> f.setImageFilename(null));
			}
			return ResponseEntity.ok(families);
		} catch (Exception e) {
			log.error("ItemFamilyAPI::getAll:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	private boolean isPosShowImages() {
		String val = generalSetupService.findValueByCode("POS_SHOW_IMAGES");
		return val == null || !"false".equalsIgnoreCase(val);
	}

	/**
	 * Create family. Only allowed in standalone mode (in ERP mode families come from sync).
	 */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody ItemFamily entity) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item family creation is only available in standalone mode. In ERP mode families are synchronized from the ERP."));
		}
		try {
			log.info("ItemFamilyAPI::create");
			ItemFamily created = service.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (Exception e) {
			log.error("ItemFamilyAPI::create:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * The generic update. Step 6: 409 when a head office that sends its catalogue would change the family's code.
	 */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ItemFamily entity) {
		try {
			log.info("ItemFamilyAPI::update::" + id);
			Optional<ItemFamily> existing = service.findById(id);
			if (!existing.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			entity.setId(existing.get().getId());
			return ResponseEntity.ok(service.save(entity));
		} catch (CatalogueCodeChangeException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("ItemFamilyAPI::update:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}


