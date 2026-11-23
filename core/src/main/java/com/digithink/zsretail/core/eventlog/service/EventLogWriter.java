package com.digithink.zsretail.core.eventlog.service;

import java.util.List;
import java.util.function.Consumer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.eventlog.model.EventLogEntry;
import com.digithink.zsretail.core.eventlog.model.EventLogSequence;
import com.digithink.zsretail.core.eventlog.repository.EventLogEntryRepository;
import com.digithink.zsretail.core.eventlog.repository.EventLogSequenceRepository;

import lombok.extern.log4j.Log4j2;

/**
 * 3.4.0, event log: writes one row, numbered within its importance, in its own short transaction, never the caller's:
 * a failure here cannot roll back a sale, and the number row (event_log_sequence) is locked only for this insert.
 * Throws on a failure; {@link EventLog} catches it.
 */
@Component
@Log4j2
public class EventLogWriter {

	/** A write never holds the caller longer than this (a locked number row, a slow database). */
	static final int TIMEOUT_SECONDS = 5;

	private final EventLogEntryRepository entries;
	private final EventLogSequenceRepository sequences;
	private final TransactionOperations transactions;

	@Autowired
	public EventLogWriter(EventLogEntryRepository entries, EventLogSequenceRepository sequences,
			PlatformTransactionManager transactionManager) {
		this(entries, sequences, ownTransaction(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public EventLogWriter(EventLogEntryRepository entries, EventLogSequenceRepository sequences,
			TransactionOperations transactions) {
		this.entries = entries;
		this.sequences = sequences;
		this.transactions = transactions;
	}

	private static TransactionTemplate ownTransaction(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	/**
	 * Numbers the event and inserts it. Tried a second time once, when the first try met a unique key: two first events
	 * of one importance on a database without its number row both created it; the second try finds the row.
	 */
	public EventLogEntry insert(EventLogEntry entry) {
		return insert(entry, completed -> {
		});
	}

	/**
	 * Step 2: {@code completion} first fills what the caller did not say (EventLogContext.complete), in the same own
	 * transaction; a completion that fails leaves those columns empty and the event is written all the same.
	 */
	public EventLogEntry insert(EventLogEntry entry, Consumer<EventLogEntry> completion) {
		try {
			return transactions.execute(status -> numberedAndSaved(entry, completion));
		} catch (DataIntegrityViolationException e) {
			entry.setSequenceNo(null);
			return transactions.execute(status -> numberedAndSaved(entry, completion));
		}
	}

	private EventLogEntry numberedAndSaved(EventLogEntry entry, Consumer<EventLogEntry> completion) {
		try {
			completion.accept(entry);
		} catch (RuntimeException e) {
			log.debug("Event log: {} written without its context ({})", entry.getCode(), e.toString());
		}
		entry.setSequenceNo(next(entry.getImportance()));
		return entries.save(entry);
	}

	/** The next number of an importance; its row is created at 1 when it has none (db/3.4.0/update.sql creates them). */
	private long next(int importance) {
		if (sequences.increment(importance) == 0) {
			sequences.saveAndFlush(new EventLogSequence(importance, 1));
			return 1;
		}
		List<Long> last = sequences.lastValue(importance);
		return last.get(0);
	}
}
