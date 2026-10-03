package com.digithink.zsretail.holink.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

import javax.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.HeadOfficeCallResult;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.service.HeadOfficeLinkStatus;

import lombok.extern.log4j.Log4j2;

/**
 * Heartbeat to the head office (task 1.4): first call {@link #FIRST_DELAY} after the start, then every
 * headoffice.heartbeat-interval-seconds (fixed delay).
 *
 * Runs on its own thread (ho-link-1), not with {@code @Scheduled}: the shared scheduler has one thread, and a call
 * blocked up to 15 s would delay the ERP and franchise jobs, while a long ERP job would hold the heartbeat back. The
 * thread pool is deliberately not a bean: a TaskScheduler bean would replace Spring Boot's default one, and the
 * existing {@code @Scheduled} jobs would move onto it.
 */
@Component
@ConditionalOnHeadOfficeLink
@Log4j2
public class HeadOfficeHeartbeatScheduler {

	static final Duration FIRST_DELAY = Duration.ofSeconds(15);

	private final HeadOfficeClient client;
	private final HeadOfficeLinkStatus status;
	private final Duration interval;
	private ThreadPoolTaskScheduler taskScheduler;

	public HeadOfficeHeartbeatScheduler(HeadOfficeClient client, HeadOfficeLinkStatus status,
			@Value("${headoffice.heartbeat-interval-seconds:60}") long intervalSeconds) {
		this.client = client;
		this.status = status;
		this.interval = Duration.ofSeconds(intervalSeconds);
	}

	@EventListener(ApplicationReadyEvent.class)
	public synchronized void start() {
		if (taskScheduler != null) {
			return;
		}
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("ho-link-");
		scheduler.setDaemon(true);
		scheduler.initialize();
		scheduler.scheduleWithFixedDelay(this::beat, Instant.now().plus(FIRST_DELAY), interval);
		taskScheduler = scheduler;
		log.info("Head office link: heartbeat to {} every {} s", client.getBaseUrl(), interval.getSeconds());
	}

	@PreDestroy
	public synchronized void stop() {
		if (taskScheduler != null) {
			taskScheduler.shutdown();
			taskScheduler = null;
		}
	}

	/** One heartbeat. INFO when the state changes, DEBUG otherwise. */
	void beat() {
		HeadOfficeCallResult result = client.heartbeat();
		HeadOfficeLinkState previous = status.record(result, LocalDateTime.now());
		String detail = result.getState() == HeadOfficeLinkState.ONLINE
				? "head office time " + result.getServerTime()
				: result.getMessage();
		if (previous != result.getState()) {
			log.info("Head office link: {} -> {} ({})", previous, result.getState(), detail);
		} else {
			log.debug("Head office link: {} ({})", result.getState(), detail);
		}
	}
}
