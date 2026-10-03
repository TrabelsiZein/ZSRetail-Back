package com.digithink.zsretail.holink.scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOfficePull;
import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.service.CopiesDownPuller;
import com.digithink.zsretail.holink.service.SalesCopyFinder;
import com.digithink.zsretail.model.enumeration.DataDomain;

import lombok.extern.log4j.Log4j2;

/**
 * The pull of copies down (step 3), a job of the head office link after the sales push. Exists only when the store
 * pulls something from its head office (headoffice.url set and at least one domain owned by the head office). First
 * run {@link #FIRST_DELAY} after the start, then headoffice.pull.interval-seconds (default 60) unless a frequency is
 * saved from the page; while a domain has more pages waiting, the next run comes after the catch-up delay.
 */
@Component
@ConditionalOnHeadOfficePull
@Order(3)
@Log4j2
public class CopiesDownJob implements LinkJob {

	public static final String CODE = "COPIES_DOWN";

	static final Duration FIRST_DELAY = Duration.ofSeconds(25);

	private final CopiesDownPuller puller;
	private final long defaultIntervalSeconds;

	/** Failure of the last run per domain, null when it was delivered; read and written by the ho-link thread only. */
	private final Map<DataDomain, String> lastFailure = new EnumMap<>(DataDomain.class);

	public CopiesDownJob(CopiesDownPuller puller, @Value("${headoffice.pull.interval-seconds:60}") long intervalSeconds) {
		this.puller = puller;
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

	/** One cycle over every domain, its log lines and run result. Never throws. */
	@Override
	public LinkJobRun run() {
		CopiesDownPuller.Cycle cycle;
		try {
			cycle = puller.runCycle();
		} catch (RuntimeException e) {
			String cause = SalesCopyFinder.cause(e);
			log.warn("Head office copies down: cycle failed, retried at the next cycle ({})", cause);
			return LinkJobRun.error("cycle failed (" + cause + ")");
		}
		if (cycle.getRuns().isEmpty()) {
			return LinkJobRun.success("no domain to pull");
		}
		LinkJobResult result = LinkJobResult.SUCCESS;
		List<String> messages = new ArrayList<>();
		for (CopiesDownPuller.DomainRun run : cycle.getRuns()) {
			report(run);
			messages.add(run.getDomain() + ": " + message(run));
			if (run.getFailure() != null) {
				result = LinkJobResult.ERROR;
			} else if (result == LinkJobResult.SUCCESS
					&& run.getPulled().getProblems() + run.getRetried().getProblems() > 0) {
				result = LinkJobResult.WARNING;
			}
		}
		return LinkJobRun.of(result, String.join("; ", messages), cycle.isMore());
	}

	private static String message(CopiesDownPuller.DomainRun run) {
		String local = run.getLocalDeactivated() > 0 ? run.getLocalDeactivated() + " local set inactive; " : "";
		if (run.getFailure() != null) {
			return local + run.getFailure();
		}
		DownApplyResult total = total(run);
		if (run.getRecords() + run.getRemovedCodes() == 0 && total.isEmpty()) {
			return local + "nothing new";
		}
		return local + counts(total);
	}

	private static DownApplyResult total(CopiesDownPuller.DomainRun run) {
		DownApplyResult total = DownApplyResult.none();
		total.add(run.getPulled());
		total.add(run.getRetried());
		return total;
	}

	private static String counts(DownApplyResult result) {
		return result.getApplied() + " applied, " + result.getUnchanged() + " unchanged, " + result.getRemoved()
				+ " removed, " + result.getWaiting() + " waiting, " + result.getErrors() + " in error";
	}

	/**
	 * INFO when a pull brought something, when a retry applied or removed something, and when delivery fails or comes
	 * back; DEBUG otherwise (a record still waiting is not repeated at INFO every cycle).
	 */
	private void report(CopiesDownPuller.DomainRun run) {
		DataDomain domain = run.getDomain();
		String previous = lastFailure.get(domain);
		if (run.getFailure() != null) {
			if (!run.getFailure().equals(previous)) {
				log.info("Head office copies down: {} {}", domain, run.getFailure());
			} else {
				log.debug("Head office copies down: {} still {}", domain, run.getFailure());
			}
		} else if (previous != null) {
			log.info("Head office copies down: {} delivered again", domain);
		}
		lastFailure.put(domain, run.getFailure());
		if (run.getLocalDeactivated() > 0) {
			log.info("Head office copies down: {} {} local records set inactive (owned by the head office)", domain,
					run.getLocalDeactivated());
		}
		DownApplyResult retried = run.getRetried();
		if (run.getRecords() + run.getRemovedCodes() > 0 || retried.getApplied() + retried.getRemoved() > 0) {
			log.info("Head office copies down: {} {} records and {} removed codes received; {}", domain,
					run.getRecords(), run.getRemovedCodes(), counts(total(run)));
		} else {
			log.debug("Head office copies down: {} nothing new", domain);
		}
	}
}
