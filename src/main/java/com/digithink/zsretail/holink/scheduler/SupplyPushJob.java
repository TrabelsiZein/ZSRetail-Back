package com.digithink.zsretail.holink.scheduler;

import java.time.Duration;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSupply;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.service.SalesCopyFinder;
import com.digithink.zsretail.holink.service.SupplyPushService;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 7A: the supply push, a job of the head office link. Exists only on a store whose goods come
 * from the head office. First run {@link #FIRST_DELAY} after the start, then headoffice.supply-push.interval-seconds
 * (60) unless a frequency is saved from the page; while more waits, the next run comes after the catch-up delay. The
 * service writes one exchange log row per batch.
 */
@Component
@ConditionalOnHeadOfficeSupply
@Order(5)
@Log4j2
public class SupplyPushJob implements LinkJob {

	public static final String CODE = SupplyPushService.JOB_CODE;

	static final Duration FIRST_DELAY = Duration.ofSeconds(35);

	private final SupplyPushService service;

	/** State of the last request; read and written by the ho-link thread only. */
	private HeadOfficeLinkState lastState;

	public SupplyPushJob(SupplyPushService service) {
		this.service = service;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public long getDefaultIntervalSeconds() {
		return service.getDefaultIntervalSeconds();
	}

	@Override
	public Duration getFirstDelay() {
		return FIRST_DELAY;
	}

	/** One cycle and its log lines. Never throws. */
	@Override
	public LinkJobRun run() {
		SupplyPushService.Cycle cycle;
		try {
			cycle = service.runCycle();
		} catch (RuntimeException e) {
			String cause = SalesCopyFinder.cause(e);
			log.warn("Head office supply push: cycle failed, retried at the next cycle ({})", cause);
			return LinkJobRun.error("cycle failed (" + cause + ")");
		}
		report(cycle);
		return LinkJobRun.of(result(cycle), message(cycle), cycle.isMore());
	}

	private static LinkJobResult result(SupplyPushService.Cycle cycle) {
		if (!cycle.isDelivered()) {
			return LinkJobResult.ERROR;
		}
		return cycle.getConfirmationsRejected() > 0 ? LinkJobResult.WARNING : LinkJobResult.SUCCESS;
	}

	static String message(SupplyPushService.Cycle cycle) {
		if (!cycle.isDelivered()) {
			return "not delivered, confirmations stay pending (" + cycle.getState() + ": " + cycle.getMessage() + ")";
		}
		if (cycle.isIdle()) {
			return "nothing to send";
		}
		return "BL confirmations: " + cycle.getConfirmationsSent() + " sent, " + cycle.getConfirmationsRejected()
				+ " rejected";
	}

	/** INFO when something was sent, or when delivery fails or comes back; DEBUG otherwise. */
	private void report(SupplyPushService.Cycle cycle) {
		HeadOfficeLinkState state = cycle.getState();
		if (state != null && state != HeadOfficeLinkState.ONLINE) {
			if (state != lastState) {
				log.info("Head office supply push: not delivered, confirmations stay pending ({}: {})", state,
						cycle.getMessage());
			} else {
				log.debug("Head office supply push: still not delivered ({}: {})", state, cycle.getMessage());
			}
		} else if (state == HeadOfficeLinkState.ONLINE && lastState != null && lastState != HeadOfficeLinkState.ONLINE) {
			log.info("Head office supply push: delivered again");
		}
		if (state != null) {
			lastState = state;
		}
		if (!cycle.isIdle() && cycle.isDelivered()) {
			log.info("Head office supply push: {}", message(cycle));
		} else if (cycle.isIdle()) {
			log.debug("Head office supply push: nothing to send");
		}
	}
}
