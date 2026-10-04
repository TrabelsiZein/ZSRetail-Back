package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeStandalone;
import com.digithink.zsretail.headoffice.dto.DeliveryInputDTO;
import com.digithink.zsretail.headoffice.service.HoDeliveryService;
import com.digithink.zsretail.security.CurrentUserProvider;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 7A.2: the BLs (JWT, like the other admin APIs). Head office without an ERP only: elsewhere these
 * URLs answer 404. Errors {"error"}: 400 invalid data, 404 unknown BL, 409 a BL that is not a draft, a store that does
 * not receive BLs, or a stock not sufficient. See docs/modules/head-office.md, "BLs".
 */
@RestController
@RequestMapping("admin/headoffice/deliveries")
@ConditionalOnHeadOfficeStandalone
@Log4j2
public class HoDeliveryAPI {

	private static final String NOT_FOUND = "Not found";

	private final HoDeliveryService service;
	private final CurrentUserProvider currentUser;

	public HoDeliveryAPI(HoDeliveryService service, CurrentUserProvider currentUser) {
		this.service = service;
		this.currentUser = currentUser;
	}

	/** A page {content, totalElements, totalPages, number, size}, newest first. */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String status, @RequestParam(required = false) String search,
			@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
			@RequestParam(required = false) Boolean difference, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return answer(() -> ResponseEntity
				.ok(service.list(storeId, status, search, dateFrom, dateTo, difference, page, size)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable Long id) {
		return answer(() -> service.get(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** Body {storeId, documentDate, note, lines: [{itemCode or itemId, quantity}]}: 201 the draft. */
	@PostMapping
	public ResponseEntity<?> create(@RequestBody DeliveryInputDTO body) {
		return answer(() -> ResponseEntity.status(HttpStatus.CREATED).body(service.create(body)));
	}

	/** The draft replaced by the body; 409 when it is not a draft. */
	@PutMapping("/{id}")
	public ResponseEntity<?> update(@PathVariable Long id, @RequestBody DeliveryInputDTO body) {
		return answer(() -> service.update(id, body).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** 204; 409 when it is not a draft. */
	@DeleteMapping("/{id}")
	public ResponseEntity<?> delete(@PathVariable Long id) {
		return answer(() -> service.delete(id) ? ResponseEntity.noContent().build() : notFound());
	}

	/** Draft to SENT: numbered, the goods leave the head office stock, the store receives it. 200 the BL. */
	@PostMapping("/{id}/validate")
	public ResponseEntity<?> validate(@PathVariable Long id) {
		return answer(() -> service.validate(id, userName()).<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(this::notFound));
	}

	private String userName() {
		try {
			return currentUser.getCurrentUserName();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private ResponseEntity<?> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(NOT_FOUND));
	}

	private ResponseEntity<?> answer(Supplier<ResponseEntity<?>> call) {
		try {
			return call.get();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("HoDeliveryAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
