package com.digithink.zsretail.holink.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.TriggerContext;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSalesPush;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.service.SalesCopyFinder;
import com.digithink.zsretail.holink.service.SalesPushService;
import com.digithink.zsretail.holink.service.SalesPushSettings;

import lombok.extern.log4j.Log4j2;

/**
 * Runs the sales push (task 2.4) on the ho-link thread, after the heartbeat: first cycle {@link #FIRST_DELAY} after
 * the start (the first heartbeat comes at 15 s), then headoffice.sales-push.interval-seconds after the end of the
 * previous cycle, read at each cycle (task 2.6 makes it editable). While a long history is caught up the next cycle
 * comes {@link #CATCH_UP_DELAY} later; the heartbeat, due in between, runs first. A cycle never throws.
 */
@Component
@ConditionalOnHeadOfficeSalesPush
@Log4j2
public class SalesPushScheduler {

	static final Duration FIRST_DELAY = Duration.ofSeconds(20);
	static final Duration CATCH_UP_DELAY = Duration.ofSeconds(5);

	private final SalesPushService service;
	private final SalesPushSettings settings;
	private final HeadOfficeHeartbeatScheduler linkThread;

	private Instant startedAt;
	private volatile boolean catchingUp;

	/** State of the last request; read and written by the ho-link thread only. */
	private HeadOfficeLinkState lastState;

	public SalesPushScheduler(SalesPushService service, SalesPushSettings settings,
			HeadOfficeHeartbeatScheduler linkThread) {
		this.service = service;
		this.settings = settings;
		this.linkThread = linkThread;
	}

	@EventListener(ApplicationReadyEvent.class)
	public synchronized void start() {
		if (startedAt != null) {
			return;
		}
		startedAt = Instant.now();
		linkThread.scheduleOnLinkThread(this::cycle, this::nextRun);
		log.info("Head office sales push: every {} s, {} documents per request, {}", settings.getIntervalSeconds(),
				settings.getBatchSize(), settings.getFromDate() == null ? "whole history"
						: "documents from " + settings.getFromDate().toLocalDate());
	}

	/** First run {@link #FIRST_DELAY} after the start, then {@link #nextDelay()} after the end of the previous one. */
	synchronized Date nextRun(TriggerContext context) {
		Date last = context.lastCompletionTime();
		if (last == null) {
			return Date.from(startedAt.plus(FIRST_DELAY));
		}
		return new Date(last.getTime() + nextDelay().toMillis());
	}

	Duration nextDelay() {
		return catchingUp ? CATCH_UP_DELAY : Duration.ofSeconds(settings.getIntervalSeconds());
	}

	/** One cycle. Never throws: a failure is logged and the next cycle comes at the usual interval. */
	void cycle() {
		try {
			SalesPushService.Cycle cycle = service.runCycle();
			catchingUp = cycle.isMore();
			report(cycle);
		} catch (RuntimeException e) {
			catchingUp = false;
			log.warn("Head office sales push: cycle failed, retried at the next cycle ({})", SalesCopyFinder.cause(e));
		}
	}

	/** INFO when something was found or sent, or when delivery fails or comes back; DEBUG otherwise. */
	private void report(SalesPushService.Cycle cycle) {
		if (cycle.getDiscoveryFailure() != null) {
			log.warn("Head office sales push: search failed, retried at the next cycle ({})",
					cycle.getDiscoveryFailure());
		}
		HeadOfficeLinkState state = cycle.getState();
		if (state != null && state != HeadOfficeLinkState.ONLINE) {
			if (state != lastState) {
				log.info("Head office sales push: not delivered, documents stay pending ({}: {})", state,
						cycle.getMessage());
			} else {
				log.debug("Head office sales push: still not delivered ({}: {})", state, cycle.getMessage());
			}
		} else if (state == HeadOfficeLinkState.ONLINE && lastState != null && lastState != HeadOfficeLinkState.ONLINE) {
			log.info("Head office sales push: delivered again");
		}
		if (state != null) {
			lastState = state;
		}
		if (cycle.getSent() + cycle.getRejected() + cycle.getNotBuilt() + cycle.getUnchanged() + cycle.getFound()
				+ cycle.getChanged() > 0) {
			Map<SalesCopyStatus, Long> counts = service.counts();
			log.info("Head office sales push: {} sent, {} rejected, {} not built, {} unchanged; {} new, {} changed;"
					+ " now {} pending, {} sent, {} in error", cycle.getSent(), cycle.getRejected(), cycle.getNotBuilt(),
					cycle.getUnchanged(), cycle.getFound(), cycle.getChanged(), counts.get(SalesCopyStatus.PENDING),
					counts.get(SalesCopyStatus.SENT), counts.get(SalesCopyStatus.ERROR));
		} else if (cycle.isIdle()) {
			log.debug("Head office sales push: nothing to send");
		}
	}
}
