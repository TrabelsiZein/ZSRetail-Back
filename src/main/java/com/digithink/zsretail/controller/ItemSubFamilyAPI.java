package com.digithink.zsretail.controller;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
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
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.service.CatalogueCodeChangeException;
import com.digithink.zsretail.service.CatalogueCodeTooLongException;
import com.digithink.zsretail.service.GeneralSetupService;
import com.digithink.zsretail.service.ItemFamilyService;
import com.digithink.zsretail.service.ItemSubFamilyService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("item-sub-family")
@Log4j2
public class ItemSubFamilyAPI extends _BaseController<ItemSubFamily, Long, ItemSubFamilyService> {

	@Autowired(required = false)
	private ObjectProvider<StoreCatalogueGuard> catalogueGuard;

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
		if (applicationModeService.isCatalogueFromErp()) {
			return ResponseEntity.status(HttpStatus.FORBIDDEN)
					.body(createErrorResponse("Item subfamily creation is only available in standalone mode. In ERP mode subfamilies are synchronized from the ERP."));
		}
		StoreCatalogueGuard guard = catalogueGuard();
		if (guard != null) { // step 6: an own record, only with the purchase right, never a head office code
			String refusal = guard.create() != null ? guard.create() : guard.subFamilyCodeTaken(entity.getCode());
			if (refusal != null) {
				return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(refusal));
			}
		}
		try {
			log.info("ItemSubFamilyAPI::create");
			ItemSubFamily created = service.save(entity);
			return ResponseEntity.status(HttpStatus.CREATED).body(created);
		} catch (CatalogueCodeTooLongException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage())); // step 6, head office
		} catch (Exception e) {
			log.error("ItemSubFamilyAPI::create:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/**
	 * The generic update. Step 6: 409 when a head office that sends its catalogue would change the sub-family's code, and on a
	 * store whose catalogue is the head office's for a head office sub-family (consult-only).
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
			StoreCatalogueGuard guard = catalogueGuard();
			if (guard != null && guard.subFamilyWrite(id) != null) { // step 6: a head office record is consult-only
				return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(guard.subFamilyWrite(id)));
			}
			entity.setId(existing.get().getId());
			return ResponseEntity.ok(service.save(entity));
		} catch (CatalogueCodeChangeException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(e.getMessage()));
		} catch (CatalogueCodeTooLongException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage())); // step 6, head office
		} catch (Exception e) {
			log.error("ItemSubFamilyAPI::update:error: " + getDetailedMessage(e), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(getDetailedMessage(e)));
		}
	}

	/** The generic delete. Step 6: 409 for a head office record on a store whose catalogue is the head office's. */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		StoreCatalogueGuard guard = catalogueGuard();
		if (guard != null && guard.subFamilyWrite(id) != null) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(guard.subFamilyWrite(id)));
		}
		return super.deleteById(id);
	}

	/** Step 6: the rules of a store whose catalogue is the head office's; null on every other installation. */
	private StoreCatalogueGuard catalogueGuard() {
		return catalogueGuard == null ? null : catalogueGuard.getIfAvailable();
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


