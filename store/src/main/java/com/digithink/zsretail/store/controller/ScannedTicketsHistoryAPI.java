package com.digithink.zsretail.store.controller;

import java.util.Collections;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.core.security.CurrentUserProvider;
import com.digithink.zsretail.store.service.ScannedTicketsHistoryService;
import com.digithink.zsretail.store.service.ScannedTicketsHistoryService.Filters;

import lombok.extern.log4j.Log4j2;

/**
 * 3.2.0, step 7: the scanned tickets taken (meal tickets, gift cheques), read-only, the page "Tickets resto / cadeau
 * encaissés" of the Caisse menu. Every call needs read:admin-scanned-tickets (403 otherwise): the roles give it on the
 * Roles page. Filters (all optional): dateFrom, dateTo (as the tickets history), issuer, paymentMethodId, cashierId,
 * barcode (the whole barcode or its last digits; then the period is left out).
 * <ul>
 * <li>GET /scanned-tickets?...&amp;page=&amp;size=: {content, totalElements, totalPages, number, size, totals};</li>
 * <li>GET /scanned-tickets/export?...: {rows, total, truncated, limit, totals}, every row of the filters (the newest
 * {@link ScannedTicketsHistoryService#MAX_EXPORT} at most), for the Excel file;</li>
 * <li>GET /scanned-tickets/issuers: the issuers met, for the filter.</li>
 * </ul>
 */
@RestController
@RequestMapping("scanned-tickets")
@Log4j2
public class ScannedTicketsHistoryAPI {

	static final String PERMISSION = "read:admin-scanned-tickets";

	@Autowired
	private ScannedTicketsHistoryService service;

	@Autowired
	private CurrentUserProvider currentUserProvider;

	@GetMapping
	public ResponseEntity<?> page(@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo, @RequestParam(required = false) String issuer,
			@RequestParam(required = false) String paymentMethodId, @RequestParam(required = false) String cashierId,
			@RequestParam(required = false) String barcode, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		ResponseEntity<?> refused = refusedWithoutPermission();
		if (refused != null) {
			return refused;
		}
		try {
			return ResponseEntity.ok(service.page(Filters.of(dateFrom, dateTo, issuer, paymentMethodId, cashierId, barcode),
					page, size));
		} catch (RuntimeException e) {
			log.error("ScannedTicketsHistoryAPI::page:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Collections.singletonMap("error", e.getMessage()));
		}
	}

	@GetMapping("/export")
	public ResponseEntity<?> export(@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo, @RequestParam(required = false) String issuer,
			@RequestParam(required = false) String paymentMethodId, @RequestParam(required = false) String cashierId,
			@RequestParam(required = false) String barcode) {
		ResponseEntity<?> refused = refusedWithoutPermission();
		if (refused != null) {
			return refused;
		}
		try {
			return ResponseEntity.ok(service.export(
					Filters.of(dateFrom, dateTo, issuer, paymentMethodId, cashierId, barcode),
					ScannedTicketsHistoryService.MAX_EXPORT));
		} catch (RuntimeException e) {
			log.error("ScannedTicketsHistoryAPI::export:error: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Collections.singletonMap("error", e.getMessage()));
		}
	}

	@GetMapping("/issuers")
	public ResponseEntity<?> issuers() {
		ResponseEntity<?> refused = refusedWithoutPermission();
		return refused != null ? refused : ResponseEntity.ok(service.issuers());
	}

	/** 403 without the page's permission, null with it. */
	private ResponseEntity<?> refusedWithoutPermission() {
		if (currentUserProvider.currentUserHasPermission(PERMISSION)) {
			return null;
		}
		return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Collections.singletonMap("error",
				"The scanned tickets history needs the permission " + PERMISSION));
	}
}
