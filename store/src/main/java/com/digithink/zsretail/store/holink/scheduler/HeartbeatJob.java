package com.digithink.zsretail.store.holink.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.store.holink.client.HeadOfficeClient;
import com.digithink.zsretail.store.holink.dto.HeadOfficeCallResult;
import com.digithink.zsretail.store.holink.dto.LinkJobRun;
import com.digithink.zsretail.store.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.store.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.store.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.store.holink.service.CatalogueRights;
import com.digithink.zsretail.store.holink.service.CopiesDownPuller;
import com.digithink.zsretail.store.holink.service.HeadOfficeLinkStatus;
import com.digithink.zsretail.store.holink.service.LinkExchangeLog;
import com.digithink.zsretail.store.holink.service.TicketTemplatesManaged;

import lombok.extern.log4j.Log4j2;

/**
 * The heartbeat to the head office (task 1.4), a job of the head office link since task 2.6: first run
 * {@link #FIRST_DELAY} after the start, then every headoffice.heartbeat-interval-seconds unless a frequency is saved
 * from the page. Each heartbeat updates the in-memory link status; one INFO line and one exchange log row when the
 * state changes, DEBUG and no row otherwise. 3.5.0, ticket templates step 5: an ONLINE answer while the head office
 * manages the ticket templates also pulls that domain (CopiesDownPuller#runDomain), so the switch turned on and every
 * save or reset at the head office reach the store within this rhythm, not the copies down's (30 min by default); a
 * pull with nothing new writes no exchange row. The other domains keep the copies down's rhythm.
 */
@Component
@ConditionalOnInstallation(Installation.LINKED_TO_HEAD_OFFICE)
@Order(1)
@Log4j2
public class HeartbeatJob implements LinkJob {

	/** 2.2.2: the default frequency (heartbeat every 3 min) when none is saved from the Jobs page; a property overrides it. */
	public static final long DEFAULT_INTERVAL_SECONDS = 180;

	public static final String CODE = "HEARTBEAT";

	static final Duration FIRST_DELAY = Duration.ofSeconds(15);

	private final HeadOfficeClient client;
	private final HeadOfficeLinkStatus status;
	private final LinkExchangeLog exchangeLog;
	private final long defaultIntervalSeconds;

	/** Step 6: the catalogue rights are saved when the catalogue is the head office's; no bean otherwise. */
	@Autowired(required = false)
	private ObjectProvider<CatalogueRights> catalogueRights;

	/** 3.5.0, ticket templates step 5: "the head office manages the ticket templates" saved, on every linked store. */
	@Autowired(required = false)
	private ObjectProvider<TicketTemplatesManaged> ticketTemplates;

	/** 3.5.0, ticket templates step 5: the copies down, for the ticket templates' pull at each heartbeat. */
	@Autowired(required = false)
	private ObjectProvider<CopiesDownPuller> copiesDown;

	public HeartbeatJob(HeadOfficeClient client, HeadOfficeLinkStatus status, LinkExchangeLog exchangeLog,
			@Value("${headoffice.heartbeat-interval-seconds:" + HeartbeatJob.DEFAULT_INTERVAL_SECONDS + "}") long intervalSeconds) {
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
		CatalogueRights rights = catalogueRights == null ? null : catalogueRights.getIfAvailable();
		if (online && rights != null) {
			try {
				rights.received(result.getMayChangePrices(), result.getCanPurchase(), at);
			} catch (RuntimeException e) {
				log.warn("Head office link: catalogue rights not saved ({})", e.getMessage());
			}
		}
		TicketTemplatesManaged templates = ticketTemplates == null ? null : ticketTemplates.getIfAvailable();
		if (online && templates != null) {
			try {
				templates.received(result.getTicketTemplatesManaged(), at);
			} catch (RuntimeException e) {
				log.warn("Head office link: the ticket templates switch not saved ({})", e.getMessage());
			}
			pullTicketTemplates(templates);
		}
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

	/** While managed: the ticket templates pulled now (a failure is the puller's exchange row; the heartbeat stays ONLINE). */
	private void pullTicketTemplates(TicketTemplatesManaged templates) {
		CopiesDownPuller puller = copiesDown == null ? null : copiesDown.getIfAvailable();
		if (puller == null || !templates.managedByHeadOffice()) {
			return;
		}
		try {
			puller.runDomain(DataDomain.TICKET_TEMPLATES);
		} catch (RuntimeException e) {
			log.warn("Head office link: the ticket templates not pulled ({})", e.getMessage());
		}
	}
}
