package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.PromotionTargetsDTO;
import com.digithink.zsretail.headoffice.dto.PromotionWithTargetsDTO;
import com.digithink.zsretail.headoffice.service.HoPromotionService;
import com.digithink.zsretail.holink.service.SalesCopyFinder;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 3.3: the target stores of the head office promotions (JWT, like the other admin APIs; head
 * office only, 404 on a store). Editing a promotion itself stays on PUT /promotion/{id}. Contract:
 * docs/modules/head-office.md, "Promotions owned by the head office".
 */
@RestController
@RequestMapping("admin/headoffice/promotions")
@ConditionalOnHeadOffice
@Log4j2
public class HoPromotionTargetAPI {

	private final HoPromotionService service;

	public HoPromotionTargetAPI(HoPromotionService service) {
		this.service = service;
	}

	/** Every promotion with its targets: [{promotionId, code, allStores, storeIds}]. */
	@GetMapping("/targets")
	public List<PromotionTargetsDTO> listTargets() {
		return service.listTargets();
	}

	@GetMapping("/{id}/targets")
	public ResponseEntity<?> getTargets(@PathVariable Long id) {
		try {
			return ResponseEntity.ok(service.getTargets(id));
		} catch (NoSuchElementException e) {
			return error(HttpStatus.NOT_FOUND, e.getMessage());
		}
	}

	/** Body {"allStores": true} or {"allStores": false, "storeIds": [..]}. */
	@PutMapping("/{id}/targets")
	public ResponseEntity<?> setTargets(@PathVariable Long id, @RequestBody(required = false) PromotionTargetsDTO body) {
		try {
			return ResponseEntity.ok(service.setTargets(id, body));
		} catch (NoSuchElementException e) {
			return error(HttpStatus.NOT_FOUND, e.getMessage());
		} catch (IllegalArgumentException e) {
			return error(HttpStatus.BAD_REQUEST, e.getMessage());
		}
	}

	/** Creates a promotion with its targets: body {"promotion": {...}, "allStores": .., "storeIds": [..]}. */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody(required = false) PromotionWithTargetsDTO body) {
		try {
			return ResponseEntity.status(HttpStatus.CREATED).body(service.createWithTargets(body));
		} catch (IllegalArgumentException e) {
			return error(HttpStatus.BAD_REQUEST, e.getMessage());
		} catch (Exception e) {
			log.error("HoPromotionTargetAPI::create:error: {}", SalesCopyFinder.cause(e), e);
			return error(HttpStatus.INTERNAL_SERVER_ERROR, SalesCopyFinder.cause(e));
		}
	}

	private static ResponseEntity<?> error(HttpStatus status, String message) {
		return ResponseEntity.status(status).body(Collections.singletonMap("error", message));
	}
}
