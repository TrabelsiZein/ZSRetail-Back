package com.digithink.zsretail.holink.dto;

import java.time.LocalDateTime;
import java.util.Map;

import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** Answer of GET admin/holink/status and POST admin/holink/check (task 1.5). Never carries the API key. */
@Getter
@AllArgsConstructor
public class HeadOfficeLinkStatusDTO {

	private final HeadOfficeLinkState state;

	/** Message of the last call, as written by the store (English); null when it was ONLINE. */
	private final String message;

	/** Store clock; null before the first heartbeat. */
	private final LocalDateTime lastAttempt;

	/** Store clock of the last ONLINE answer; null until then. */
	private final LocalDateTime lastSuccess;

	/** Head office time sent with the last ONLINE answer (ISO-8601 with offset); null until then. */
	private final String serverTime;

	/** headoffice.url without its trailing slashes. */
	private final String headOfficeUrl;

	/** DEFAULT_LOCATION now; null when empty or unreadable. */
	private final String storeCode;

	private final long intervalSeconds;

	/** Task 2.4: sales copies waiting to be sent; null when this store does not copy its sales to the head office. */
	private final Long pendingCount;

	/** Task 2.4: sales copies accepted by the head office; null when this store does not copy its sales. */
	private final Long sentCount;

	/** Task 2.4: sales copies rejected or not built, retried; null when this store does not copy its sales. */
	private final Long errorCount;

	/**
	 * Task 3.5: per domain pulled from the head office, the records received by status, e.g. {"PROMOTIONS": {"APPLIED":
	 * 12, "WAITING": 1, "ERROR": 0}}; null when this store pulls nothing, or when the counts cannot be read.
	 */
	private final Map<String, Map<String, Long>> received;

	/**
	 * Step 4: when loyalty is owned by the head office, what the store sends up and its rights: {"members": {"PENDING":
	 * 0, "SENT": 3, "ERROR": 0}, "movements": {...}, "canEditMembers": true, "canAdjustPoints": false} (rights null
	 * before the first heartbeat answer); null otherwise, or when the counts cannot be read.
	 */
	private final Map<String, Object> loyalty;

	/**
	 * Step 6: when the catalogue is the head office's, {fromHeadOffice, linkState, mayChangePrices, canPurchase,
	 * ownPriceCount, salesPriceRowsOnHeadOfficeItems} (rights: the saved values, null when never received); null
	 * otherwise, or when it cannot be read.
	 */
	private final Map<String, Object> catalogue;
}
