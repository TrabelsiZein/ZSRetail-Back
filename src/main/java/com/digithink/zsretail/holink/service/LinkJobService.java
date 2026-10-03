package com.digithink.zsretail.holink.service;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeLink;
import com.digithink.zsretail.holink.dto.LinkJobRun;
import com.digithink.zsretail.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.holink.model.LinkJobState;
import com.digithink.zsretail.holink.repository.LinkJobStateRepository;
import com.digithink.zsretail.holink.scheduler.LinkJob;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.log4j.Log4j2;

/**
 * The jobs' saved frequency and last run (task 2.6), in hol_job and in memory. The scheduler reads the frequency from
 * memory at each run, so a saved change applies without a restart and a database problem never stops a job: the
 * table is read once (retried until it works) and written at each run and change; a failed write is logged and the
 * memory copy keeps the value.
 */
@Component
@ConditionalOnHeadOfficeLink
@Log4j2
public class LinkJobService {

	/** Frequencies a user may save from the page, in seconds. */
	public static final long MIN_INTERVAL_SECONDS = 10;
	public static final long MAX_INTERVAL_SECONDS = 86_400;

	static final int TIMEOUT_SECONDS = 15;

	private final LinkJobStateRepository repository;
	private final TransactionOperations transactions;
	private final Map<String, Memory> memory = new ConcurrentHashMap<>();
	private volatile boolean loaded;

	@Autowired
	public LinkJobService(LinkJobStateRepository repository, PlatformTransactionManager transactionManager) {
		this(repository, ownTransaction(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public LinkJobService(LinkJobStateRepository repository, TransactionOperations transactions) {
		this.repository = repository;
		this.transactions = transactions;
	}

	private static TransactionTemplate ownTransaction(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	/** The frequency in force: the saved one, or the job's default. Never throws. */
	public long intervalSeconds(LinkJob job) {
		Long saved = state(job.getCode()).getIntervalSeconds();
		return saved != null ? saved : job.getDefaultIntervalSeconds();
	}

	/** What the store remembers of a job; never null (empty before its first run). */
	public Memory state(String code) {
		load();
		return memory.getOrDefault(code, Memory.EMPTY);
	}

	/**
	 * Saves a frequency from the page; null goes back to the default. Throws IllegalArgumentException outside
	 * {@value #MIN_INTERVAL_SECONDS} to {@value #MAX_INTERVAL_SECONDS} seconds, and the database error when it cannot
	 * be saved (nothing changes then).
	 */
	public void setInterval(LinkJob job, Long seconds) {
		if (seconds != null && (seconds < MIN_INTERVAL_SECONDS || seconds > MAX_INTERVAL_SECONDS)) {
			throw new IllegalArgumentException("intervalSeconds must be from " + MIN_INTERVAL_SECONDS + " to "
					+ MAX_INTERVAL_SECONDS + " seconds");
		}
		load();
		save(job.getCode(), row -> row.setIntervalSeconds(seconds));
		memory.compute(job.getCode(), (code, old) -> (old == null ? Memory.EMPTY : old).withInterval(seconds));
		log.info("Head office link: job {} frequency set to {}", job.getCode(),
				seconds == null ? "its default (" + job.getDefaultIntervalSeconds() + " s)" : seconds + " s");
	}

	/** Records a run. Never throws: the memory copy is updated first, a failed write is logged. */
	public void recordRun(String code, LinkJobRun run, LocalDateTime at, long durationMs) {
		load();
		String message = run.getMessage() == null || run.getMessage().length() <= LinkJobState.MESSAGE_LENGTH
				? run.getMessage() : run.getMessage().substring(0, LinkJobState.MESSAGE_LENGTH);
		memory.compute(code, (key, old) -> (old == null ? Memory.EMPTY : old).withRun(at, run.getResult(), message,
				durationMs));
		try {
			save(code, row -> {
				row.setLastRunAt(at);
				row.setLastResult(run.getResult());
				row.setLastMessage(message);
				row.setLastDurationMs(durationMs);
			});
		} catch (RuntimeException e) {
			log.warn("Head office link: last run of job {} not saved ({})", code, SalesCopyFinder.cause(e));
		}
	}

	private void save(String code, Consumer<LinkJobState> change) {
		transactions.executeWithoutResult(status -> {
			LinkJobState row = repository.findByCode(code).orElseGet(() -> {
				LinkJobState created = new LinkJobState();
				created.setCode(code);
				return created;
			});
			change.accept(row);
			repository.save(row);
		});
	}

	/** Reads hol_job once; retried at the next call while the database cannot be read. */
	private void load() {
		if (loaded) {
			return;
		}
		synchronized (this) {
			if (loaded) {
				return;
			}
			try {
				for (LinkJobState row : repository.findAll()) {
					memory.putIfAbsent(row.getCode(), new Memory(row.getIntervalSeconds(), row.getLastRunAt(),
							row.getLastResult(), row.getLastMessage(), row.getLastDurationMs()));
				}
				loaded = true;
			} catch (RuntimeException e) {
				log.warn("Head office link: jobs could not be read, defaults used for now ({})", SalesCopyFinder.cause(e));
			}
		}
	}

	/** Immutable view of a job's row. */
	@Getter
	@AllArgsConstructor
	public static final class Memory {

		static final Memory EMPTY = new Memory(null, null, null, null, null);

		/** Saved frequency; null: the default. */
		private final Long intervalSeconds;
		private final LocalDateTime lastRunAt;
		private final LinkJobResult lastResult;
		private final String lastMessage;
		private final Long lastDurationMs;

		Memory withInterval(Long seconds) {
			return new Memory(seconds, lastRunAt, lastResult, lastMessage, lastDurationMs);
		}

		Memory withRun(LocalDateTime at, LinkJobResult result, String message, long durationMs) {
			return new Memory(intervalSeconds, at, result, message, durationMs);
		}
	}
}
