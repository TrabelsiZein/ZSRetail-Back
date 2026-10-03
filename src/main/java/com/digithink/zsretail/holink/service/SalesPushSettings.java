package com.digithink.zsretail.holink.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSalesPush;
import com.digithink.zsretail.config.NodeOwnership;

/**
 * Settings of the sales copies (tasks 2.1, 2.4), checked at startup by {@link NodeOwnership}. Since task 2.6 the
 * interval here is only the default of the SALES_PUSH job: a frequency saved from the page wins (LinkJobService). See
 * docs/modules/head-office.md, "Sales copies".
 */
@Component
@ConditionalOnHeadOfficeSalesPush
public class SalesPushSettings {

	private final LocalDateTime fromDate;
	private final int batchSize;
	private final long intervalSeconds;

	public SalesPushSettings(@Value("${headoffice.sales-push.from-date:}") String fromDate,
			@Value("${headoffice.sales-push.batch-size:50}") int batchSize,
			@Value("${headoffice.sales-push.interval-seconds:60}") long intervalSeconds) {
		LocalDate date = NodeOwnership.parseSalesPushFromDate(fromDate);
		this.fromDate = date == null ? null : date.atStartOfDay();
		this.batchSize = batchSize;
		this.intervalSeconds = intervalSeconds;
	}

	/** Start of the day headoffice.sales-push.from-date; older documents are ignored. Null: the whole history. */
	public LocalDateTime getFromDate() {
		return fromDate;
	}

	/** Documents per request (headoffice.sales-push.batch-size, default 50). */
	public int getBatchSize() {
		return batchSize;
	}

	/** Default seconds between two push cycles (headoffice.sales-push.interval-seconds, default 60). */
	public long getDefaultIntervalSeconds() {
		return intervalSeconds;
	}
}
