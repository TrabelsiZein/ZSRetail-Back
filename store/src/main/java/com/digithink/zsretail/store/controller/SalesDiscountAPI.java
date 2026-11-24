package com.digithink.zsretail.store.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.controller._BaseController;
import com.digithink.zsretail.core.model.SalesDiscount;
import com.digithink.zsretail.store.service.SalesDiscountService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("sales-discount")
@Log4j2
public class SalesDiscountAPI extends _BaseController<SalesDiscount, Long, SalesDiscountService> {

	@Autowired
	private SalesDiscountService salesDiscountService;

	/** 3.4.0, event log step 9: a discount created, changed (its values before → after) or deleted. */
	@Autowired(required = false)
	private com.digithink.zsretail.core.eventlog.service.AdminEvents adminEvents;

	/** The generic create, its event (3.4.0). */
	@Override
	@org.springframework.web.bind.annotation.PostMapping
	public ResponseEntity<?> create(@org.springframework.web.bind.annotation.RequestBody SalesDiscount entity) {
		ResponseEntity<?> answer = super.create(entity);
		if (adminEvents != null && answer.getStatusCode().is2xxSuccessful() && answer.getBody() instanceof SalesDiscount) {
			adminEvents.discountSaved(null, (SalesDiscount) answer.getBody());
		}
		return answer;
	}

	/** The generic update, its event with the values before (copied: the loaded row is merged over). */
	@Override
	@org.springframework.web.bind.annotation.PutMapping("/{id}")
	public ResponseEntity<?> update(@org.springframework.web.bind.annotation.PathVariable Long id,
			@org.springframework.web.bind.annotation.RequestBody SalesDiscount entity) {
		java.util.Map<String, String> before = service.findById(id).map(com.digithink.zsretail.core.eventlog.service.AdminEvents::describe).orElse(null);
		ResponseEntity<?> answer = super.update(id, entity);
		if (adminEvents != null && before != null && answer.getStatusCode().is2xxSuccessful()
				&& answer.getBody() instanceof SalesDiscount) {
			adminEvents.discountSaved(before, (SalesDiscount) answer.getBody());
		}
		return answer;
	}

	/** The generic delete, its event. */
	@Override
	@org.springframework.web.bind.annotation.DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@org.springframework.web.bind.annotation.PathVariable Long id) {
		SalesDiscount gone = service.findById(id).orElse(null);
		ResponseEntity<?> answer = super.deleteById(id);
		if (adminEvents != null && gone != null && answer.getStatusCode().is2xxSuccessful()) {
			adminEvents.discountDeleted(gone);
		}
		return answer;
	}

	/**
	 * Get paginated sales discounts with optional search
	 * GET /sales-discount/paginated?page=0&size=20&search=term
	 */
	@GetMapping("/paginated")
	public ResponseEntity<?> getAllPaginated(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String search) {
		try {
			log.info("SalesDiscountAPI::getAllPaginated::page={}, size={}, search={}", page, size, search);
			Page<SalesDiscount> salesDiscounts = salesDiscountService.findAllPaginated(page, size, search);
			return ResponseEntity.ok(salesDiscounts);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("SalesDiscountAPI::getAllPaginated:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}
}

