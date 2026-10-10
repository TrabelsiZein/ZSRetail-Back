package com.digithink.zsretail.controller;

import java.util.List;

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

import com.digithink.zsretail.holink.service.StoreCatalogueGuard;
import com.digithink.zsretail.model.ItemComposition;
import com.digithink.zsretail.service.ItemCompositionService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("item-composition")
@Log4j2
public class ItemCompositionAPI extends _BaseController<ItemComposition, Long, ItemCompositionService> {

	@Autowired
	private ItemCompositionService itemCompositionService;

	/** Step 6: the rules of a store whose catalogue is the head office's; no bean on every other installation. */
	@Autowired(required = false)
	private ObjectProvider<StoreCatalogueGuard> catalogueGuard;

	/** Step 6: 409 when the pack (old or new parent) is a head office item; null otherwise or without the guard. */
	private ResponseEntity<?> refusedForPack(Long compositionId, ItemComposition body) {
		StoreCatalogueGuard guard = catalogueGuard == null ? null : catalogueGuard.getIfAvailable();
		if (guard == null) {
			return null;
		}
		String refusal = null;
		if (compositionId != null) {
			refusal = service.findById(compositionId).map(c -> guard.itemWrite(c.getParentItem().getId())).orElse(null);
		}
		if (refusal == null && body != null && body.getParentItem() != null) {
			refusal = guard.itemWrite(body.getParentItem().getId());
		}
		return refusal == null ? null : ResponseEntity.status(HttpStatus.CONFLICT).body(createErrorResponse(refusal));
	}

	/** The generic create; step 6: not on a head office pack. */
	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody ItemComposition entity) {
		String decimal = itemCompositionService.decimalRefusal(entity); // 2.2.1: 400 naming the component
		if (decimal != null) {
			return ResponseEntity.badRequest().body(createErrorResponse(decimal));
		}
		ResponseEntity<?> refusal = refusedForPack(null, entity);
		return refusal != null ? refusal : super.create(entity);
	}

	/** The generic update; step 6: not on a head office pack. */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody ItemComposition entity) {
		String decimal = itemCompositionService.decimalRefusal(entity); // 2.2.1: 400 naming the component
		if (decimal != null) {
			return ResponseEntity.badRequest().body(createErrorResponse(decimal));
		}
		ResponseEntity<?> refusal = refusedForPack(id, entity);
		return refusal != null ? refusal : super.update(id, entity);
	}

	/** The generic delete; step 6: not on a head office pack. */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		ResponseEntity<?> refusal = refusedForPack(id, null);
		return refusal != null ? refusal : super.deleteById(id);
	}

	/**
	 * Get all active components of a kit (parent PACKAGE item)
	 */
	@GetMapping("/item/{itemId}")
	public ResponseEntity<?> getComponentsByItemId(@PathVariable Long itemId) {
		try {
			log.info("ItemCompositionAPI::getComponentsByItemId: " + itemId);
			List<ItemComposition> components = itemCompositionService.getComponentsByParentItemId(itemId);
			return ResponseEntity.ok(components);
		} catch (Exception e) {
			log.error("ItemCompositionAPI::getComponentsByItemId:error: " + e.getMessage(), e);
			return ResponseEntity.status(500).body(createErrorResponse(getDetailedMessage(e)));
		}
	}
}
