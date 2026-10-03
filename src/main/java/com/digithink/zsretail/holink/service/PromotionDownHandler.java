package com.digithink.zsretail.holink.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import com.digithink.zsretail.config.ConditionalOnHeadOfficeOwned;
import com.digithink.zsretail.headoffice.dto.PromotionCopyDTO;
import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.holink.model.DownRecord;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemFamily;
import com.digithink.zsretail.model.ItemSubFamily;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.digithink.zsretail.model.enumeration.RecordOrigin;
import com.digithink.zsretail.repository.ItemFamilyRepository;
import com.digithink.zsretail.repository.ItemRepository;
import com.digithink.zsretail.repository.ItemSubFamilyRepository;
import com.digithink.zsretail.repository.PromotionRepository;
import com.digithink.zsretail.service.PromotionService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Head office plan, task 3.3: the store side of the promotions on the copies down mechanism. Exists only when
 * promotions are owned by the head office. A received promotion is saved by its code with origin HEAD_OFFICE, its
 * codes resolved to this store's records, with the normalisation of {@link PromotionService#save} and without the
 * "used promotion" lock. A promotion equal to the one the store has is not written again. The calculation engine is
 * not changed: it reads the promotion table as before. Task 3.5: each received promotion is tracked in hol_down_record
 * (APPLIED, WAITING for a missing target, ERROR) and the ones not applied are retried at every cycle.
 * <p>
 * See docs/modules/head-office.md, "Promotions owned by the head office".
 */
@Component
@ConditionalOnHeadOfficeOwned(DataDomain.PROMOTIONS)
public class PromotionDownHandler implements DownHandler {

	static final String CODE_CLASH = "code already used by a local promotion of this store";

	/** Task 3.5: start of the reason of a WAITING promotion, followed by what is missing. */
	static final String NOT_IN_STORE = "not in this store: ";

	static final String NO_CODE = "?";

	static final int TIMEOUT_SECONDS = 15;

	static final ObjectMapper COPY_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	private final PromotionService promotionService;
	private final PromotionRepository promotions;
	private final ItemRepository items;
	private final ItemFamilyRepository families;
	private final ItemSubFamilyRepository subFamilies;
	private final TransactionOperations transactions;
	private final DownRecordLog records;

	/** Codes applied by the pulls of the current cycle: the retry that follows skips them. ho-link thread only. */
	private final Set<String> appliedThisCycle = new HashSet<>();

	@Autowired
	public PromotionDownHandler(PromotionService promotionService, PromotionRepository promotions,
			ItemRepository items, ItemFamilyRepository families, ItemSubFamilyRepository subFamilies,
			DownRecordLog records, PlatformTransactionManager transactionManager) {
		this(promotionService, promotions, items, families, subFamilies, records, timed(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public PromotionDownHandler(PromotionService promotionService, PromotionRepository promotions,
			ItemRepository items, ItemFamilyRepository families, ItemSubFamilyRepository subFamilies,
			DownRecordLog records, TransactionOperations transactions) {
		this.records = records;
		this.promotionService = promotionService;
		this.promotions = promotions;
		this.items = items;
		this.families = families;
		this.subFamilies = subFamilies;
		this.transactions = transactions;
	}

	private static TransactionTemplate timed(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setTimeout(TIMEOUT_SECONDS);
		return template;
	}

	@Override
	public DataDomain getDomain() {
		return DataDomain.PROMOTIONS;
	}

	/** Promotions owned by the head office: the local ones are set inactive, kept, origin unchanged. */
	@Override
	public int deactivateLocal() {
		Integer count = transactions.execute(status -> {
			List<Promotion> local = promotions.findActiveNotFrom(RecordOrigin.HEAD_OFFICE);
			for (Promotion promotion : local) {
				deactivate(promotion);
			}
			return local.size();
		});
		return count == null ? 0 : count;
	}

	/**
	 * Applies each record and tracks it (task 3.5): APPLIED, WAITING (a target is missing) or ERROR, with the copy for
	 * the retries. Applying the same page twice writes nothing.
	 */
	@Override
	public DownApplyResult apply(List<JsonNode> records, List<String> removed) {
		DownApplyResult result = DownApplyResult.none();
		for (JsonNode record : records) {
			String code = codeOf(record);
			String payload = record.toString();
			PromotionCopyDTO copy;
			try {
				copy = COPY_MAPPER.treeToValue(record, PromotionCopyDTO.class);
			} catch (Exception e) {
				failed(code, "unreadable record (" + SalesCopyFinder.cause(e) + ")", payload, result);
				continue;
			}
			if (copy.getCode() == null || copy.getCode().trim().isEmpty()) {
				result.addError(NO_CODE, "record without a code");
				continue;
			}
			appliedThisCycle.add(copy.getCode());
			applyAndTrack(copy, payload, result);
		}
		for (String code : removed) {
			try {
				transactions.executeWithoutResult(status -> remove(code, result));
			} catch (RuntimeException e) {
				result.addError(code, "not removed (" + SalesCopyFinder.cause(e) + ")");
			}
		}
		return result;
	}

	/**
	 * Task 3.5: every cycle, the records WAITING or in ERROR are applied again from the copy last received, without a
	 * new change from the head office; a promotion whose item has arrived applies by itself. The records the pulls of
	 * this cycle just applied are skipped.
	 */
	@Override
	public DownApplyResult retry() {
		DownApplyResult result = DownApplyResult.none();
		try {
			List<DownRecord> rows = transactions.execute(status -> records.toRetry(DataDomain.PROMOTIONS));
			for (DownRecord row : rows == null ? new ArrayList<DownRecord>() : rows) {
				if (appliedThisCycle.contains(row.getRecordCode())) {
					continue;
				}
				PromotionCopyDTO copy;
				try {
					copy = COPY_MAPPER.readValue(row.getPayload(), PromotionCopyDTO.class);
				} catch (Exception e) {
					result.addError(row.getRecordCode(), row.getReason()); // stays as it is until a new copy comes
					continue;
				}
				applyAndTrack(copy, null, result);
			}
		} finally {
			appliedThisCycle.clear();
		}
		return result;
	}

	/** One record in its own transaction with its tracking row; payload null keeps the stored copy (a retry). */
	private void applyAndTrack(PromotionCopyDTO copy, String payload, DownApplyResult result) {
		String code = copy.getCode();
		Outcome outcome;
		try {
			outcome = transactions.execute(status -> {
				Outcome applied = applyCopy(copy);
				records.track(DataDomain.PROMOTIONS, code, copy.getName(), applied.status, applied.reason, applied.info,
						payload);
				return applied;
			});
		} catch (RuntimeException e) {
			failed(code, "not saved (" + SalesCopyFinder.cause(e) + ")", payload, result);
			return;
		}
		switch (outcome.status) {
			case APPLIED:
				if (outcome.written) {
					result.addApplied();
				} else {
					result.addUnchanged();
				}
				break;
			case WAITING:
				result.addWaiting(code, outcome.reason);
				break;
			default:
				result.addError(code, outcome.reason);
		}
	}

	/** An ERROR the record could not even be tried for: tracked in a transaction of its own when possible. */
	private void failed(String code, String reason, String payload, DownApplyResult result) {
		result.addError(code, reason);
		if (NO_CODE.equals(code)) {
			return;
		}
		try {
			transactions.executeWithoutResult(
					status -> records.track(DataDomain.PROMOTIONS, code, null, DownRecordStatus.ERROR, reason, null, payload));
		} catch (RuntimeException e) {
			// the row is written at the next cycle; the error is already counted
		}
	}

	private Outcome applyCopy(PromotionCopyDTO copy) {
		Optional<Promotion> existing = promotions.findByCode(copy.getCode());
		if (existing.isPresent() && !PromotionService.isFromHeadOffice(existing.get())) {
			return new Outcome(DownRecordStatus.ERROR, CODE_CLASH, null, false);
		}
		Resolved resolved = resolve(copy);
		if (!resolved.missing.isEmpty()) {
			// Not applied; a promotion the store already has stops applying until its targets are here
			boolean written = false;
			if (existing.isPresent() && !Boolean.FALSE.equals(existing.get().getActive())) {
				deactivate(existing.get());
				written = true;
			}
			return new Outcome(DownRecordStatus.WAITING, NOT_IN_STORE + String.join(", ", resolved.missing), null,
					written);
		}
		String info = resolved.missingGroupItems.isEmpty() ? null
				: "group items not in this store: " + String.join(", ", resolved.missingGroupItems);
		Promotion candidate = build(copy, resolved, existing.map(Promotion::getId).orElse(null));
		promotionService.normalise(candidate);
		if (existing.isPresent() && PromotionCopyDTO.of(existing.get()).equals(PromotionCopyDTO.of(candidate))) {
			return new Outcome(DownRecordStatus.APPLIED, null, info, false);
		}
		promotionService.saveReceived(candidate);
		return new Outcome(DownRecordStatus.APPLIED, null, info, true);
	}

	/**
	 * Removed at the head office, or this store taken off: a head office promotion never used in a sale here is
	 * deleted, a used one is set inactive and kept. A local promotion with that code, or no promotion, is left alone.
	 * The tracking row is deleted.
	 */
	private void remove(String code, DownApplyResult result) {
		boolean tracked = records.remove(DataDomain.PROMOTIONS, code);
		Optional<Promotion> existing = promotions.findByCode(code);
		if (!existing.isPresent() || !PromotionService.isFromHeadOffice(existing.get())) {
			if (tracked) {
				result.addRemoved();
			}
			return;
		}
		Promotion promotion = existing.get();
		if (promotionService.getLocalUsageCount(promotion.getId()) == 0) {
			promotions.delete(promotion);
			result.addRemoved();
		} else if (Boolean.FALSE.equals(promotion.getActive())) {
			if (tracked) {
				result.addRemoved();
			} else {
				result.addUnchanged();
			}
		} else {
			deactivate(promotion);
			result.addRemoved();
		}
	}

	/** What applying one copy gave. */
	private static final class Outcome {
		final DownRecordStatus status;
		final String reason;
		final String info;

		/** Something was written to the promotion table. */
		final boolean written;

		Outcome(DownRecordStatus status, String reason, String info, boolean written) {
			this.status = status;
			this.reason = reason;
			this.info = info;
			this.written = written;
		}
	}

	/** active = false only, audited as the head office; nothing else of the promotion changes. */
	private void deactivate(Promotion promotion) {
		promotion.setActive(false);
		promotion.setUpdatedBy(PromotionService.HEAD_OFFICE_USER);
		promotion.setUpdatedAt(LocalDateTime.now());
		promotions.save(promotion);
	}

	/** The codes of the copy resolved to this store's records; what is missing, as "item X", "family Y"... */
	Resolved resolve(PromotionCopyDTO copy) {
		Resolved resolved = new Resolved();
		if (copy.getItemCode() != null) {
			resolved.item = items.findByItemCode(copy.getItemCode()).orElse(null);
			if (resolved.item == null) {
				resolved.missing.add("item " + copy.getItemCode());
			}
		}
		if (copy.getItemFamilyCode() != null) {
			resolved.family = families.findByCode(copy.getItemFamilyCode()).orElse(null);
			if (resolved.family == null) {
				resolved.missing.add("family " + copy.getItemFamilyCode());
			}
		}
		if (copy.getItemSubFamilyCode() != null) {
			resolved.subFamily = subFamilies.findByCode(copy.getItemSubFamilyCode()).orElse(null);
			if (resolved.subFamily == null) {
				resolved.missing.add("sub-family " + copy.getItemSubFamilyCode());
			}
		}
		if (copy.getGetItemCode() != null) {
			resolved.getItem = items.findByItemCode(copy.getGetItemCode()).orElse(null);
			if (resolved.getItem == null) {
				resolved.missing.add("benefit item " + copy.getGetItemCode());
			}
		}
		if (copy.getGroupItemCodes() != null) {
			for (String code : copy.getGroupItemCodes()) {
				Optional<Item> item = items.findByItemCode(code);
				if (item.isPresent()) {
					resolved.groupItems.add(item.get());
				} else {
					resolved.missingGroupItems.add(code);
				}
			}
			if (!copy.getGroupItemCodes().isEmpty() && resolved.groupItems.isEmpty()) {
				resolved.missing.add("group items " + String.join(", ", resolved.missingGroupItems));
			}
		}
		return resolved;
	}

	/** The promotion as the store saves it: every field of the copy, the resolved records, origin HEAD_OFFICE. */
	private static Promotion build(PromotionCopyDTO copy, Resolved resolved, Long id) {
		Promotion promotion = new Promotion();
		promotion.setId(id);
		promotion.setOrigin(RecordOrigin.HEAD_OFFICE);
		promotion.setCode(copy.getCode());
		promotion.setName(copy.getName());
		promotion.setDescription(copy.getDescription());
		promotion.setPromotionType(copy.getPromotionType());
		promotion.setScope(copy.getScope());
		promotion.setItem(resolved.item);
		promotion.setItemFamily(resolved.family);
		promotion.setItemSubFamily(resolved.subFamily);
		promotion.setGroupItems(new HashSet<>(resolved.groupItems));
		promotion.setGetItem(resolved.getItem);
		promotion.setMinimumQuantity(copy.getMinimumQuantity());
		promotion.setMinimumAmount(copy.getMinimumAmount());
		promotion.setBenefitType(copy.getBenefitType());
		promotion.setDiscountPercentage(copy.getDiscountPercentage());
		promotion.setDiscountAmount(copy.getDiscountAmount());
		promotion.setFreeQuantity(copy.getFreeQuantity());
		promotion.setStartDate(copy.getStartDate());
		promotion.setEndDate(copy.getEndDate());
		promotion.setRequiresCode(copy.getRequiresCode() == null ? Boolean.FALSE : copy.getRequiresCode());
		promotion.setDayOfWeek(copy.getDayOfWeek());
		promotion.setTimeStart(copy.getTimeStart());
		promotion.setTimeEnd(copy.getTimeEnd());
		promotion.setPriority(copy.getPriority() == null ? 0 : copy.getPriority());
		promotion.setActive(copy.getActive() == null ? Boolean.TRUE : copy.getActive());
		return promotion;
	}

	private static String codeOf(JsonNode record) {
		JsonNode code = record == null ? null : record.get("code");
		return code == null || code.isNull() ? "?" : code.asText();
	}

	/** The local records of a copy. */
	static final class Resolved {
		Item item;
		ItemFamily family;
		ItemSubFamily subFamily;
		Item getItem;
		final Set<Item> groupItems = new HashSet<>();
		final List<String> missingGroupItems = new ArrayList<>();
		final List<String> missing = new ArrayList<>();
	}
}
