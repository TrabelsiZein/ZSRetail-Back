package com.digithink.zsretail.headoffice.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.headoffice.model.HoDownChange;
import com.digithink.zsretail.headoffice.model.HoDownSequence;
import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.headoffice.repository.HoDownChangeRepository;
import com.digithink.zsretail.headoffice.repository.HoDownSequenceRepository;
import com.digithink.zsretail.holink.service.SalesCopyFinder;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.fasterxml.jackson.databind.JsonNode;

import lombok.extern.log4j.Log4j2;

/**
 * Head office side of the copies down mechanism (step 3, design 2.3): records the changes of the data the head office
 * owns and answers the pulls of the stores. Generic: one {@link DownDomainProvider} per domain.
 * <p>
 * <b>Cursor.</b> Each change takes the next number of its domain (ho_down_sequence), incremented inside the writer's
 * transaction: the row stays locked until that transaction commits, so the numbers of one domain are given in commit
 * order. A pull first reads the domain's number as committed (the horizon), then the changes up to it: every change
 * numbered up to the horizon is committed, and a change committed during the pull gets a higher number and comes with
 * a later pull. The cursor is that number, made from the head office data only; the store's clock is never used.
 * <p>
 * See docs/modules/head-office.md, "Copies down".
 */
@Service
@ConditionalOnHeadOffice
@Log4j2
public class CopiesDownFeed {

	static final int DEFAULT_LIMIT = 100;
	static final int MAX_LIMIT = 500;
	static final int TIMEOUT_SECONDS = 15;

	/** Records written per transaction by the startup backfill (step 6). */
	static final int BACKFILL_CHUNK = 500;

	private final HoDownChangeRepository changes;
	private final HoDownSequenceRepository sequences;
	private final Map<DataDomain, DownDomainProvider> providers = new EnumMap<>(DataDomain.class);
	private final TransactionOperations readTransactions;
	private final TransactionOperations writeTransactions;

	/** The providers are optional: a domain without one is answered 404. */
	@Autowired
	public CopiesDownFeed(HoDownChangeRepository changes, HoDownSequenceRepository sequences,
			ObjectProvider<DownDomainProvider> providers, PlatformTransactionManager transactionManager) {
		this(changes, sequences, providers.orderedStream().collect(Collectors.toList()), readOnly(transactionManager),
				write(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public CopiesDownFeed(HoDownChangeRepository changes, HoDownSequenceRepository sequences,
			List<DownDomainProvider> providers, TransactionOperations readTransactions,
			TransactionOperations writeTransactions) {
		this.changes = changes;
		this.sequences = sequences;
		for (DownDomainProvider provider : providers) {
			this.providers.put(provider.getDomain(), provider);
		}
		this.readTransactions = readTransactions;
		this.writeTransactions = writeTransactions;
	}

	private static TransactionTemplate readOnly(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setReadOnly(true);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	private static TransactionTemplate write(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	/**
	 * Records a change of one record for the stores concerned, inside the writer's transaction (created, edited,
	 * activated, deactivated, deleted, or targets changed). For a change of targets, pass the old and the new targets
	 * together ({@link StoreTargets#union}): a store taken off gets the code as removed. A blank code is ignored.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordChange(DataDomain domain, String code, StoreTargets stores) {
		if (code == null || code.trim().isEmpty()) {
			return;
		}
		long version = nextVersion(domain);
		if (stores.isAllStores()) {
			touch(domain, code, null, version);
		}
		for (Long storeId : stores.getStoreIds()) {
			touch(domain, code, storeId, version);
		}
	}

	/**
	 * Stock points, step 3a: the changes of many records for a list of stores, inside the writer's transaction, in bulk:
	 * one update of the sequence for all of them, each code still its own number (in the order given), the rows of
	 * each store read and written per chunk of {@value #BACKFILL_CHUNK} codes. Same rows as one {@link #recordChange} per
	 * code. Every-store targets fall back to {@link #recordChange}; no store: nothing recorded, the sequence untouched.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public void recordChanges(DataDomain domain, Collection<String> codes, StoreTargets stores) {
		List<String> list = codes.stream().filter(code -> code != null && !code.trim().isEmpty()).distinct()
				.collect(Collectors.toList());
		if (list.isEmpty()) {
			return;
		}
		if (stores.isAllStores()) {
			list.forEach(code -> recordChange(domain, code, stores));
			return;
		}
		if (stores.getStoreIds().isEmpty()) {
			return;
		}
		if (sequences.incrementBy(domain, list.size()) == 0) {
			HoDownSequence sequence = new HoDownSequence();
			sequence.setDomain(domain);
			sequence.setLastVersion(list.size());
			sequences.save(sequence);
		}
		long version = sequences.lastVersion(domain).get(0) - list.size();
		Map<String, Long> versions = new java.util.LinkedHashMap<>();
		for (String code : list) {
			versions.put(code, ++version);
		}
		for (Long storeId : stores.getStoreIds()) {
			for (int from = 0; from < list.size(); from += BACKFILL_CHUNK) {
				List<String> chunk = list.subList(from, Math.min(from + BACKFILL_CHUNK, list.size()));
				Map<String, HoDownChange> existing = new java.util.HashMap<>();
				for (HoDownChange change : changes.findByDomainAndStoreIdAndRecordCodeIn(domain, storeId, chunk)) {
					existing.put(change.getRecordCode(), change);
				}
				List<HoDownChange> rows = new ArrayList<>();
				for (String code : chunk) {
					HoDownChange change = existing.get(code);
					if (change == null) {
						rows.add(newChange(domain, code, storeId, versions.get(code)));
					} else {
						change.setChangeVersion(versions.get(code));
						rows.add(change);
					}
				}
				changes.saveAll(rows);
			}
		}
	}

	private long nextVersion(DataDomain domain) {
		if (sequences.increment(domain) == 0) {
			HoDownSequence sequence = new HoDownSequence();
			sequence.setDomain(domain);
			sequence.setLastVersion(1);
			sequences.save(sequence);
			return 1;
		}
		return sequences.lastVersion(domain).get(0);
	}

	private void touch(DataDomain domain, String code, Long storeId, long version) {
		HoDownChange change = (storeId == null ? changes.findByDomainAndRecordCodeAndStoreIdIsNull(domain, code)
				: changes.findByDomainAndRecordCodeAndStoreId(domain, code, storeId)).orElseGet(() -> {
					HoDownChange created = new HoDownChange();
					created.setDomain(domain);
					created.setRecordCode(code);
					created.setStoreId(storeId);
					return created;
				});
		change.setChangeVersion(version);
		changes.save(change);
	}

	/**
	 * One page of changes for the calling store: GET /ho/down/{domain}. cursor: blank for the first pull, then the cursor
	 * of the previous answer; a cursor after the domain's last change (head office database restored) starts again from
	 * the beginning. limit: codes per page, {@value #DEFAULT_LIMIT} by default, at most {@value #MAX_LIMIT}. Throws
	 * {@link NoSuchElementException} for a domain without copies down, IllegalArgumentException for a cursor that
	 * cannot be read.
	 */
	public CopiesDownAnswerDTO pull(Store store, String domainName, String cursor, Integer limit) {
		DownDomainProvider provider = providerOf(domainName);
		long after = parseCursor(cursor);
		int size = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
		return readTransactions.execute(status -> answer(provider, store, after, size));
	}

	private CopiesDownAnswerDTO answer(DownDomainProvider provider, Store store, long after, int size) {
		DataDomain domain = provider.getDomain();
		List<Long> last = sequences.lastVersion(domain);
		long horizon = last.isEmpty() ? 0 : last.get(0); // read first: every change up to it is committed
		long from = after > horizon ? 0 : after;
		List<Object[]> rows = changes.findChanged(domain, store.getId(), from, horizon, PageRequest.of(0, size + 1));
		boolean more = rows.size() > size;
		List<Object[]> page = more ? rows.subList(0, size) : rows;
		List<String> codes = new ArrayList<>();
		for (Object[] row : page) {
			codes.add((String) row[0]);
		}
		long next = more ? ((Number) page.get(page.size() - 1)[1]).longValue() : horizon;
		Map<String, JsonNode> copies = codes.isEmpty() ? Collections.emptyMap() : provider.load(store, codes);
		List<JsonNode> records = new ArrayList<>();
		List<String> removed = new ArrayList<>();
		for (String code : codes) {
			JsonNode copy = copies.get(code);
			if (copy != null) {
				records.add(copy);
			} else {
				removed.add(code);
			}
		}
		return new CopiesDownAnswerDTO(domain.name(), records, removed, String.valueOf(next), more);
	}

	private DownDomainProvider providerOf(String domainName) {
		String name = domainName == null ? "" : domainName.trim().toUpperCase(Locale.ROOT);
		for (DataDomain domain : DataDomain.values()) {
			if (domain.name().equals(name) && providers.containsKey(domain)) {
				return providers.get(domain);
			}
		}
		throw new NoSuchElementException("No copies down for domain '" + domainName + "'");
	}

	/** Blank: 0 (first pull). Otherwise a whole number, 0 or more. */
	static long parseCursor(String cursor) {
		if (cursor == null || cursor.trim().isEmpty()) {
			return 0;
		}
		try {
			long value = Long.parseLong(cursor.trim());
			if (value >= 0) {
				return value;
			}
		} catch (NumberFormatException e) {
			// refused below
		}
		throw new IllegalArgumentException("Invalid cursor '" + cursor + "': send back the cursor of the last answer");
	}

	/** At the start: each domain gets its sequence row, and every record without a change row gets one (backfill). */
	@EventListener(ApplicationReadyEvent.class)
	public void initialise() {
		for (DownDomainProvider provider : providers.values()) {
			try {
				int added = backfill(provider);
				if (added > 0) {
					log.info("Head office copies down: {} {} records made available to the stores", added,
							provider.getDomain());
				}
			} catch (RuntimeException e) {
				log.warn("Head office copies down: backfill of {} failed ({})", provider.getDomain(),
						SalesCopyFinder.cause(e));
			}
		}
	}

	/**
	 * Returns how many records got their first change row. Step 6 (a catalogue holds thousands of records): the records
	 * without a row are written in chunks of {@value #BACKFILL_CHUNK}, each in its own transaction with one update of the
	 * sequence. Each record still gets its own number, in the order of {@link DownDomainProvider#currentTargets} (a pull
	 * pages by number, so two codes never share one), and the same rows as one {@link #recordChange} per record. A start
	 * stopped in the middle goes on at the next start.
	 */
	int backfill(DownDomainProvider provider) {
		DataDomain domain = provider.getDomain();
		List<Map.Entry<String, StoreTargets>> missing = writeTransactions.execute(status -> {
			if (sequences.lastVersion(domain).isEmpty()) {
				HoDownSequence sequence = new HoDownSequence();
				sequence.setDomain(domain);
				sequence.setLastVersion(0);
				sequences.save(sequence);
			}
			Set<String> known = new HashSet<>(changes.findCodes(domain));
			List<Map.Entry<String, StoreTargets>> records = new ArrayList<>();
			for (Map.Entry<String, StoreTargets> record : provider.currentTargets().entrySet()) {
				if (record.getKey() != null && !record.getKey().trim().isEmpty() && !known.contains(record.getKey())) {
					records.add(record);
				}
			}
			return records;
		});
		int added = 0;
		for (int from = 0; from < missing.size(); from += BACKFILL_CHUNK) {
			List<Map.Entry<String, StoreTargets>> chunk = missing.subList(from,
					Math.min(from + BACKFILL_CHUNK, missing.size()));
			writeTransactions.executeWithoutResult(status -> addFirstChanges(domain, chunk));
			added += chunk.size();
		}
		return added;
	}

	/** One number per record, reserved with one update of the sequence; the rows of each record's targets. */
	private void addFirstChanges(DataDomain domain, List<Map.Entry<String, StoreTargets>> records) {
		sequences.incrementBy(domain, records.size()); // the row stays locked until the commit
		long version = sequences.lastVersion(domain).get(0) - records.size();
		List<HoDownChange> rows = new ArrayList<>();
		for (Map.Entry<String, StoreTargets> record : records) {
			version++;
			if (record.getValue().isAllStores()) {
				rows.add(newChange(domain, record.getKey(), null, version));
			}
			for (Long storeId : record.getValue().getStoreIds()) {
				rows.add(newChange(domain, record.getKey(), storeId, version));
			}
		}
		changes.saveAll(rows);
	}

	private static HoDownChange newChange(DataDomain domain, String code, Long storeId, long version) {
		HoDownChange change = new HoDownChange();
		change.setDomain(domain);
		change.setRecordCode(code);
		change.setStoreId(storeId);
		change.setChangeVersion(version);
		return change;
	}

	/** The domains served, in DataDomain order. */
	public Collection<DataDomain> domains() {
		return providers.keySet();
	}
}
