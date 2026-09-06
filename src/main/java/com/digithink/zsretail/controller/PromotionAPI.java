package com.digithink.zsretail.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.service.PromotionService;

import lombok.extern.log4j.Log4j2;

@RestController
@RequestMapping("promotion")
@Log4j2
public class PromotionAPI extends _BaseController<Promotion, Long, PromotionService> {

	@Autowired
	private PromotionService promotionService;

	/**
	 * Get paginated promotions with optional search.
	 * GET /promotion/paginated?page=0&size=20&search=term
	 */
	@GetMapping("/paginated")
	public ResponseEntity<?> getAllPaginated(
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) String search) {
		try {
			log.info("PromotionAPI::getAllPaginated::page={}, size={}, search={}", page, size, search);
			Page<Promotion> promotions = promotionService.findAllPaginated(page, size, search);
			return ResponseEntity.ok(promotions);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("PromotionAPI::getAllPaginated:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * GET /promotion/{id}/usage-count
	 * Returns how many sales lines + headers reference this promotion.
	 * Frontend uses this to decide which edit fields to disable.
	 */
	@GetMapping("/{id}/usage-count")
	public ResponseEntity<?> getUsageCount(@PathVariable Long id) {
		try {
			long count = promotionService.getUsageCount(id);
			return ResponseEntity.ok(count);
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("PromotionAPI::getUsageCount:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * DELETE /promotion/{id}
	 * Blocked with 409 + friendly message if the promotion has been used in sales.
	 */
	@Override
	@DeleteMapping("/{id}")
	public ResponseEntity<?> deleteById(@PathVariable Long id) {
		try {
			long usageCount = promotionService.getUsageCount(id);
			if (usageCount > 0) {
				return ResponseEntity.status(HttpStatus.CONFLICT)
						.body("This promotion has been used in " + usageCount
								+ " sale(s) and cannot be deleted. Deactivate it instead.");
			}
			promotionService.deleteById(id);
			return ResponseEntity.noContent().build();
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("PromotionAPI::deleteById:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}

	/**
	 * PUT /promotion/{id}
	 * Validates that locked fields are not changed when the promotion has been used in sales.
	 * Returns 409 with list of violated fields if check fails.
	 */
	@Override
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Promotion promotion) {
		try {
			if (!promotionService.findById(id).isPresent()) {
				return ResponseEntity.notFound().build();
			}
			promotionService.validateUpdateAllowed(id, promotion);
			promotion.setId(id);
			Promotion updated = promotionService.save(promotion);
			return ResponseEntity.ok(updated);
		} catch (IllegalStateException e) {
			log.warn("PromotionAPI::update: locked field violation for id={}: {}", id, e.getMessage());
			return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
		} catch (Exception e) {
			String detailedMessage = getDetailedMessage(e);
			log.error("PromotionAPI::update:error: " + detailedMessage, e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(createErrorResponse(detailedMessage));
		}
	}
}
