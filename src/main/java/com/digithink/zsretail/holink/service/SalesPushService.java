package com.digithink.zsretail.holink.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSalesPush;
import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.holink.client.HeadOfficeClient;
import com.digithink.zsretail.holink.dto.SalesPushAnswer;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.model.SalesCopy;
import com.digithink.zsretail.holink.repository.SalesCopyRepository;
import com.digithink.zsretail.holink.service.SalesCopyFinder.Discovery;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import lombok.Getter;
import lombok.ToString;
import lombok.extern.log4j.Log4j2;

/**
 * Sends the sales copies to the head office (task 2.4). One cycle: the search ({@link SalesCopyFinder}), then up to
 * {@value #BATCHES_PER_TYPE} batches of each type, round robin, oldest document first, never tried first. Rejected
 * documents are retried in the first round only, so a document is sent at most once per cycle.
 * <p>
 * Per batch: each copy is built in its own read-only transaction. A copy equal to the one the head office accepted
 * last is marked SENT without being sent. The others go in one request. Head office unreachable, key refused or
 * unexpected answer: no row changes (no attempt, no error) and the cycle stops. Delivered: each document follows its
 * own result (SENT, or ERROR with the reason; attempts + 1); a rejected document is retried at later cycles, after the
 * documents never tried. A document the store cannot build becomes ERROR too.
 * <p>
 * Runs only on the ho-link thread; nothing in the selling path calls it, and it never writes a selling table.
 */
@Component
@ConditionalOnHeadOfficeSalesPush
@Log4j2
public class SalesPushService {

	/** Batches per document type and per cycle. */
	static final int BATCHES_PER_TYPE = 2;

	/** No new batch is started once a cycle has run this long: the heartbeat, on the same thread, is not held back. */
	static final Duration CYCLE_BUDGET = Duration.ofSeconds(20);

	/** Longest database step (transaction timeout, applied to each statement). */
	static final int TIMEOUT_SECONDS = 15;

	static final String NOT_FOUND = "the document no longer exists in the store";
	static final String REJECTED = "rejected by the head office";
	static final String NO_RESULT = "no result from the head office for this document";

	/** First round of a cycle: pending, then rejected documents (retried). */
	private static final List<SalesCopyStatus> TO_SEND = Arrays.asList(SalesCopyStatus.PENDING, SalesCopyStatus.ERROR);

	/** Later rounds: pending only, so a document rejected in this cycle waits for the next one. */
	private static final List<SalesCopyStatus> PENDING_ONLY = Arrays.asList(SalesCopyStatus.PENDING);

	/** The copy as JSON with sorted properties: the same content always gives the same hash. */
	private static final ObjectMapper HASH_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.configure(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

	private final SalesCopyFinder finder;
	private final SalesDocumentSource source;
	private final SalesCopyRepository copies;
	private final HeadOfficeClient client;
	private final SalesPushSettings settings;
	private final TransactionOperations writes;
	private final TransactionOperations reads;
	private final Supplier<LocalDateTime> clock;

	@Autowired
	public SalesPushService(SalesCopyFinder finder, SalesDocumentSource source, SalesCopyRepository copies,
			HeadOfficeClient client, SalesPushSettings settings, PlatformTransactionManager transactionManager) {
		this(finder, source, copies, client, settings, template(transactionManager, false),
				template(transactionManager, true), LocalDateTime::now);
	}

	/** With given transactions and clock: used by the tests. */
	public SalesPushService(SalesCopyFinder finder, SalesDocumentSource source, SalesCopyRepository copies,
			HeadOfficeClient client, SalesPushSettings settings, TransactionOperations writes,
			TransactionOperations reads, Supplier<LocalDateTime> clock) {
		this.finder = finder;
		this.source = source;
		this.copies = copies;
		this.client = client;
		this.settings = settings;
		this.writes = writes;
		this.reads = reads;
		this.clock = clock;
	}

	private static TransactionTemplate template(PlatformTransactionManager transactionManager, boolean readOnly) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		template.setReadOnly(readOnly);
		return template;
	}

	/** One cycle. Throws only when the tracking table itself cannot be read or written. */
	public Cycle runCycle() {
		LocalDateTime start = clock.get();
		Cycle cycle = new Cycle(finder.discover(start));
		for (int round = 0; round < BATCHES_PER_TYPE; round++) {
			for (SalesCopyType type : SalesCopyType.values()) {
				if (cycle.stopped) {
					return cycle;
				}
				if (Duration.between(start, clock.get()).compareTo(CYCLE_BUDGET) >= 0) {
					cycle.budgetReached = true;
					return cycle;
				}
				pushBatch(type, round == 0 ? TO_SEND : PENDING_ONLY, cycle);
			}
		}
		return cycle;
	}

	private void pushBatch(SalesCopyType type, List<SalesCopyStatus> statuses, Cycle cycle) {
		int batchSize = settings.getBatchSize();
		List<SalesCopy> batch = writes.execute(status -> copies.findQueue(type, statuses, PageRequest.of(0, batchSize)));
		if (batch == null || batch.isEmpty()) {
			return;
		}
		if (batch.size() == batchSize && batch.stream().anyMatch(row -> row.getStatus() == SalesCopyStatus.PENDING)) {
			cycle.fullBatch = true;
		}

		LocalDateTime now = clock.get();
		List<SalesCopy> toSave = new ArrayList<>();
		List<SalesCopy> toSend = new ArrayList<>();
		List<Object> payloads = new ArrayList<>();
		List<String> hashes = new ArrayList<>();
		for (SalesCopy row : batch) {
			Object copy;
			String hash;
			try {
				copy = reads.execute(status -> source.loadCopy(type, row.getLocalId()));
				hash = copy == null ? null : hash(copy);
			} catch (RuntimeException e) {
				notBuilt(row, "the store could not build the copy (" + SalesCopyFinder.cause(e) + ")", now);
				toSave.add(row);
				cycle.notBuilt++;
				continue;
			}
			if (copy == null) {
				notBuilt(row, NOT_FOUND, now);
				toSave.add(row);
				cycle.notBuilt++;
			} else if (hash.equals(row.getContentHash())) {
				// The head office already has this content (e.g. only an ERP field of the document moved)
				row.setStatus(SalesCopyStatus.SENT);
				row.setLastError(null);
				toSave.add(row);
				cycle.unchanged++;
			} else {
				toSend.add(row);
				payloads.add(copy);
				hashes.add(hash);
			}
		}

		if (!toSend.isEmpty()) {
			SalesPushAnswer answer = client.push(type, payloads);
			cycle.state = answer.getState();
			cycle.message = answer.getMessage();
			if (!answer.isDelivered()) {
				cycle.stopped = true; // the rows to send stay exactly as they are
			} else {
				Map<String, SalesCopyResultDTO> results = new HashMap<>();
				answer.getResults().forEach(result -> results.put(result.getDocumentNumber(), result));
				for (int i = 0; i < toSend.size(); i++) {
					SalesCopy row = toSend.get(i);
					SalesCopyResultDTO result = results.get(row.getDocumentNumber());
					row.setAttempts(row.getAttempts() + 1);
					row.setLastPushDate(now);
					if (result != null && result.isAccepted()) {
						row.setStatus(SalesCopyStatus.SENT);
						row.setContentHash(hashes.get(i));
						row.setLastError(null);
						cycle.sent++;
					} else {
						// Rejected, or missing from the answer (an anomaly, made visible rather than resent at once)
						row.setStatus(SalesCopyStatus.ERROR);
						row.setLastError(cut(result == null ? NO_RESULT
								: result.getMessage() == null ? REJECTED : result.getMessage()));
						cycle.rejected++;
						logRejection(row);
					}
					toSave.add(row);
				}
			}
		}
		if (!toSave.isEmpty()) {
			writes.executeWithoutResult(status -> copies.saveAll(toSave));
		}
	}

	/** A document the store could not build: ERROR, counted as an attempt (no head office involved). */
	private static void notBuilt(SalesCopy row, String reason, LocalDateTime now) {
		row.setStatus(SalesCopyStatus.ERROR);
		row.setAttempts(row.getAttempts() + 1);
		row.setLastError(cut(reason));
		row.setLastPushDate(now);
		logRejection(row);
	}

	/** WARN the first time, DEBUG when the same document fails again. */
	private static void logRejection(SalesCopy row) {
		if (row.getAttempts() == 1) {
			log.warn("Head office sales push: {} {} not accepted, retried at later cycles ({})", row.getDocumentType(),
					row.getDocumentNumber(), row.getLastError());
		} else {
			log.debug("Head office sales push: {} {} not accepted again, attempt {} ({})", row.getDocumentType(),
					row.getDocumentNumber(), row.getAttempts(), row.getLastError());
		}
	}

	/** Rows per status, every status present (0 when none). */
	public Map<SalesCopyStatus, Long> counts() {
		Map<SalesCopyStatus, Long> counts = new EnumMap<>(SalesCopyStatus.class);
		for (SalesCopyStatus status : SalesCopyStatus.values()) {
			counts.put(status, 0L);
		}
		for (Object[] row : copies.countByStatus()) {
			counts.put((SalesCopyStatus) row[0], ((Number) row[1]).longValue());
		}
		return counts;
	}

	/** SHA-256 (hex) of the copy as JSON with sorted properties. */
	static String hash(Object copy) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(HASH_MAPPER.writeValueAsBytes(copy));
			StringBuilder hex = new StringBuilder(64);
			for (byte b : digest) {
				hex.append(String.format("%02x", b));
			}
			return hex.toString();
		} catch (JsonProcessingException | NoSuchAlgorithmException e) {
			throw new IllegalStateException("copy could not be hashed: " + e.getMessage(), e);
		}
	}

	private static String cut(String reason) {
		return reason.length() > SalesCopy.LAST_ERROR_LENGTH ? reason.substring(0, SalesCopy.LAST_ERROR_LENGTH) : reason;
	}

	/** What one cycle did. */
	@Getter
	@ToString
	public static final class Cycle {

		/** New finished documents found by the search. */
		private final int found;

		/** Tracked documents found changed by the search. */
		private final int changed;

		/** Why the search failed for a type; null when it did not. */
		private final String discoveryFailure;

		private int sent;
		private int rejected;

		/** Documents the store could not build (deleted, or failed to read). */
		private int notBuilt;

		/** Documents marked SENT again without being sent: the head office already has this content. */
		private int unchanged;

		/** State of the last request: ONLINE when delivered; null when nothing had to be sent. */
		private HeadOfficeLinkState state;

		/** Why the last request was not delivered; null otherwise. */
		private String message;

		private boolean stopped;
		private boolean budgetReached;
		private boolean fullBatch;
		private final boolean moreToSearch;

		Cycle(Discovery discovery) {
			this.found = discovery.getFound();
			this.changed = discovery.getChanged();
			this.discoveryFailure = discovery.getFailure();
			this.moreToSearch = discovery.isMore();
		}

		/** More work is probably waiting and the head office answers: the next cycle can come sooner. */
		public boolean isMore() {
			return !stopped && (moreToSearch || fullBatch || budgetReached);
		}

		/** Nothing found, nothing sent, no failure. */
		public boolean isIdle() {
			return found + changed + sent + rejected + notBuilt + unchanged == 0 && state == null
					&& discoveryFailure == null;
		}
	}
}
