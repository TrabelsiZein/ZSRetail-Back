package com.digithink.zsretail.store.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.store.model.QuickQuantity;
import com.digithink.zsretail.store.service.QuickQuantityService;

/**
 * 3.1.2, bulk sale, step 1: the store's quick quantities, the generic CRUD as PaymentMethodAPI (GET, GET /{id}, POST,
 * PUT /{id}, DELETE /{id}). A quick quantity refused by QuickQuantityService.check answers 400 with the reason, before
 * anything is written.
 */
@RestController
@RequestMapping("quick-quantity")
public class QuickQuantityAPI extends _BaseController<QuickQuantity, Long, QuickQuantityService> {

	@Override
	@PostMapping
	public ResponseEntity<?> create(@RequestBody QuickQuantity entity) {
		ResponseEntity<?> refusal = refusal(entity);
		return refusal != null ? refusal : super.create(entity);
	}

	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody QuickQuantity entity) {
		ResponseEntity<?> refusal = refusal(entity);
		return refusal != null ? refusal : super.update(id, entity);
	}

	/** 400 with the reason when the quick quantity is refused; null when it may be saved. */
	private ResponseEntity<?> refusal(QuickQuantity entity) {
		try {
			service.check(entity);
			return null;
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(createErrorResponse(e.getMessage()));
		}
	}
}
