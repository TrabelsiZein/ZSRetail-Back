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
 * not changed: it reads the promotion table as before.
 * <p>
 * See docs/modules/head-office.md, "Promotions owned by the head office".
 */
@Component
@ConditionalOnHeadOfficeOwned(DataDomain.PROMOTIONS)
public class PromotionDownHandler implements DownHandler {

	static final String CODE_CLASH = "code already used by a local promotion of this store";

	static final int TIMEOUT_SECONDS = 15;

	static final ObjectMapper COPY_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule())
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	private final PromotionService promotionService;
	private final PromotionRepository promotions;
	private final ItemRepository items;
	private final ItemFamilyRepository families;
	private final ItemSubFamilyRepository subFamilies;
	private final TransactionOperations transactions;

	@Autowired
	public PromotionDownHandler(PromotionService promotionService, PromotionRepository promotions,
			ItemRepository items, ItemFamilyRepository families, ItemSubFamilyRepository subFamilies,
			PlatformTransactionManager transactionManager) {
		this(promotionService, promotions, items, families, subFamilies, timed(transactionManager));
	}

	/** With given transactions: used by the tests. */
	public PromotionDownHandler(PromotionService promotionService, PromotionRepository promotions,
			ItemRepository items, ItemFamilyRepository families, ItemSubFamilyRepository subFamilies,
			TransactionOperations transactions) {
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

	@Override
	public DownApplyResult apply(List<JsonNode> records, List<String> removed) {
		DownApplyResult result = DownApplyResult.none();
		for (JsonNode record : records) {
			PromotionCopyDTO copy;
			try {
				copy = COPY_MAPPER.treeToValue(record, PromotionCopyDTO.class);
			} catch (Exception e) {
				result.addError(codeOf(record), "unreadable record (" + SalesCopyFinder.cause(e) + ")");
				continue;
			}
			if (copy.getCode() == null || copy.getCode().trim().isEmpty()) {
				result.addError("?", "record without a code");
				continue;
			}
			try {
				transactions.executeWithoutResult(status -> applyCopy(copy, result));
			} catch (RuntimeException e) {
				result.addError(copy.getCode(), "not saved (" + SalesCopyFinder.cause(e) + ")");
			}
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

	private void applyCopy(PromotionCopyDTO copy, DownApplyResult result) {
		Optional<Promotion> existing = promotions.findByCode(copy.getCode());
		if (existing.isPresent() && !PromotionService.isFromHeadOffice(existing.get())) {
			result.addError(copy.getCode(), CODE_CLASH);
			return;
		}
		Resolved resolved = resolve(copy);
		if (!resolved.missing.isEmpty()) {
			result.addError(copy.getCode(), "not applied: " + String.join(", ", resolved.missing) + " not in this store");
			return;
		}
		Promotion candidate = build(copy, resolved, existing.map(Promotion::getId).orElse(null));
		promotionService.normalise(candidate);
		if (existing.isPresent() && PromotionCopyDTO.of(existing.get()).equals(PromotionCopyDTO.of(candidate))) {
			result.addUnchanged();
			return;
		}
		promotionService.saveReceived(candidate);
		result.addApplied();
	}

	/**
	 * Removed at the head office, or this store taken off: a head office promotion never used in a sale here is
	 * deleted, a used one is set inactive and kept. A local promotion with that code, or no promotion, is left alone.
	 */
	private void remove(String code, DownApplyResult result) {
		Optional<Promotion> existing = promotions.findByCode(code);
		if (!existing.isPresent() || !PromotionService.isFromHeadOffice(existing.get())) {
			return;
		}
		Promotion promotion = existing.get();
		if (promotionService.getLocalUsageCount(promotion.getId()) == 0) {
			promotions.delete(promotion);
			result.addRemoved();
		} else if (Boolean.FALSE.equals(promotion.getActive())) {
			result.addUnchanged();
		} else {
			deactivate(promotion);
			result.addRemoved();
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
				resolved.missing.add("none of the " + copy.getGroupItemCodes().size() + " group items");
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
