package com.digithink.zsretail.store.holink.scheduler;

import java.time.Duration;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.store.holink.dto.LinkJobRun;
import com.digithink.zsretail.store.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.store.holink.service.CommunicationPushService;
import com.digithink.zsretail.store.holink.service.SalesCopyFinder;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication step 6: the communication push, a job of the head office link, on a store whose loyalty is the
 * head office's with the module on (Installation.COMMUNICATION_PUSHED_UP). Every {@link #DEFAULT_INTERVAL_SECONDS}
 * unless a frequency is saved from the Jobs page (no property). While the head office takes no events the run is a
 * quiet SUCCESS (paused); an unreachable head office is an ERROR run, as the other jobs.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_PUSHED_UP)
@Order(6)
@Log4j2
public class CommunicationPushJob implements LinkJob {

	public static final String CODE = CommunicationPushService.JOB_CODE;
	static final long DEFAULT_INTERVAL_SECONDS = 60;
	static final Duration FIRST_DELAY = Duration.ofSeconds(45);

	private final CommunicationPushService service;

	public CommunicationPushJob(CommunicationPushService service) {
		this.service = service;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public long getDefaultIntervalSeconds() {
		return DEFAULT_INTERVAL_SECONDS;
	}

	@Override
	public Duration getFirstDelay() {
		return FIRST_DELAY;
	}

	@Override
	public LinkJobRun run() {
		CommunicationPushService.Cycle cycle;
		try {
			cycle = service.runCycle();
		} catch (RuntimeException e) {
			String cause = SalesCopyFinder.cause(e);
			log.warn("Head office communication push: cycle failed, retried at the next cycle ({})", cause);
			return LinkJobRun.error("cycle failed (" + cause + ")");
		}
		String ended = cycle.getEnded() > 0 ? cycle.getEnded() + " event(s) ended (too old); " : "";
		if (cycle.isPaused() || cycle.isNotTaken()) {
			return LinkJobRun.success(ended + "the head office takes no events: paused");
		}
		if (cycle.getFailure() != null) {
			return LinkJobRun.error(ended + cycle.getFailure());
		}
		if (cycle.getTaken() + cycle.getRefused() > 0) {
			log.info("Head office communication push: {} event(s) taken, {} refused", cycle.getTaken(),
					cycle.getRefused());
		}
		return LinkJobRun.of(cycle.getRefused() > 0 ? LinkJobResult.WARNING : LinkJobResult.SUCCESS,
				ended + cycle.getTaken() + " taken, " + cycle.getRefused() + " refused", cycle.isMore());
	}

	/** "Run now" from the Jobs page: the pause lifted, the head office asked at once (its module back on, say). */
	@Override
	public LinkJobRun runNow() {
		service.resume();
		return run();
	}
}
