package com.digithink.zsretail.store.holink.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;
import com.digithink.zsretail.core.link.dto.CatalogueBarcodeCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueCategoryCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueFamilyCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueItemCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueSectionCopyDTO;
import com.digithink.zsretail.core.link.dto.CatalogueSubFamilyCopyDTO;
import com.digithink.zsretail.core.model.enumeration.CatalogueKind;
import com.digithink.zsretail.core.model.enumeration.DataDomain;
import com.digithink.zsretail.store.holink.dto.DownApplyResult;
import com.digithink.zsretail.store.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.store.holink.enumeration.ExchangeDirection;
import com.digithink.zsretail.store.holink.enumeration.LinkJobResult;
import com.digithink.zsretail.store.holink.model.DownRecord;
import com.digithink.zsretail.store.holink.scheduler.CopiesDownJob;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 6.2: the store side of the CATALOGUE domain on the copies down mechanism, on a store whose
 * catalogue is the head office's. Records CATEGORY: (item variants, ZEN, step 7), FAMILY:, SUBFAMILY:, SECTION:, ITEM:
 * and BARCODE: are applied in that order (the order of CatalogueKind) within a page by {@link CatalogueCopyWriter}, each in its own transaction and tracked in hol_down_record (APPLIED, WAITING for
 * a family, sub-family, item or component missing here, ERROR), retried at every cycle. Removed codes become inactive.
 * The store's other local records are not switched off. Before each pull ({@link #prepare}), when the right "may change
 * its selling prices" is off, the own prices give way to the head office price.
 */
@Component
@ConditionalOnInstallation(Installation.CATALOGUE_FROM_HEAD_OFFICE)
@Log4j2
public class CatalogueDownHandler implements DownHandler {

	static final int TIMEOUT_SECONDS = 15;
	static final String NO_CODE = "?";

	static final ObjectMapper COPY_MAPPER = new ObjectMapper()
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	private final CatalogueCopyWriter writer;
	private final DownRecordLog records;
	private final CatalogueRights rights;
	private final LinkExchangeLog exchangeLog;
	private final TransactionOperations transactions;

	/** Codes applied by the pulls of the current cycle: the retry that follows skips them. ho-link thread only. */
	private final Set<String> appliedThisCycle = new HashSet<>();

	@Autowired
	public CatalogueDownHandler(CatalogueCopyWriter writer, DownRecordLog records, CatalogueRights rights,
			LinkExchangeLog exchangeLog, PlatformTransactionManager transactionManager) {
		this(writer, records, rights, exchangeLog, timed(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public CatalogueDownHandler(CatalogueCopyWriter writer, DownRecordLog records, CatalogueRights rights,
			LinkExchangeLog exchangeLog, TransactionOperations transactions) {
		this.writer = writer;
		this.records = records;
		this.rights = rights;
		this.exchangeLog = exchangeLog;
		this.transactions = transactions;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	@Override
	public DataDomain getDomain() {
		return DataDomain.CATALOGUE;
	}

	/** Right off (or never received): every own price gives way to the head office price, one exchange log row. */
	@Override
	public void prepare() {
		if (rights.mayChangePrices()) {
			return;
		}
		List<String> changed = transactions.execute(status -> writer.giveBackOwnPrices());
		if (changed != null && !changed.isEmpty()) {
			String text = DataDomain.CATALOGUE + ": " + changed.size()
					+ " own prices replaced by the head office price (right \"may change its selling prices\" off): "
					+ String.join(", ", changed);
			log.info("Head office link: {}", text);
			exchangeLog.recordNote(CopiesDownJob.CODE, ExchangeDirection.DOWN, changed.size(), LinkJobResult.WARNING, text,
					LocalDateTime.now());
		}
	}

	@Override
	public DownApplyResult apply(List<JsonNode> page, List<String> removed) {
		DownApplyResult result = DownApplyResult.none();
		List<Copy> copies = new ArrayList<>();
		for (JsonNode record : page) {
			Copy copy;
			try {
				copy = read(record);
			} catch (Exception e) {
				result.addError(NO_CODE, "unreadable record (" + SalesCopyFinder.cause(e) + ")");
				continue;
			}
			if (copy == null) {
				result.addError(NO_CODE, "record without a kind or a code");
				continue;
			}
			copy.payload = record.toString();
			copies.add(copy);
		}
		copies.sort(Comparator.comparing(copy -> copy.kind)); // a family before its sub-families, items, barcodes
		Map<String, DownRecord> known = trackingRows(copies);
		for (Copy copy : copies) {
			appliedThisCycle.add(copy.code);
			applyAndTrack(copy, copy.payload, result, known);
		}
		for (String code : removed) {
			CatalogueKind kind = CatalogueKind.ofRecordCode(code);
			try {
				transactions.executeWithoutResult(status -> {
					boolean changed = kind != null && writer.remove(kind, CatalogueKind.codeOf(code));
					boolean tracked = records.remove(DataDomain.CATALOGUE, code);
					if (changed || tracked) {
						result.addRemoved();
					}
				});
			} catch (RuntimeException e) {
				result.addError(code, "not removed (" + SalesCopyFinder.cause(e) + ")");
			}
		}
		return result;
	}

	/** Every cycle, the records WAITING or in ERROR are applied again from the copy last received. */
	@Override
	public DownApplyResult retry() {
		DownApplyResult result = DownApplyResult.none();
		try {
			List<DownRecord> rows = transactions.execute(status -> records.toRetry(DataDomain.CATALOGUE));
			List<Copy> copies = new ArrayList<>();
			for (DownRecord row : rows == null ? new ArrayList<DownRecord>() : rows) {
				if (appliedThisCycle.contains(row.getRecordCode())) {
					continue;
				}
				Copy copy;
				try {
					copy = read(COPY_MAPPER.readTree(row.getPayload()));
				} catch (Exception e) {
					copy = null;
				}
				if (copy == null) {
					result.addError(row.getRecordCode(), row.getReason()); // stays as it is until a new copy comes
					continue;
				}
				copies.add(copy);
			}
			copies.sort(Comparator.comparing(copy -> copy.kind));
			for (Copy copy : copies) {
				applyAndTrack(copy, null, result, null);
			}
		} finally {
			appliedThisCycle.clear();
		}
		return result;
	}

	/**
	 * The tracking rows of a page's records, read once (not once per record); null when the read fails: each record
	 * then reads its own, as before.
	 */
	private Map<String, DownRecord> trackingRows(List<Copy> copies) {
		if (copies.isEmpty()) {
			return null;
		}
		List<String> codes = copies.stream().map(copy -> copy.code).distinct().collect(Collectors.toList());
		try {
			return transactions.execute(status -> records.rowsOf(DataDomain.CATALOGUE, codes));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * One record in its own transaction with its tracking row; payload null keeps the stored copy (a retry). known: the
	 * page's tracking rows ({@link #trackingRows}), or null (the row is read here).
	 */
	private void applyAndTrack(Copy copy, String payload, DownApplyResult result, Map<String, DownRecord> known) {
		CatalogueCopyWriter.Outcome outcome;
		try {
			outcome = transactions.execute(status -> {
				CatalogueCopyWriter.Outcome saved = save(copy);
				records.track(DataDomain.CATALOGUE, copy.code, copy.name,
						saved.isApplied() ? DownRecordStatus.APPLIED : DownRecordStatus.WAITING, saved.getReason(),
						saved.getInfo(), payload, known);
				return saved;
			});
		} catch (RuntimeException e) {
			if (known != null) {
				known.remove(copy.code); // rolled back: the row in memory may not be the database's; read it again
			}
			String reason = "not saved (" + SalesCopyFinder.cause(e) + ")";
			result.addError(copy.code, reason);
			try {
				transactions.executeWithoutResult(status -> records.track(DataDomain.CATALOGUE, copy.code, copy.name,
						DownRecordStatus.ERROR, reason, null, payload));
			} catch (RuntimeException ignored) {
				// the row is written at the next cycle; the error is already counted
			}
			return;
		}
		for (String note : outcome.getNotes()) {
			log.info("Head office link: {}: {}", DataDomain.CATALOGUE, note);
			exchangeLog.recordNote(CopiesDownJob.CODE, ExchangeDirection.DOWN, 1, LinkJobResult.WARNING,
					DataDomain.CATALOGUE + ": " + note, LocalDateTime.now());
		}
		if (!outcome.isApplied()) {
			result.addWaiting(copy.code, outcome.getReason());
		} else if (outcome.isWritten()) {
			result.addApplied();
		} else {
			result.addUnchanged();
		}
	}

	private CatalogueCopyWriter.Outcome save(Copy copy) {
		switch (copy.kind) {
			case CATEGORY:
				return writer.saveCategory(copy.category);
			case SECTION:
				return writer.saveSection(copy.section);
			case FAMILY:
				return writer.saveFamily(copy.family);
			case SUBFAMILY:
				return writer.saveSubFamily(copy.subFamily);
			case ITEM:
				return writer.saveItem(copy.item);
			default:
				return writer.saveBarcode(copy.barcode);
		}
	}

	/** The record as a copy of its kind, with its record code and name; null without a known kind or a code. */
	static Copy read(JsonNode record) throws Exception {
		JsonNode kindNode = record == null ? null : record.get("kind");
		if (kindNode == null) {
			return null;
		}
		CatalogueKind kind;
		try {
			kind = CatalogueKind.valueOf(kindNode.asText());
		} catch (IllegalArgumentException e) {
			return null;
		}
		Copy copy = new Copy(kind);
		String code;
		switch (kind) {
			case CATEGORY:
				copy.category = COPY_MAPPER.treeToValue(record, CatalogueCategoryCopyDTO.class);
				code = copy.category.getCode();
				copy.name = copy.category.getName();
				break;
			case SECTION:
				copy.section = COPY_MAPPER.treeToValue(record, CatalogueSectionCopyDTO.class);
				code = copy.section.getCode();
				copy.name = copy.section.getName();
				break;
			case FAMILY:
				copy.family = COPY_MAPPER.treeToValue(record, CatalogueFamilyCopyDTO.class);
				code = copy.family.getCode();
				copy.name = copy.family.getName();
				break;
			case SUBFAMILY:
				copy.subFamily = COPY_MAPPER.treeToValue(record, CatalogueSubFamilyCopyDTO.class);
				code = copy.subFamily.getCode();
				copy.name = copy.subFamily.getName();
				break;
			case ITEM:
				copy.item = COPY_MAPPER.treeToValue(record, CatalogueItemCopyDTO.class);
				code = copy.item.getItemCode();
				copy.name = copy.item.getName();
				break;
			default:
				copy.barcode = COPY_MAPPER.treeToValue(record, CatalogueBarcodeCopyDTO.class);
				code = copy.barcode.getBarcode();
				copy.name = copy.barcode.getItemCode();
				break;
		}
		if (code == null || code.trim().isEmpty()) {
			return null;
		}
		copy.code = kind.recordCode(code);
		return copy;
	}

	/** One record of the page. */
	static final class Copy {
		final CatalogueKind kind;
		String code;
		String name;
		String payload;
		CatalogueCategoryCopyDTO category;
		CatalogueSectionCopyDTO section;
		CatalogueFamilyCopyDTO family;
		CatalogueSubFamilyCopyDTO subFamily;
		CatalogueItemCopyDTO item;
		CatalogueBarcodeCopyDTO barcode;

		Copy(CatalogueKind kind) {
			this.kind = kind;
		}
	}
}
