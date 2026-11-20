package com.digithink.zsretail.store.holink.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.communication.enumeration.CommunicationEventStatus;
import com.digithink.zsretail.core.communication.model.CommunicationEvent;
import com.digithink.zsretail.core.communication.repository.CommunicationEventRepository;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.CommunicationEventAnswerDTO;
import com.digithink.zsretail.core.link.dto.CommunicationEventCopyDTO;
import com.digithink.zsretail.store.holink.client.HeadOfficeClient;
import com.digithink.zsretail.store.holink.dto.CommunicationPushAnswer;
import com.digithink.zsretail.store.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.store.holink.enumeration.LinkJobResult;

import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication step 6: on a store whose loyalty is the head office's (Installation.COMMUNICATION_PUSHED_UP),
 * sends its comm_event rows up (POST /ho/communication/events), run by the link job COMMUNICATION_PUSH. The store makes
 * no message: its event rows are its queue, marked by their own status (no new table: the status never travels):
 * <ul>
 * <li>PENDING: to send; PROCESSED: taken by the head office (stored there, or already there: sending twice is
 * harmless, its dedup key keeps it once); ERROR: refused by the head office (attempts, last_error), sent again at most
 * {@link #MAX_ATTEMPTS} times; SKIPPED: ended without going up.</li>
 * <li>The head office takes no events (its module is off, or an older version without the endpoint): the push pauses
 * {@link #PAUSE} (one INFO line, one note in the exchange log), nothing counted, the events untouched.</li>
 * <li>An event never taken ends quietly {@link #GIVE_UP_HOURS} hours after it happened (SKIPPED): the longest lifetime a
 * rule may have; the store does not know the head office's rules, and the head office skips what is too old for them.</li>
 * <li>An unreachable head office: as the other link jobs (the exchange log groups the failures).</li>
 * </ul>
 */
@Service
@ConditionalOnInstallation(Installation.COMMUNICATION_PUSHED_UP)
@Log4j2
public class CommunicationPushService {

	public static final String JOB_CODE = "COMMUNICATION_PUSH";
	static final int BATCH = 100;
	static final int MAX_ATTEMPTS = 5;
	static final Duration PAUSE = Duration.ofHours(1);
	static final int GIVE_UP_HOURS = 720;
	static final String GAVE_UP = "never taken by the head office within " + GIVE_UP_HOURS + " h";

	private final HeadOfficeClient client;
	private final CommunicationEventRepository events;
	private final LinkExchangeLog exchangeLog;
	private final TransactionOperations transactions;
	private final Supplier<LocalDateTime> clock;

	/** Until when the head office is not asked again (it took no events); null: not paused. ho-link thread only. */
	private LocalDateTime pausedUntil;

	@Autowired
	public CommunicationPushService(HeadOfficeClient client, CommunicationEventRepository events,
			LinkExchangeLog exchangeLog, PlatformTransactionManager transactionManager) {
		this(client, events, exchangeLog, new TransactionTemplate(transactionManager), LocalDateTime::now);
	}

	/** With given transactions and clock: used by the tests. */
	public CommunicationPushService(HeadOfficeClient client, CommunicationEventRepository events,
			LinkExchangeLog exchangeLog, TransactionOperations transactions, Supplier<LocalDateTime> clock) {
		this.client = client;
		this.events = events;
		this.exchangeLog = exchangeLog;
		this.transactions = transactions;
		this.clock = clock;
	}

	/** What one cycle did. */
	@Getter
	public static final class Cycle {
		private int ended;
		private int taken;
		private int refused;
		private boolean paused;
		private boolean notTaken;
		private String failure;
		private boolean more;
	}

	/** "Run now" from the Jobs page (ho-link thread): the pause, if any, lifted; the next cycle asks the head office. */
	public void resume() {
		if (pausedUntil != null) {
			log.info("Head office communication push: pause lifted by a run asked from the Jobs page");
			pausedUntil = null;
		}
	}

	public Cycle runCycle() {
		LocalDateTime now = clock.get();
		Cycle cycle = new Cycle();
		cycle.ended = endOld(now);
		if (pausedUntil != null && now.isBefore(pausedUntil)) {
			cycle.paused = true;
			return cycle;
		}
		List<Long> ids = transactions.execute(status -> events.findToProcess(CommunicationEventStatus.PENDING,
				CommunicationEventStatus.ERROR, MAX_ATTEMPTS, 0L, PageRequest.of(0, BATCH)));
		if (ids == null || ids.isEmpty()) {
			return cycle;
		}
		List<CommunicationEvent> batch = events.findAllById(ids).stream()
				.sorted(Comparator.comparing(CommunicationEvent::getId)).collect(Collectors.toList());
		long started = System.currentTimeMillis();
		CommunicationPushAnswer answer = client.pushCommunicationEvents(batch.stream().map(CommunicationEventCopyDTO::of)
				.collect(Collectors.toList()));
		if (!answer.isTaken()) {
			pausedUntil = now.plus(PAUSE);
			cycle.notTaken = true;
			log.info("Head office communication push: {}; {} event(s) kept, tried again at {}", answer.getMessage(),
					batch.size(), pausedUntil.toLocalTime().withNano(0));
			exchangeLog.recordNote(JOB_CODE, ExchangeDirection.UP, batch.size(), LinkJobResult.WARNING,
					answer.getMessage() + ": tried again in " + PAUSE.toMinutes() + " min", now);
			return cycle;
		}
		if (!answer.isDelivered()) {
			cycle.failure = answer.getState() + ": " + answer.getMessage();
			exchangeLog.recordFailure(JOB_CODE, ExchangeDirection.UP, batch.size(), cycle.failure, now,
					System.currentTimeMillis() - started);
			return cycle;
		}
		if (pausedUntil != null) {
			log.info("Head office communication push: the head office takes events again");
			pausedUntil = null;
		}
		Map<String, CommunicationEventAnswerDTO.Result> results = new LinkedHashMap<>();
		answer.getResults().stream().filter(r -> r != null && r.getDedupKey() != null)
				.forEach(r -> results.put(r.getDedupKey(), r));
		List<String> refusals = new ArrayList<>();
		for (CommunicationEvent event : batch) {
			CommunicationEventAnswerDTO.Result result = results.get(event.getDedupKey());
			transactions.executeWithoutResult(status -> apply(event.getId(), result, now));
			if (result != null && result.isAccepted()) {
				cycle.taken++;
			} else {
				cycle.refused++;
				refusals.add(event.getDedupKey() + ": " + (result == null ? "no answer for it" : result.getMessage()));
			}
		}
		cycle.more = ids.size() == BATCH;
		exchangeLog.record(JOB_CODE, ExchangeDirection.UP, batch.size(),
				cycle.refused > 0 ? LinkJobResult.WARNING : LinkJobResult.SUCCESS,
				refusals.isEmpty() ? null : String.join("; ", refusals), now, System.currentTimeMillis() - started);
		return cycle;
	}

	/** One event's answer: PROCESSED (taken), else ERROR with one more attempt and the reason. */
	private void apply(Long id, CommunicationEventAnswerDTO.Result result, LocalDateTime now) {
		events.findById(id).ifPresent(event -> {
			if (result != null && result.isAccepted()) {
				event.setStatus(CommunicationEventStatus.PROCESSED);
				event.setLastError(result.getMessage()); // e.g. "already at the head office"
				event.setProcessedAt(now);
			} else {
				event.setStatus(CommunicationEventStatus.ERROR);
				event.setAttempts(event.getAttempts() + 1);
				String why = result == null ? "no answer for it" : result.getMessage();
				event.setLastError(why == null || why.length() <= CommunicationEvent.LAST_ERROR_LENGTH ? why
						: why.substring(0, CommunicationEvent.LAST_ERROR_LENGTH));
			}
			events.save(event);
		});
	}

	/** The events still to send (PENDING or ERROR) that happened more than GIVE_UP_HOURS ago: SKIPPED, quietly. */
	int endOld(LocalDateTime now) {
		LocalDateTime limit = now.minusHours(GIVE_UP_HOURS);
		Integer ended = transactions.execute(status -> {
			List<CommunicationEvent> old = events.findOlderThan(java.util.Arrays.asList(CommunicationEventStatus.PENDING,
					CommunicationEventStatus.ERROR), limit, PageRequest.of(0, 500));
			for (CommunicationEvent event : old) {
				event.setStatus(CommunicationEventStatus.SKIPPED);
				event.setLastError(GAVE_UP);
				event.setProcessedAt(now);
				events.save(event);
			}
			return old.size();
		});
		return ended == null ? 0 : ended;
	}
}
