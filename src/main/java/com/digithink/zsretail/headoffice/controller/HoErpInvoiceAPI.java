package com.digithink.zsretail.headoffice.controller;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeErpSupply;
import com.digithink.zsretail.erp.controller.ErpSyncJobAdminController;
import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.headoffice.service.HoErpInvoiceService;

import lombok.extern.log4j.Log4j2;

/**
 * Invoices from the ERP, step (b): the REST of the head office page of step (d) (JWT, like the other admin APIs; read
 * permission read:admin-headoffice-erp-invoices in the frontend). Only on a head office with headoffice.supply.source=ERP:
 * elsewhere these URLs answer 404. Errors {"error"}: 400 invalid parameters, 404 unknown invoice.
 */
@RestController
@RequestMapping("admin/headoffice/erp-invoices")
@ConditionalOnHeadOfficeErpSupply
@Log4j2
public class HoErpInvoiceAPI {

	private static final String NOT_FOUND = "Not found";

	private final HoErpInvoiceService service;
	private final ErpSyncJobRepository jobs;
	private final ErpSyncJobAdminController jobAdmin;

	public HoErpInvoiceAPI(HoErpInvoiceService service, ErpSyncJobRepository jobs, ErpSyncJobAdminController jobAdmin) {
		this.service = service;
		this.jobs = jobs;
		this.jobAdmin = jobAdmin;
	}

	/**
	 * A page {content, totalElements, totalPages, number, size, lastRun}, newest number first. status READ, SENT,
	 * RECEIVED; mapping ASSIGNED, NO_CUSTOMER, NO_STORE, STORE_INACTIVE, STORE_NOT_SUPPLIED; held true or false.
	 */
	@GetMapping
	public ResponseEntity<?> list(@RequestParam(required = false) Long storeId,
			@RequestParam(required = false) String status, @RequestParam(required = false) String mapping,
			@RequestParam(required = false) Boolean held, @RequestParam(required = false) String search,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		return answer(() -> ResponseEntity.ok(service.list(storeId, status, mapping, held, search, page, size)));
	}

	@GetMapping("/{id}")
	public ResponseEntity<?> get(@PathVariable Long id) {
		return answer(() -> service.get(id).<ResponseEntity<?>>map(ResponseEntity::ok).orElseGet(this::notFound));
	}

	/** The second step alone: every invoice without a store and not held looks for its store again. 200 the summary. */
	@PostMapping("/match-stores")
	public ResponseEntity<?> matchStores() {
		return answer(() -> ResponseEntity.ok(service.matchStoresNow()));
	}

	/**
	 * The ERP job IMPORT_SUPPLY_INVOICES now, through the ERP jobs' run now (POST admin/erp/jobs/{id}/run: admin only,
	 * its status, next run and communications log as from the ERP jobs page). 200 {job, summary}; the job's own answers
	 * otherwise (400 {"error"} on a warning); 404 when the job does not exist.
	 */
	@PostMapping("/run-now")
	public ResponseEntity<?> runNow() {
		Optional<ErpSyncJob> job = jobs.findByJobType(ErpSyncJobType.IMPORT_SUPPLY_INVOICES);
		if (!job.isPresent()) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(error("The ERP job " + ErpSyncJobType.IMPORT_SUPPLY_INVOICES + " does not exist"));
		}
		try {
			ResponseEntity<?> ran = jobAdmin.runJobNow(job.get().getId());
			if (!ran.getStatusCode().is2xxSuccessful()) {
				return ran;
			}
			Map<String, Object> body = new LinkedHashMap<>();
			body.put("job", ran.getBody());
			body.put("summary", service.getLastRun());
			return ResponseEntity.ok(body);
		} catch (ResponseStatusException e) {
			return ResponseEntity.status(e.getStatus()).body(error(e.getReason()));
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
			log.error("HoErpInvoiceAPI: " + e.getMessage(), e);
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(error(String.valueOf(e.getMessage())));
		}
	}

	private static Map<String, String> error(String message) {
		return Collections.singletonMap("error", message);
	}
}
