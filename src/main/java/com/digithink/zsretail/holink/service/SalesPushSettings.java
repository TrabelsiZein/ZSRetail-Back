package com.digithink.zsretail.holink.service;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSalesPush;
import com.digithink.zsretail.config.NodeOwnership;

/**
 * Settings of the sales copies (tasks 2.1, 2.4), checked at startup by {@link NodeOwnership}. The interval is read
 * here at each scheduling, so task 2.6 can make it editable from a page by changing this class only. See
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

	/** Seconds between two push cycles (headoffice.sales-push.interval-seconds, default 60). Read at each cycle. */
	public long getIntervalSeconds() {
		return intervalSeconds;
	}
}
