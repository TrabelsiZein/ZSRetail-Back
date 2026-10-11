package com.digithink.zsretail.holink.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.holink.enumeration.LinkJobResult;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** One job of the head office link on the jobs list (task 2.6): GET admin/holink/jobs. */
@Getter
@AllArgsConstructor
public class LinkJobDTO {

	/** HEARTBEAT, SALES_PUSH... The page shows a translated label per code. */
	private final String code;

	/** Frequency in force, in seconds. */
	private final long intervalSeconds;

	/** The properties value, used while no frequency is saved. */
	private final long defaultIntervalSeconds;

	/** True when a frequency was saved from the page. */
	private final boolean customInterval;

	private final long minimumIntervalSeconds;

	private final long maximumIntervalSeconds;

	/** Start of the last run, store clock; null before the first run. */
	private final LocalDateTime lastRunAt;

	private final LinkJobResult lastResult;

	private final String lastMessage;

	private final Long lastDurationMs;

	/** Next scheduled run, store clock; null while the jobs are not started. */
	private final LocalDateTime nextRunAt;
}
