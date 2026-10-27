package com.digithink.zsretail.headoffice.controller;

import java.time.LocalDate;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.headoffice.service.HoStoreOrderPosting;
import com.digithink.zsretail.headoffice.service.HoStoreOrderService;

import lombok.extern.log4j.Log4j2;

/**
 * 3.1.1: the stores' orders at the head office, for its page (JWT, like the other admin APIs; the screen and its
 * permission come with step 4). Only on a head office that takes them (headoffice.storeorders.post set): elsewhere 404.
 * Errors {"error"}: 400 invalid data, 404 unknown order, 409 an action the order's status or the mode refuses. See
 * docs/modules/store-orders.md.
 */
@RestController
@RequestMapping("admin/headoffice/store-orders")
@ConditionalOnInstallation(Installation.HEAD_OFFICE_STORE_ORDERS)
@Log4j2
public class HoStoreOrderAPI {

	private static final String NOT_FOUND = "Not found";

	private final HoStoreOrderService service;
	/** ERP mode only: "retry now". */
	private final ObjectProvider<HoStoreOrderPosting> posting;
	private final CurrentUserProvider currentUser;

	public HoStoreOrderAPI(HoStoreOrderService service, ObjectProvider<HoStoreOrderPosting> posting,
			CurrentUserProvider currentUser) {
		this.service = service;
		this.posting = posting;
		this.currentUser = currentUser;
	}

	/**
	 * GET admin/headoffice/store-orders?storeId&amp;status&amp;from&amp;to&amp;sort&amp;dir&amp;page&amp;size: {content,
	 * totalElements, totalPages, number, size, postMode}. status SENT, POSTED, ERROR, REJECTED or none / ALL; from, to
	 * yyyy-MM-dd, the reception day, both included; sort receivedAt (the default), number, status, postedAt, store; dir
	 * desc (the default) or asc. postMode: ERP or MANUAL (which actions the page offers).
	 */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String status,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) String sort, @RequestParam(required = false) String dir,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return answer(() -> {
			Map<String, Object> result = service.list(storeId, status, from, to, sort, dir, page, size);
			result.put("postMode", service.isByErp() ? "ERP" : "MANUAL");
			return ResponseEntity.ok(result);
		});
	}

	/** GET admin/headoffice/store-orders/{id}: the order with its lines. */
	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable Long id) {
		return answer(() -> found(service.get(id)));
	}

	/** POST .../{id}/mark-posted {navOrderNo}: MANUAL mode, the ERP's number typed: POSTED. */
	@PostMapping("/{id}/mark-posted")
	public ResponseEntity<?> markPosted(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
		return answer(() -> found(service.markPosted(id, body == null ? null : body.get("navOrderNo"), userName())));
	}

	/** POST .../{id}/reject {message}: REJECTED, never posted; the store sees the message. */
	@PostMapping("/{id}/reject")
	public ResponseEntity<?> reject(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body) {
		return answer(() -> found(service.reject(id, body == null ? null : body.get("message"), userName())));
	}

	/** POST .../{id}/retry: ERP mode, the order posted now (POSTED, or ERROR with the reason); 409 in MANUAL mode. */
	@PostMapping("/{id}/retry")
	public ResponseEntity<?> retry(@PathVariable Long id) {
		return answer(() -> {
			HoStoreOrderPosting erp = posting.getIfAvailable();
			if (erp == null) {
				throw new IllegalStateException("The orders are posted by hand on this head office"
						+ " (headoffice.storeorders.post=MANUAL): use \"mark posted\"");
			}
			return found(erp.post(id));
		});
	}

	private String userName() {
		try {
			return currentUser.getCurrentUserName();
		} catch (RuntimeException e) {
			return null;
		}
	}

	private static ResponseEntity<?> found(Optional<?> value) {
		return value.<ResponseEntity<?>>map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(error(NOT_FOUND)));
	}

	private ResponseEntity<?> answer(Supplier<ResponseEntity<?>> call) {
		try {
			return call.get();
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(error(e.getMessage()));
		} catch (IllegalStateException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(error(e.getMessage()));
		} catch (RuntimeException e) {
			log.error("HoStoreOrderAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
