package com.digithink.zsretail.holink.scheduler;

import java.time.Duration;
import java.util.Map;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSalesPush;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.service.LinkExchangeLog;
import com.digithink.zsretail.holink.service.SalesCopyFinder;
import com.digithink.zsretail.holink.service.SalesPushService;
import com.digithink.zsretail.holink.service.SalesPushSettings;

import lombok.extern.log4j.Log4j2;

/**
 * The sales push (task 2.4), a job of the head office link since task 2.6. Exists only when the store copies its
 * sales to the head office (decision 4). First run {@link #FIRST_DELAY} after the start (after the first heartbeat),
 * then headoffice.sales-push.interval-seconds unless a frequency is saved from the page; while a long history is
 * caught up, the next run comes after the catch-up delay. One exchange log row per batch that sent something or failed
 * (and for a failed search), written after the tracking rows are saved: a log failure cannot undo or stop the push.
 */
@Component
@ConditionalOnHeadOfficeSalesPush
@Order(2)
@Log4j2
public class SalesPushJob implements LinkJob {

	public static final String CODE = "SALES_PUSH";

	static final Duration FIRST_DELAY = Duration.ofSeconds(20);

	private final SalesPushService service;
	private final SalesPushSettings settings;
	private final LinkExchangeLog exchangeLog;

	/** State of the last request; read and written by the ho-link thread only. */
	private HeadOfficeLinkState lastState;

	public SalesPushJob(SalesPushService service, SalesPushSettings settings, LinkExchangeLog exchangeLog) {
		this.service = service;
		this.settings = settings;
		this.exchangeLog = exchangeLog;
	}

	@Override
	public String getCode() {
		return CODE;
	}

	@Override
	public long getDefaultIntervalSeconds() {
		return settings.getDefaultIntervalSeconds();
	}

	@Override
	public Duration getFirstDelay() {
		return FIRST_DELAY;
	}

	/** One push cycle, its log lines and exchange rows. Never throws. */
	@Override
	public LinkJobRun run() {
		SalesPushService.Cycle cycle;
		try {
			cycle = service.runCycle();
		} catch (RuntimeException e) {
			String cause = SalesCopyFinder.cause(e);
			log.warn("Head office sales push: cycle failed, retried at the next cycle ({})", cause);
			return LinkJobRun.error("cycle failed (" + cause + ")");
		}
		for (SalesPushService.Exchange exchange : cycle.getExchanges()) {
			exchangeLog.record(CODE, ExchangeDirection.UP, exchange.getRecords(), exchange.getResult(),
					exchange.getError(), exchange.getAt(), exchange.getDurationMs());
		}
		report(cycle);
		return LinkJobRun.of(result(cycle), message(cycle), cycle.isMore());
	}

	/** ERROR when the search failed or nothing was delivered; WARNING when documents failed; SUCCESS otherwise. */
	private static LinkJobResult result(SalesPushService.Cycle cycle) {
		if (cycle.getDiscoveryFailure() != null
				|| cycle.getState() != null && cycle.getState() != HeadOfficeLinkState.ONLINE) {
			return LinkJobResult.ERROR;
		}
		return cycle.getRejected() + cycle.getNotBuilt() > 0 ? LinkJobResult.WARNING : LinkJobResult.SUCCESS;
	}

	private static String message(SalesPushService.Cycle cycle) {
		if (cycle.getState() != null && cycle.getState() != HeadOfficeLinkState.ONLINE) {
			return "not delivered, documents stay pending (" + cycle.getState() + ": " + cycle.getMessage() + ")";
		}
		if (cycle.getDiscoveryFailure() != null) {
			return "search failed (" + cycle.getDiscoveryFailure() + ")";
		}
		if (cycle.isIdle()) {
			return "nothing to send";
		}
		return cycle.getSent() + " sent, " + cycle.getRejected() + " rejected, " + cycle.getNotBuilt() + " not built, "
				+ cycle.getUnchanged() + " unchanged";
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
			try {
				Map<SalesCopyStatus, Long> counts = service.counts();
				log.info("Head office sales push: {} sent, {} rejected, {} not built, {} unchanged; {} new, {} changed;"
						+ " now {} pending, {} sent, {} in error", cycle.getSent(), cycle.getRejected(),
						cycle.getNotBuilt(), cycle.getUnchanged(), cycle.getFound(), cycle.getChanged(),
						counts.get(SalesCopyStatus.PENDING), counts.get(SalesCopyStatus.SENT),
						counts.get(SalesCopyStatus.ERROR));
			} catch (RuntimeException e) {
				log.info("Head office sales push: {} sent, {} rejected, {} not built, {} unchanged; {} new, {} changed",
						cycle.getSent(), cycle.getRejected(), cycle.getNotBuilt(), cycle.getUnchanged(),
						cycle.getFound(), cycle.getChanged());
			}
		} else if (cycle.isIdle()) {
			log.debug("Head office sales push: nothing to send");
		}
	}
}
