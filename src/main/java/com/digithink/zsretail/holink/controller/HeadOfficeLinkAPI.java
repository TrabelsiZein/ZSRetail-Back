package com.digithink.zsretail.holink.controller;

import java.util.Map;
import java.util.Optional;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.HeadOfficeLinkStatusDTO;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.scheduler.HeadOfficeHeartbeatScheduler;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.holink.service.SalesPushService;

/**
 * Head office plan, task 1.5: the store's "Head office link" page. Exists only when headoffice.url is set (otherwise
 * 404). Admin endpoints (JWT, like the other admin APIs); nothing in the selling path calls them. The key is never
 * returned. Task 2.4: the status also gives the sales copy counts.
 */
@RestController
@RequestMapping("admin/holink")
@ConditionalOnHeadOfficeLink
public class HeadOfficeLinkAPI {

	private final HeadOfficeLinkStatus status;
	private final HeadOfficeHeartbeatScheduler scheduler;
	private final HeadOfficeClient client;

	/** Task 2.4: present only when the store copies its sales to the head office. */
	private final Optional<SalesPushService> salesPush;

	public HeadOfficeLinkAPI(HeadOfficeLinkStatus status, HeadOfficeHeartbeatScheduler scheduler,
			HeadOfficeClient client, Optional<SalesPushService> salesPush) {
		this.status = status;
		this.scheduler = scheduler;
		this.client = client;
		this.salesPush = salesPush;
	}

	@GetMapping("/status")
	public HeadOfficeLinkStatusDTO status() {
		return toDto(status.get());
	}

	/** One heartbeat now, on the heartbeat thread; answers the status after it. */
	@PostMapping("/check")
	public HeadOfficeLinkStatusDTO check() {
		return toDto(scheduler.checkNow());
	}

	private HeadOfficeLinkStatusDTO toDto(HeadOfficeLinkStatus.Snapshot snapshot) {
		Map<SalesCopyStatus, Long> counts;
		try {
			counts = salesPush.map(SalesPushService::counts).orElse(null);
		} catch (RuntimeException e) {
			counts = null; // the status is still answered
		}
		return new HeadOfficeLinkStatusDTO(snapshot.getState(), snapshot.getLastMessage(), snapshot.getLastAttempt(),
				snapshot.getLastSuccess(), snapshot.getServerTime(), client.getBaseUrl(), storeCode(),
				scheduler.getIntervalSeconds(), count(counts, SalesCopyStatus.PENDING), count(counts, SalesCopyStatus.SENT),
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
