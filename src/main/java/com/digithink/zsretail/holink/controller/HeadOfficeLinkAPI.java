package com.digithink.zsretail.holink.controller;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.HeadOfficeLinkStatusDTO;
import com.digithink.zsretail.holink.dto.LinkJobDTO;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.scheduler.HeartbeatJob;
import com.digithink.zsretail.holink.scheduler.LinkJob;
import com.digithink.zsretail.holink.scheduler.LinkJobScheduler;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.holink.service.LinkExchangeLog;
import com.digithink.zsretail.holink.service.LinkJobService;
import com.digithink.zsretail.holink.service.SalesCopyFinder;
import com.digithink.zsretail.holink.service.SalesPushService;

/**
 * Head office plan, task 1.5: the store's "Head office link" page. Exists only when headoffice.url is set (otherwise
 * 404). Admin endpoints (JWT, like the other admin APIs); nothing in the selling path calls them. The key is never
 * returned. Task 2.4: the status also gives the sales copy counts. Task 2.6: the jobs (frequency, run now) and the
 * exchange log. Contract: docs/modules/head-office.md, "Head office link page".
 */
@RestController
@RequestMapping("admin/holink")
@ConditionalOnHeadOfficeLink
public class HeadOfficeLinkAPI {

	private final HeadOfficeLinkStatus status;
	private final LinkJobScheduler jobs;
	private final LinkJobService jobService;
	private final LinkExchangeLog exchangeLog;
	private final HeadOfficeClient client;

	/** Task 2.4: present only when the store copies its sales to the head office. */
	private final Optional<SalesPushService> salesPush;

	public HeadOfficeLinkAPI(HeadOfficeLinkStatus status, LinkJobScheduler jobs, LinkJobService jobService,
			LinkExchangeLog exchangeLog, HeadOfficeClient client, Optional<SalesPushService> salesPush) {
		this.status = status;
		this.jobs = jobs;
		this.jobService = jobService;
		this.exchangeLog = exchangeLog;
		this.client = client;
		this.salesPush = salesPush;
	}

	@GetMapping("/status")
	public HeadOfficeLinkStatusDTO status() {
		return toDto(status.get());
	}

	/** One heartbeat now, on the ho-link thread (the HEARTBEAT job's run now); answers the status after it. */
	@PostMapping("/check")
	public HeadOfficeLinkStatusDTO check() {
		jobs.job(HeartbeatJob.CODE).ifPresent(jobs::runNow);
		return toDto(status.get());
	}

	/** Task 2.6: the jobs of this store, in order. Which jobs exist is decided by the settings. */
	@GetMapping("/jobs")
	public List<LinkJobDTO> jobs() {
		return jobs.views();
	}

	/**
	 * Task 2.6: saves a job's frequency, body {"intervalSeconds": n}; null goes back to the default. Applies at once:
	 * the next run comes one new frequency from now.
	 */
	@PutMapping("/jobs/{code}/interval")
	public ResponseEntity<?> setInterval(@PathVariable String code, @RequestBody(required = false) Map<String, Object> body) {
		Optional<LinkJob> job = jobs.job(code);
		if (!job.isPresent()) {
			return notFound(code);
		}
		Long seconds;
		Object value = body == null ? null : body.get("intervalSeconds");
		try {
			seconds = value == null ? null : Long.valueOf(value.toString().trim());
		} catch (NumberFormatException e) {
			return badRequest("intervalSeconds must be a whole number of seconds");
		}
		try {
			jobService.setInterval(job.get(), seconds);
		} catch (IllegalArgumentException e) {
			return badRequest(e.getMessage());
		} catch (RuntimeException e) {
			return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
					.body(Collections.singletonMap("error", "frequency not saved (" + SalesCopyFinder.cause(e) + ")"));
		}
		jobs.reschedule(job.get());
		return ResponseEntity.ok(jobs.view(job.get()));
	}

	/** Task 2.6: runs a job now on the ho-link thread (waited for up to 30 s); answers the job after the run. */
	@PostMapping("/jobs/{code}/run")
	public ResponseEntity<?> runNow(@PathVariable String code) {
		Optional<LinkJob> job = jobs.job(code);
		if (!job.isPresent()) {
			return notFound(code);
		}
		jobs.runNow(job.get());
		return ResponseEntity.ok(jobs.view(job.get()));
	}

	/** Task 2.6: the exchange log, newest first, with filters; 400 on a filter that cannot be read. */
	@GetMapping("/log")
	public ResponseEntity<?> log(@RequestParam(required = false) String job,
			@RequestParam(required = false) String result, @RequestParam(required = false) String dateFrom,
			@RequestParam(required = false) String dateTo, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		try {
			return ResponseEntity.ok(exchangeLog.search(job, result, dateFrom, dateTo, page, size));
		} catch (IllegalArgumentException e) {
			return badRequest(e.getMessage());
		}
	}

	private static ResponseEntity<?> notFound(String code) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(Collections.singletonMap("error", "No job '" + code + "' on this store"));
	}

	private static ResponseEntity<?> badRequest(String message) {
		return ResponseEntity.badRequest().body(Collections.singletonMap("error", message));
	}

	private HeadOfficeLinkStatusDTO toDto(HeadOfficeLinkStatus.Snapshot snapshot) {
		Map<SalesCopyStatus, Long> counts;
		try {
			counts = salesPush.map(SalesPushService::counts).orElse(null);
		} catch (RuntimeException e) {
			counts = null; // the status is still answered
		}
		long interval = jobs.job(HeartbeatJob.CODE).map(jobService::intervalSeconds).orElse(0L);
		return new HeadOfficeLinkStatusDTO(snapshot.getState(), snapshot.getLastMessage(), snapshot.getLastAttempt(),
				snapshot.getLastSuccess(), snapshot.getServerTime(), client.getBaseUrl(), storeCode(), interval,
				count(counts, SalesCopyStatus.PENDING), count(counts, SalesCopyStatus.SENT),
				count(counts, SalesCopyStatus.ERROR));
	}

	private static Long count(Map<SalesCopyStatus, Long> counts, SalesCopyStatus status) {
		return counts == null ? null : counts.get(status);
	}

	/** DEFAULT_LOCATION; null when empty or when it cannot be read (the state then says why). */
	private String storeCode() {
		try {
			return client.readStoreCode();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
