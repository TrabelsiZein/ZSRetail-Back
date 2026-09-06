package com.digithink.zsretail.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.persistence.criteria.Predicate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.model.enumeration.PromotionScope;
import com.digithink.zsretail.model.enumeration.PromotionType;
import com.digithink.zsretail.repository.PromotionRepository;
import com.digithink.zsretail.repository._BaseRepository;

import lombok.extern.log4j.Log4j2;

@Service
@Log4j2
public class PromotionService extends _BaseService<Promotion, Long> {

	@Autowired
	private PromotionRepository promotionRepository;

	@Override
	protected _BaseRepository<Promotion, Long> getRepository() {
		return promotionRepository;
	}

	public PromotionRepository getPromotionRepository() {
		return promotionRepository;
	}

	/** Returns total number of sales lines + headers that reference this promotion. */
	public long getUsageCount(Long promotionId) {
		return promotionRepository.countUsages(promotionId);
	}

	/**
	 * Validates and normalizes the ITEM_GROUP target before delegating to the base save.
	 * ITEM_GROUP scope requires at least one group item and owns the target exclusively
	 * (single-target FKs are cleared); all other scopes must not carry group items.
	 */
	@Override
	@Transactional
	public Promotion save(Promotion promotion) throws Exception {
		if (promotion.getScope() == PromotionScope.ITEM_GROUP) {
			if (promotion.getGroupItems() == null || promotion.getGroupItems().isEmpty()) {
				throw new IllegalArgumentException("An ITEM_GROUP promotion must target at least one item");
			}
			promotion.setItem(null);
			promotion.setItemFamily(null);
			promotion.setItemSubFamily(null);
		} else if (promotion.getGroupItems() == null) {
			// A null collection would be skipped by merge and could leave stale join rows
			promotion.setGroupItems(new HashSet<>());
		} else {
			promotion.getGroupItems().clear();
		}

		// Cross-product benefit target: only meaningful for quantity promotions;
		// "benefit on the same purchased product" is expressed as getItem = null.
		if (promotion.getPromotionType() != PromotionType.QUANTITY_PROMOTION) {
			promotion.setGetItem(null);
		} else if (promotion.getGetItem() != null && promotion.getScope() == PromotionScope.ITEM
				&& promotion.getItem() != null
				&& entityIdEqual(promotion.getGetItem(), promotion.getItem())) {
			promotion.setGetItem(null);
		}
		return super.save(promotion);
	}

	/**
	 * Validates that an update does not change locked fields when the promotion
	 * has already been used in sales. Throws IllegalStateException if violations found.
	 *
	 * Locked fields (when used): promotionType, benefitType, discountPercentage,
	 * discountAmount, freeQuantity, scope, item, itemFamily, itemSubFamily,
	 * groupItems, getItem, minimumQuantity, minimumAmount, requiresCode, code.
	 *
	 * Allowed fields (always): name, description, startDate, endDate, active,
	 * priority, timeStart, timeEnd, dayOfWeek.
	 */
	public void validateUpdateAllowed(Long promotionId, Promotion updated) {
		Promotion existing = findById(promotionId)
				.orElseThrow(() -> new IllegalArgumentException("Promotion not found: " + promotionId));

		long usageCount = promotionRepository.countUsages(promotionId);
		if (usageCount == 0) return; // Never used — full edit allowed

		List<String> violations = new ArrayList<>();

		if (!Objects.equals(existing.getPromotionType(), updated.getPromotionType()))
			violations.add("promotionType");
		if (!Objects.equals(existing.getBenefitType(), updated.getBenefitType()))
			violations.add("benefitType");
		if (!Objects.equals(existing.getDiscountPercentage(), updated.getDiscountPercentage()))
			violations.add("discountPercentage");
		if (!Objects.equals(existing.getDiscountAmount(), updated.getDiscountAmount()))
			violations.add("discountAmount");
		if (!Objects.equals(existing.getFreeQuantity(), updated.getFreeQuantity()))
			violations.add("freeQuantity");
		if (!Objects.equals(existing.getScope(), updated.getScope()))
			violations.add("scope");
		if (!entityIdEqual(existing.getItem(), updated.getItem()))
			violations.add("item");
		if (!entityIdEqual(existing.getItemFamily(), updated.getItemFamily()))
			violations.add("itemFamily");
		if (!entityIdEqual(existing.getItemSubFamily(), updated.getItemSubFamily()))
			violations.add("itemSubFamily");
		if (!groupItemIdsEqual(existing.getGroupItems(), updated.getGroupItems()))
			violations.add("groupItems");
		if (!entityIdEqual(existing.getGetItem(), updated.getGetItem()))
			violations.add("getItem");
		if (!Objects.equals(existing.getMinimumQuantity(), updated.getMinimumQuantity()))
			violations.add("minimumQuantity");
		if (!Objects.equals(existing.getMinimumAmount(), updated.getMinimumAmount()))
			violations.add("minimumAmount");
		if (!Objects.equals(existing.getRequiresCode(), updated.getRequiresCode()))
			violations.add("requiresCode");
		if (!Objects.equals(existing.getCode(), updated.getCode()))
			violations.add("code");

		if (!violations.isEmpty()) {
			throw new IllegalStateException(
				"Promotion has been used in " + usageCount + " sale(s). Cannot modify: " + violations);
		}
	}

	/** Compares two group-item sets by item IDs (order-independent; null == empty). */
	private boolean groupItemIdsEqual(Set<Item> a, Set<Item> b) {
		Set<Long> idsA = (a == null) ? Collections.emptySet()
				: a.stream().map(Item::getId).collect(Collectors.toSet());
		Set<Long> idsB = (b == null) ? Collections.emptySet()
				: b.stream().map(Item::getId).collect(Collectors.toSet());
		return idsA.equals(idsB);
	}

	private boolean entityIdEqual(Object a, Object b) {
		if (a == null && b == null) return true;
		if (a == null || b == null) return false;
		try {
			Object idA = a.getClass().getMethod("getId").invoke(a);
			Object idB = b.getClass().getMethod("getId").invoke(b);
			return Objects.equals(idA, idB);
		} catch (Exception e) {
			return Objects.equals(a, b);
		}
	}

	/**
	 * Get paginated promotions with optional full-text search.
	 * Results are ordered by priority DESC, then createdAt DESC.
	 */
	public Page<Promotion> findAllPaginated(int page, int size, String searchTerm) {
		Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "priority").and(Sort.by(Sort.Direction.DESC, "createdAt")));

		if (StringUtils.hasText(searchTerm)) {
			Specification<Promotion> spec = buildSearchSpecification(searchTerm);
			return promotionRepository.findAll(spec, pageable);
		}

		return promotionRepository.findAll(pageable);
	}

	/**
	 * Build search specification: searches across code, name, description,
	 * promotionType (enum match), scope (enum match), benefitType (enum match), and numeric fields.
	 */
	private Specification<Promotion> buildSearchSpecification(String searchTerm) {
		return (root, query, cb) -> {
			String pattern = "%" + searchTerm.toLowerCase() + "%";

			// String LIKE predicates
			Predicate codePredicate        = cb.like(cb.lower(root.get("code")),        pattern);
			Predicate namePredicate        = cb.like(cb.lower(root.get("name")),        pattern);
			Predicate descriptionPredicate = cb.like(cb.lower(root.get("description")), pattern);

			// Enum predicates (exact match on enum name or displayName)
			Predicate promotionTypePredicate = null;
			PromotionType matchedType = PromotionType.fromString(searchTerm.trim());
			if (matchedType != null) {
				promotionTypePredicate = cb.equal(root.get("promotionType"), matchedType);
			}

			Predicate scopePredicate = null;
			PromotionScope matchedScope = PromotionScope.fromString(searchTerm.trim());
			if (matchedScope != null) {
				scopePredicate = cb.equal(root.get("scope"), matchedScope);
			}

			Predicate benefitTypePredicate = null;
			PromotionBenefitType matchedBenefit = PromotionBenefitType.fromString(searchTerm.trim());
			if (matchedBenefit != null) {
				benefitTypePredicate = cb.equal(root.get("benefitType"), matchedBenefit);
			}

			// Numeric predicates
			Predicate discountPctPredicate = null;
			Predicate discountAmtPredicate = null;
			Predicate minQtyPredicate      = null;
			try {
				Double numericValue = Double.parseDouble(searchTerm);
				discountPctPredicate = cb.equal(root.get("discountPercentage"), numericValue);
				discountAmtPredicate = cb.equal(root.get("discountAmount"),     numericValue);
				minQtyPredicate      = cb.equal(root.get("minimumQuantity"),    numericValue.intValue());
			} catch (NumberFormatException e) {
				// Not a number — skip numeric predicates
			}

			// Base OR across string fields
			Predicate combined = cb.or(codePredicate, namePredicate, descriptionPredicate);

			if (promotionTypePredicate != null) combined = cb.or(combined, promotionTypePredicate);
			if (scopePredicate         != null) combined = cb.or(combined, scopePredicate);
			if (benefitTypePredicate   != null) combined = cb.or(combined, benefitTypePredicate);
			if (discountPctPredicate   != null) combined = cb.or(combined, discountPctPredicate);
			if (discountAmtPredicate   != null) combined = cb.or(combined, discountAmtPredicate);
			if (minQtyPredicate        != null) combined = cb.or(combined, minQtyPredicate);

			return combined;
		};
	}
}
