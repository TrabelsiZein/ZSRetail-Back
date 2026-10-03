package com.digithink.zsretail.headoffice.controller;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.dto.DashboardTodayDTO;
import com.digithink.zsretail.headoffice.service.ConsolidatedSalesService;
import com.digithink.zsretail.headoffice.service.ConsolidatedSalesService.HistoryQuery;

/**
 * Head office plan, task 2.5: the API of the head office pages Tickets history, Sessions history and Returns, the
 * store filter and the home cards. Head office only (on a store these URLs answer 404). Admin endpoints (JWT, like the
 * other admin APIs); they read the consolidation tables only. Contract: docs/modules/head-office.md, "Consolidated
 * sales API".
 */
@RestController
@RequestMapping("admin/headoffice")
@ConditionalOnHeadOffice
public class ConsolidatedSalesAPI {

	private final ConsolidatedSalesService service;

	public ConsolidatedSalesAPI(ConsolidatedSalesService service) {
		this.service = service;
	}

	@GetMapping("/tickets")
	public ResponseEntity<?> tickets(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, @RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
			@RequestParam(required = false) String salesNumber, @RequestParam(required = false) String status,
			@RequestParam(required = false) String sessionNumber) {
		return list(() -> service.tickets(
				HistoryQuery.of(page, size, storeId, dateFrom, dateTo, salesNumber, status, sessionNumber)));
	}

	@GetMapping("/tickets/{id}")
	public ResponseEntity<?> ticket(@PathVariable Long id) {
		return detail(service.ticket(id));
	}

	@GetMapping("/sessions")
	public ResponseEntity<?> sessions(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, @RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
			@RequestParam(required = false) String sessionNumber, @RequestParam(required = false) String status) {
		return list(() -> service
				.sessions(HistoryQuery.of(page, size, storeId, dateFrom, dateTo, sessionNumber, status, null)));
	}

	@GetMapping("/sessions/{id}")
	public ResponseEntity<?> session(@PathVariable Long id) {
		return detail(service.session(id));
	}

	@GetMapping("/returns")
	public ResponseEntity<?> returns(@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size, @RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String dateFrom, @RequestParam(required = false) String dateTo,
			@RequestParam(required = false) String returnNumber, @RequestParam(required = false) String status,
			@RequestParam(required = false) String sessionNumber) {
		return list(() -> service.returns(
				HistoryQuery.of(page, size, storeId, dateFrom, dateTo, returnNumber, status, sessionNumber)));
	}

	@GetMapping("/returns/{id}")
	public ResponseEntity<?> returnDetail(@PathVariable Long id) {
		return detail(service.returnDetail(id));
	}

	/** The stores for the filters of the three pages: [{id, code, name, active}], by code. */
	@GetMapping("/store-options")
	public List<Map<String, Object>> storeOptions() {
		return service.storeOptions();
	}

	/** The home cards, all stores together; same fields as the store's GET admin/dashboard/today. */
	@GetMapping("/dashboard/today")
	public DashboardTodayDTO dashboardToday() {
		return service.dashboardToday(LocalDate.now());
	}

	/** 400 {"error": ...} when a filter cannot be read. */
	private static ResponseEntity<?> list(Supplier<Map<String, Object>> call) {
		try {
			return ResponseEntity.ok(call.get());
		} catch (IllegalArgumentException e) {
			return ResponseEntity.badRequest().body(Collections.singletonMap("error", e.getMessage()));
		}
	}

	private static ResponseEntity<?> detail(Optional<Map<String, Object>> found) {
		return found.<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(Collections.singletonMap("error", "Not found")));
	}
}
