package com.digithink.zsretail.holink.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.HeadOfficeCallResult;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.holink.service.LinkExchangeLog;

import lombok.extern.log4j.Log4j2;

/**
 * The heartbeat to the head office (task 1.4), a job of the head office link since task 2.6: first run
 * {@link #FIRST_DELAY} after the start, then every headoffice.heartbeat-interval-seconds unless a frequency is saved
 * from the page. Each heartbeat updates the in-memory link status; one INFO line and one exchange log row when the
 * state changes, DEBUG and no row otherwise.
 */
@Component
@ConditionalOnHeadOfficeLink
@Order(1)
@Log4j2
public class HeartbeatJob implements LinkJob {

	public static final String CODE = "HEARTBEAT";

	static final Duration FIRST_DELAY = Duration.ofSeconds(15);

	private final HeadOfficeClient client;
	private final HeadOfficeLinkStatus status;
	private final LinkExchangeLog exchangeLog;
	private final long defaultIntervalSeconds;

	public HeartbeatJob(HeadOfficeClient client, HeadOfficeLinkStatus status, LinkExchangeLog exchangeLog,
			@Value("${headoffice.heartbeat-interval-seconds:60}") long intervalSeconds) {
		this.client = client;
		this.status = status;
		this.exchangeLog = exchangeLog;
		this.defaultIntervalSeconds = intervalSeconds;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public long getDefaultIntervalSeconds() {
		return defaultIntervalSeconds;
	}

	@Override
	public Duration getFirstDelay() {
		return FIRST_DELAY;
	}

	/** One heartbeat. INFO and an exchange log row when the state changes, DEBUG otherwise. */
	@Override
	public LinkJobRun run() {
		LocalDateTime at = LocalDateTime.now();
		long started = System.nanoTime();
		HeadOfficeCallResult result = client.heartbeat();
		long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		HeadOfficeLinkState previous = status.record(result, at);
		boolean online = result.getState() == HeadOfficeLinkState.ONLINE;
		String detail = online ? "head office time " + result.getServerTime() : result.getMessage();
		if (previous != result.getState()) {
			log.info("Head office link: {} -> {} ({})", previous, result.getState(), detail);
			exchangeLog.record(CODE, ExchangeDirection.UP, 0, online ? LinkJobResult.SUCCESS : LinkJobResult.ERROR,
					online ? null : result.getState() + ": " + result.getMessage(), at, durationMs);
		} else {
			log.debug("Head office link: {} ({})", result.getState(), detail);
		}
		return online ? LinkJobRun.success(HeadOfficeLinkState.ONLINE.name())
				: LinkJobRun.error(result.getState() + ": " + result.getMessage());
	}
}
