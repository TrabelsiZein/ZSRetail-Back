package com.digithink.zsretail.holink.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficePull;
import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.holink.dto.PullAnswer;
import com.digithink.zsretail.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.DownCursor;
import com.digithink.zsretail.holink.repository.DownCursorRepository;
import com.digithink.zsretail.holink.scheduler.CopiesDownJob;
import com.digithink.zsretail.model.enumeration.DataDomain;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Store side of the copies down mechanism (step 3, design 2.3): for each domain owned by the head office (one
 * {@link DownHandler} each, in DataDomain order), pulls the changes since the saved cursor, gives each page to the
 * handler, then saves the page's cursor, exactly as received. Then the handler retries what it could not apply yet.
 * Before the pull, the handler sets inactive the local records of the domain (one exchange log row with how many).
 * <p>
 * Nothing changes when the head office is unreachable, refuses the key, or answers something unreadable: no record, no
 * cursor; the store keeps its last copy. The cursor is saved only after its page is applied, so a page is never
 * skipped; a page applied twice changes nothing.
 * <p>
 * Exchange log: direction DOWN, one row per pull that brought something or failed, none for a pull with nothing new.
 * Run by {@link CopiesDownJob} on the ho-link thread. See docs/modules/head-office.md, "Copies down".
 */
@Component
@ConditionalOnHeadOfficePull
public class CopiesDownPuller {

	/** Codes per page asked from the head office. */
	static final int PAGE_SIZE = 100;

	/** Pages per domain in one cycle; more waits for the next cycle, 5 s later. */
	static final int MAX_PAGES = 10;

	/** No new page is pulled once the cycle has run this long. */
	static final Duration CYCLE_BOUND = Duration.ofSeconds(20);

	static final int TIMEOUT_SECONDS = 15;

	private final HeadOfficeClient client;
	private final List<DownHandler> handlers;
	private final DownCursorRepository cursors;
	private final LinkExchangeLog exchangeLog;
	private final TransactionOperations transactions;

	@Autowired
	public CopiesDownPuller(HeadOfficeClient client, ObjectProvider<DownHandler> handlers, DownCursorRepository cursors,
			LinkExchangeLog exchangeLog, PlatformTransactionManager transactionManager) {
		this(client, handlers.orderedStream().collect(Collectors.toList()), cursors, exchangeLog,
				timed(transactionManager));
	}

	/** With given handlers and transactions: used by the tests. */
	public CopiesDownPuller(HeadOfficeClient client, List<DownHandler> handlers, DownCursorRepository cursors,
			LinkExchangeLog exchangeLog, TransactionOperations transactions) {
		this.client = client;
		List<DownHandler> sorted = new ArrayList<>(handlers);
		sorted.sort(Comparator.comparing(DownHandler::getDomain));
		this.handlers = Collections.unmodifiableList(sorted);
		this.cursors = cursors;
		this.exchangeLog = exchangeLog;
		this.transactions = transactions;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	/** The domains pulled, in order. */
	public List<DataDomain> getDomains() {
		return handlers.stream().map(DownHandler::getDomain).collect(Collectors.toList());
	}

	/** One cycle over every domain. Never throws for one domain: its failure is in its run. */
	public Cycle runCycle() {
		Cycle cycle = new Cycle();
		long started = System.nanoTime();
		for (DownHandler handler : handlers) {
			cycle.runs.add(runDomain(handler, started));
		}
		return cycle;
	}

	private DomainRun runDomain(DownHandler handler, long cycleStarted) {
		DataDomain domain = handler.getDomain();
		DomainRun run = new DomainRun(domain);
		LocalDateTime localAt = LocalDateTime.now();
		long localStarted = System.nanoTime();
		try {
			run.localDeactivated = handler.deactivateLocal();
			if (run.localDeactivated > 0) {
				log(domain, run.localDeactivated, LinkJobResult.WARNING, run.localDeactivated
						+ " local records set inactive: the domain is owned by the head office", localAt, localStarted);
			}
		} catch (RuntimeException e) {
			log(domain, 0, LinkJobResult.ERROR, "local records not set inactive (" + SalesCopyFinder.cause(e) + ")",
					localAt, localStarted);
		}
		String cursor;
		try {
			cursor = transactions.execute(status -> cursors.findByDomain(domain).map(DownCursor::getCursorValue)
					.orElse(""));
		} catch (RuntimeException e) {
			run.failure = "cursor could not be read (" + SalesCopyFinder.cause(e) + ")";
			cursor = null;
		}
		while (cursor != null && run.pages < MAX_PAGES) {
			if (run.pages > 0 && System.nanoTime() - cycleStarted > CYCLE_BOUND.toNanos()) {
				break; // more stays true: the next cycle comes soon
			}
			cursor = pullPage(handler, run, cursor);
			if (!run.more) {
				break;
			}
		}
		try {
			run.retried = handler.retry();
		} catch (RuntimeException e) {
			if (run.failure == null) {
				run.failure = "retry failed (" + SalesCopyFinder.cause(e) + ")";
			}
		}
		return run;
	}

	/** One page: pulled, applied, then its cursor saved. Returns the cursor for the next page, null to stop. */
	private String pullPage(DownHandler handler, DomainRun run, String cursor) {
		DataDomain domain = handler.getDomain();
		LocalDateTime at = LocalDateTime.now();
		long started = System.nanoTime();
		run.pages++;
		run.more = false;
		PullAnswer answer = client.pull(domain, cursor, PAGE_SIZE);
		run.state = answer.getState();
		if (!answer.isDelivered()) {
			run.failure = "not delivered, the store keeps its last copy (" + answer.getState() + ": "
					+ answer.getMessage() + ")";
			log(domain, 0, LinkJobResult.ERROR, answer.getState() + ": " + answer.getMessage(), at, started);
			return null;
		}
		CopiesDownAnswerDTO page = answer.getPage();
		int count = page.getRecords().size() + page.getRemoved().size();
		DownApplyResult result;
		try {
			result = handler.apply(page.getRecords(), page.getRemoved());
		} catch (RuntimeException e) {
			run.failure = "page not applied, pulled again at the next cycle (" + SalesCopyFinder.cause(e) + ")";
			log(domain, count, LinkJobResult.ERROR, run.failure, at, started);
			return null;
		}
		run.records += page.getRecords().size();
		run.removedCodes += page.getRemoved().size();
		run.pulled.add(result);
		String next = page.getCursor();
		try {
			transactions.executeWithoutResult(status -> {
				DownCursor saved = cursors.findByDomain(domain).orElseGet(() -> {
					DownCursor created = new DownCursor();
					created.setDomain(domain);
					return created;
				});
				saved.setCursorValue(next);
				cursors.save(saved);
			});
		} catch (RuntimeException e) {
			run.failure = "cursor not saved, the page is pulled again (" + SalesCopyFinder.cause(e) + ")";
			log(domain, count, LinkJobResult.ERROR, run.failure, at, started);
			return null;
		}
		if (count > 0) {
			log(domain, count, result.getProblems() > 0 ? LinkJobResult.WARNING : LinkJobResult.SUCCESS,
					result.getFirstProblem(), at, started);
		}
		run.more = page.isMore();
		return next;
	}

	private void log(DataDomain domain, int records, LinkJobResult result, String error, LocalDateTime at,
			long started) {
		String text = error == null ? null : domain + ": " + error;
		long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
		if (result == LinkJobResult.ERROR) { // step 5: a failure writes one row when it starts, not one per cycle
			exchangeLog.recordFailure(CopiesDownJob.CODE, ExchangeDirection.DOWN, records, text, at, durationMs);
		} else {
			exchangeLog.record(CopiesDownJob.CODE, ExchangeDirection.DOWN, records, result, text, at, durationMs);
		}
	}

	/** Outcome of one cycle: one run per domain, in order. */
	@ToString
	@NoArgsConstructor(access = AccessLevel.PRIVATE)
	public static final class Cycle {

		private final List<DomainRun> runs = new ArrayList<>();

		public List<DomainRun> getRuns() {
			return runs;
		}

		/** A domain has more pages waiting: the next cycle comes soon. */
		public boolean isMore() {
			return runs.stream().anyMatch(DomainRun::isMore);
		}
	}

	/** Outcome of one domain in a cycle. */
	@Getter
	@ToString
	public static final class DomainRun {

		private final DataDomain domain;
		private int pages;
		private int records;
		private int removedCodes;

		/** Active local records set inactive before the pull (the domain is owned by the head office). */
		private int localDeactivated;

		private final DownApplyResult pulled = DownApplyResult.none();
		private DownApplyResult retried = DownApplyResult.none();

		/** State of the last pull; null when none was made. */
		private HeadOfficeLinkState state;

		/** Why the domain stopped early (not delivered, page not applied, cursor not saved); null otherwise. */
		private String failure;

		private boolean more;

		DomainRun(DataDomain domain) {
			this.domain = domain;
		}
	}
}
