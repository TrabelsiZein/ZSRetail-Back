package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.service.HoLoyaltyReportService;

/**
 * Head office plan, step 5: the loyalty overspend report of a head office (JWT, like the other admin APIs; 404 on a
 * store). Page permission read:admin-headoffice-loyalty-overspends (the frontend checks it, as everywhere).
 */
@RestController
@RequestMapping("admin/headoffice/loyalty")
@ConditionalOnHeadOffice
public class HoLoyaltyReportAPI {

	private final HoLoyaltyReportService service;

	public HoLoyaltyReportAPI(HoLoyaltyReportService service) {
		this.service = service;
	}

	@GetMapping("/overspends")
	public ResponseEntity<?> overspends(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, @RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
			@RequestParam(required = false) String search) {
		try {
			return ResponseEntity.ok(service.overspends(page, size, storeId, dateFrom, dateTo, search));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
		}
	}

	@GetMapping("/overspends/count")
	public ResponseEntity<?> count(@RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo) {
		try {
			return ResponseEntity.ok(service.count(dateFrom, dateTo));
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
		}
	}
}
