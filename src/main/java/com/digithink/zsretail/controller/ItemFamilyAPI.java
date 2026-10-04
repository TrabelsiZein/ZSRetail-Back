package com.digithink.zsretail.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ApplicationModeService;
import com.digithink.zsretail.holink.service.StoreCatalogueGuard;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.service.CatalogueCodeChangeException;
import com.digithink.zsretail.service.CatalogueCodeTooLongException;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.ItemFamilyService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("item-family")
@Log4j2
public class ItemFamilyAPI extends _BaseController<ItemFamily, Long, ItemFamilyService> {

	@Autowired(required = false)
	private ObjectProvider<StoreCatalogueGuard> catalogueGuard;

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
		StoreCatalogueGuard guard = catalogueGuard();
		if (guard != null) { // step 6: an own record, only with the purchase right, never a head office code
			String refusal = guard.create() != null ? guard.create() : guard.familyCodeTaken(entity.getCode());
			if (refusal != null) {
				return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(refusal));
			}
		}
		try {
			log.info("ItemFamilyAPI::create");
			ItemFamily created = service.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (CatalogueCodeTooLongException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage())); // step 6, head office
		} catch (Exception e) {
			log.error("ItemFamilyAPI::create:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * The generic update. Step 6: 409 when a head office that sends its catalogue would change the family's code, and on a store
	 * whose catalogue is the head office's for a head office family (consult-only).
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
			StoreCatalogueGuard guard = catalogueGuard();
			if (guard != null && guard.familyWrite(id) != null) { // step 6: a head office record is consult-only
				return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(guard.familyWrite(id)));
			}
			entity.setId(existing.get().getId());
			return ResponseEntity.ok(service.save(entity));
		} catch (CatalogueCodeChangeException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (CatalogueCodeTooLongException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage())); // step 6, head office
		} catch (Exception e) {
			log.error("ItemFamilyAPI::update:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** The generic delete. Step 6: 409 for a head office record on a store whose catalogue is the head office's. */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		StoreCatalogueGuard guard = catalogueGuard();
		if (guard != null && guard.familyWrite(id) != null) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(guard.familyWrite(id)));
		}
		return super.deleteById(id);
	}

	/** Step 6: the rules of a store whose catalogue is the head office's; null on every other installation. */
	private StoreCatalogueGuard catalogueGuard() {
		return catalogueGuard == null ? null : catalogueGuard.getIfAvailable();
	}
}


