package com.digithink.zsretail.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
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
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.service.CatalogueCodeChangeException;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.ItemFamilyService;
import com.digithink.zsretail.service.ItemSubFamilyService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("item-sub-family")
@Log4j2
public class ItemSubFamilyAPI extends _BaseController<ItemSubFamily, Long, ItemSubFamilyService> {

	@Autowired
	private ItemFamilyService itemFamilyService;

	@Autowired
	private ApplicationModeService applicationModeService;

	@Autowired
	private GeneralSetupService generalSetupService;

	/**
	 * Create subfamily. Only allowed in standalone mode (in ERP mode subfamilies come from sync).
	 */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody ItemSubFamily entity) {
		if (!applicationModeService.isStandalone()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item subfamily creation is only available in standalone mode. In ERP mode subfamilies are synchronized from the ERP."));
		}
		try {
			log.info("ItemSubFamilyAPI::create");
			ItemSubFamily created = service.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (Exception e) {
			log.error("ItemSubFamilyAPI::create:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * The generic update. Step 6: 409 when a head office that sends its catalogue would change the sub-family's code.
	 */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ItemSubFamily entity) {
		try {
			log.info("ItemSubFamilyAPI::update::" + id);
			Optional<ItemSubFamily> existing = service.findById(id);
			if (!existing.isPresent()) {
				return ResponseEntity.notFound().build();
			}
			entity.setId(existing.get().getId());
			return ResponseEntity.ok(service.save(entity));
		} catch (CatalogueCodeChangeException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (Exception e) {
			log.error("ItemSubFamilyAPI::update:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	@GetMapping("/by-family/{familyId}")
	public ResponseEntity<?> getByFamily(@PathVariable Long familyId) {
		try {
			ItemFamily family = itemFamilyService.findById(familyId)
					.orElseThrow(() -> new IllegalArgumentException("Item family not found: " + familyId));
			List<ItemSubFamily> subFamilies = service.findByFamily(family);
			if (!isPosShowImages()) {
				subFamilies.forEach(sf -> sf.setImageFilename(null));
			}
			return ResponseEntity.ok(subFamilies);
		} catch (Exception e) {
			return ResponseEntity.badRequest().body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	private boolean isPosShowImages() {
		String val = generalSetupService.findValueByCode("POS_SHOW_IMAGES");
		return val == null || !"false".equalsIgnoreCase(val);
	}
}


