package com.digithink.zsretail.holink.scheduler;

import java.time.Duration;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwned;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.service.LoyaltyPushService;
import com.digithink.zsretail.holink.service.SalesCopyFinder;
import com.digithink.zsretail.model.enumeration.DataDomain;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, step 4: the loyalty push, a job of the head office link. Exists only when loyalty is owned by the
 * head office. First run {@link #FIRST_DELAY} after the start, then headoffice.loyalty-push.interval-seconds (60) unless
 * a frequency is saved from the page; while more waits, the next run comes after the catch-up delay. The service writes
 * one exchange log row per batch.
 */
@Component
@ConditionalOnHeadOfficeOwned(DataDomain.LOYALTY)
@Order(4)
@Log4j2
public class LoyaltyPushJob implements LinkJob {

	public static final String CODE = LoyaltyPushService.JOB_CODE;

	static final Duration FIRST_DELAY = Duration.ofSeconds(30);

	private final LoyaltyPushService service;

	/** State of the last request; read and written by the ho-link thread only. */
	private HeadOfficeLinkState lastState;

	public LoyaltyPushJob(LoyaltyPushService service) {
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
		LoyaltyPushService.Cycle cycle;
		try {
			cycle = service.runCycle();
		} catch (RuntimeException e) {
			String cause = SalesCopyFinder.cause(e);
			log.warn("Head office loyalty push: cycle failed, retried at the next cycle ({})", cause);
			return LinkJobRun.error("cycle failed (" + cause + ")");
		}
		report(cycle);
		return LinkJobRun.of(result(cycle), message(cycle), cycle.isMore());
	}

	private static LinkJobResult result(LoyaltyPushService.Cycle cycle) {
		if (cycle.getSearchFailure() != null || !cycle.isDelivered()) {
			return LinkJobResult.ERROR;
		}
		return cycle.getMembersRejected() + cycle.getMembersNotBuilt() + cycle.getMovementsRejected()
				+ cycle.getMovementsNotBuilt() > 0 ? LinkJobResult.WARNING : LinkJobResult.SUCCESS;
	}

	private static String message(LoyaltyPushService.Cycle cycle) {
		if (!cycle.isDelivered()) {
			return "not delivered, members and movements stay pending (" + cycle.getState() + ": " + cycle.getMessage()
					+ ")";
		}
		if (cycle.getSearchFailure() != null) {
			return "search failed (" + cycle.getSearchFailure() + ")";
		}
		if (cycle.isIdle()) {
			return "nothing to send";
		}
		return "members: " + cycle.getMembersSent() + " sent (" + cycle.getMerged() + " merged), "
				+ cycle.getMembersRejected() + " rejected; movements: " + cycle.getMovementsSent() + " sent, "
				+ cycle.getMovementsRejected() + " rejected, " + (cycle.getMembersNotBuilt() + cycle.getMovementsNotBuilt())
				+ " not built";
	}

	/** INFO when something was found or sent, or when delivery fails or comes back; DEBUG otherwise. */
	private void report(LoyaltyPushService.Cycle cycle) {
		HeadOfficeLinkState state = cycle.getState();
		if (state != null && state != HeadOfficeLinkState.ONLINE) {
			if (state != lastState) {
				log.info("Head office loyalty push: not delivered, members and movements stay pending ({}: {})", state,
						cycle.getMessage());
			} else {
				log.debug("Head office loyalty push: still not delivered ({}: {})", state, cycle.getMessage());
			}
		} else if (state == HeadOfficeLinkState.ONLINE && lastState != null && lastState != HeadOfficeLinkState.ONLINE) {
			log.info("Head office loyalty push: delivered again");
		}
		if (state != null) {
			lastState = state;
		}
		if (cycle.getSearchFailure() != null) {
			log.warn("Head office loyalty push: search failed, retried at the next cycle ({})", cycle.getSearchFailure());
		}
		if (!cycle.isIdle() && cycle.isDelivered()) {
			log.info("Head office loyalty push: {}; {} new movements found", message(cycle), cycle.getFound());
		} else if (cycle.isIdle()) {
			log.debug("Head office loyalty push: nothing to send");
		}
	}
}
