package com.digithink.zsretail.holink.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeSalesPush;
import com.digithink.zsretail.holink.dto.SalesDocumentRef;
import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.holink.enumeration.SalesCopyType;
import com.digithink.zsretail.holink.model.SalesCopy;
import com.digithink.zsretail.holink.model.SalesCopyCursor;
import com.digithink.zsretail.holink.repository.SalesCopyCursorRepository;
import com.digithink.zsretail.holink.repository.SalesCopyRepository;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * Finds the documents to copy to the head office (task 2.1), by a query: nothing is hooked into the selling services.
 * <p>
 * Per type, it reads the documents whose change time (updated_at) comes after its cursor, in (change time, id) order,
 * one page at a time, and moves the cursor to the last one read. A finished document not tracked yet gets a PENDING
 * row; a tracked document that is not PENDING becomes PENDING again (changed since its last push). An unfinished
 * document is skipped; when it is finished later its updated_at moves and it is read again. With nothing new the
 * query starts after the cursor and returns nothing: the history is not re-read.
 */
@Component
@ConditionalOnHeadOfficeSalesPush
public class SalesCopyFinder {

	/** Documents read per type and per search: a long history is read over several cycles. */
	static final int PAGE_SIZE = 500;

	/**
	 * A document changed less than this ago is read at a later cycle. updated_at is written by the application before
	 * its transaction commits: read too early, an uncommitted change could be passed by the cursor and never seen.
	 */
	static final Duration SETTLE_DELAY = Duration.ofSeconds(30);

	/** Start of a new cursor, and the from date when headoffice.sales-push.from-date is absent. */
	static final LocalDateTime BEGINNING = LocalDateTime.of(1900, 1, 1, 0, 0);

	/**
	 * Longest search of one type (transaction timeout, applied to each statement). The store database reads with
	 * locks: a row held by a long ERP export transaction makes the query wait, and this bounds the wait.
	 */
	static final int TIMEOUT_SECONDS = 15;

	private final SalesDocumentSource source;
	private final SalesCopyRepository copies;
	private final SalesCopyCursorRepository cursors;
	private final SalesPushSettings settings;
	private final TransactionOperations transactions;

	@Autowired
	public SalesCopyFinder(SalesDocumentSource source, SalesCopyRepository copies, SalesCopyCursorRepository cursors,
			SalesPushSettings settings, PlatformTransactionManager transactionManager) {
		this(source, copies, cursors, settings, withTimeout(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public SalesCopyFinder(SalesDocumentSource source, SalesCopyRepository copies, SalesCopyCursorRepository cursors,
			SalesPushSettings settings, TransactionOperations transactions) {
		this.source = source;
		this.copies = copies;
		this.cursors = cursors;
		this.settings = settings;
		this.transactions = transactions;
	}

	private static TransactionTemplate withTimeout(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	/**
	 * One search per type, each in its own transaction. Never throws: a type that fails is reported in the result and
	 * searched again at the next cycle from the same cursor.
	 */
	public Discovery discover(LocalDateTime now) {
		Discovery total = Discovery.NONE;
		for (SalesCopyType type : SalesCopyType.values()) {
			try {
				total = total.plus(transactions.execute(status -> discover(type, now)));
			} catch (RuntimeException e) {
				total = total.plus(Discovery.failed(type + ": " + cause(e)));
			}
		}
		return total;
	}

	private Discovery discover(SalesCopyType type, LocalDateTime now) {
		SalesCopyCursor cursor = cursors.findByDocumentType(type).orElseGet(() -> newCursor(type));
		LocalDateTime from = settings.getFromDate() == null ? BEGINNING : settings.getFromDate();
		List<SalesDocumentRef> page = source.findChanged(type, from, cursor.getLastChangedAt(), cursor.getLastLocalId(),
				now.minus(SETTLE_DELAY), PAGE_SIZE);
		if (page.isEmpty()) {
			return Discovery.NONE;
		}

		List<Long> ids = new ArrayList<>(page.size());
		page.forEach(ref -> ids.add(ref.getLocalId()));
		Map<Long, SalesCopy> tracked = new HashMap<>();
		copies.findByDocumentTypeAndLocalIdIn(type, ids).forEach(copy -> tracked.put(copy.getLocalId(), copy));

		List<SalesCopy> toSave = new ArrayList<>();
		int found = 0;
		int changed = 0;
		for (SalesDocumentRef ref : page) {
			SalesCopy copy = tracked.get(ref.getLocalId());
			if (copy == null) {
				if (type.isFinished(ref.getStatus())) {
					toSave.add(newCopy(ref));
					found++;
				}
			} else if (copy.getStatus() != SalesCopyStatus.PENDING) {
				// Changed since its last push. The push compares the content first, so a change that does not show in
				// the copy (e.g. the ERP export writing its own fields) is marked SENT again without being sent.
				copy.setStatus(SalesCopyStatus.PENDING);
				copy.setAttempts(0);
				copy.setLastError(null);
				toSave.add(copy);
				changed++;
			}
		}
		if (!toSave.isEmpty()) {
			copies.saveAll(toSave);
		}

		SalesDocumentRef last = page.get(page.size() - 1);
		cursor.setLastChangedAt(last.getChangedAt());
		cursor.setLastLocalId(last.getLocalId());
		cursors.save(cursor);
		return new Discovery(found, changed, page.size() == PAGE_SIZE, null);
	}

	private static SalesCopyCursor newCursor(SalesCopyType type) {
		SalesCopyCursor cursor = new SalesCopyCursor();
		cursor.setDocumentType(type);
		cursor.setLastChangedAt(BEGINNING);
		cursor.setLastLocalId(0L);
		return cursor;
	}

	private static SalesCopy newCopy(SalesDocumentRef ref) {
		SalesCopy copy = new SalesCopy();
		copy.setDocumentType(ref.getType());
		copy.setLocalId(ref.getLocalId());
		copy.setDocumentNumber(ref.getDocumentNumber());
		copy.setDocumentDate(ref.getDocumentDate());
		copy.setStatus(SalesCopyStatus.PENDING);
		return copy;
	}

	/** The most specific cause, e.g. "SQLServerException: The query has timed out." */
	public static String cause(Exception e) {
		Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
		return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
	}

	/** Outcome of one search over the three types. */
	@Getter
	@ToString
	@AllArgsConstructor(access = AccessLevel.PRIVATE)
	public static final class Discovery {

		static final Discovery NONE = new Discovery(0, 0, false, null);

		/** Finished documents seen for the first time, now PENDING. */
		private final int found;

		/** Tracked documents changed since their last push, PENDING again. */
		private final int changed;

		/** A page was full: there is more to read at the next search. */
		private final boolean more;

		/** Why a type could not be searched ("TICKET: ..."); null when every type was searched. */
		private final String failure;

		static Discovery failed(String failure) {
			return new Discovery(0, 0, false, failure);
		}

		Discovery plus(Discovery other) {
			String failures = failure == null ? other.failure
					: other.failure == null ? failure : failure + "; " + other.failure;
			return new Discovery(found + other.found, changed + other.changed, more || other.more, failures);
		}
	}
}
