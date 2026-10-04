package com.digithink.zsretail.holink.controller;

import java.util.Collections;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSupply;
import com.digithink.zsretail.holink.dto.ReceptionInputDTO;
import com.digithink.zsretail.holink.service.DeliveryReceptionService;
import com.digithink.zsretail.security.CurrentUserProvider;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 7A.3: the BLs at the store and their reception (JWT, like the other admin APIs). Only on a
 * store whose goods come from the head office: elsewhere these URLs answer 404. Nothing here calls the head office:
 * a confirmation works offline. Errors {"error"}: 400 invalid data, 404 unknown BL, 409 a BL already received. See
 * docs/modules/head-office.md, "BLs at the store".
 */
@RestController
@RequestMapping("admin/deliveries")
@ConditionalOnHeadOfficeSupply
@Log4j2
public class DeliveryReceptionAPI {

	private static final String NOT_FOUND = "Not found";

	private final DeliveryReceptionService service;
	private final CurrentUserProvider currentUser;

	public DeliveryReceptionAPI(DeliveryReceptionService service, CurrentUserProvider currentUser) {
		this.service = service;
		this.currentUser = currentUser;
	}

	/** A page {content, totalElements, totalPages, number, size}, newest first; status TO_RECEIVE, RECEIVED or all. */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return answer(() -> ResponseEntity.ok(service.list(status, page, size)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable Long id) {
		return answer(() -> service.get(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** Body {note, lines: [{lineNo, quantityReceived}]} (a line absent: received as sent). 200 the BL received. */
	@PostMapping("/{id}/receive")
	public ResponseEntity<?> receive(@PathVariable Long id, @RequestBody(required = false) ReceptionInputDTO body) {
		return answer(() -> service.receive(id, body, userName()).<ResponseEntity<?>>map(ResponseEntity::ok)
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
			log.error("DeliveryReceptionAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
