package com.digithink.zsretail.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashSet;
import java.util.Set;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.model.enumeration.PromotionScope;
import com.digithink.zsretail.model.enumeration.PromotionType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Promotion entity — configurable commercial incentive rules.
 *
 * Scope determines the target (specific item, family, subfamily, or whole cart).
 * BenefitType determines what the customer gets (% off, fixed TND off, free units).
 *
 * Types supported (see PromotionType enum):
 *   SIMPLE_DISCOUNT    — scope=ITEM/ITEM_FAMILY/ITEM_SUBFAMILY, no threshold, benefitType=PERCENTAGE/FIXED
 *   QUANTITY_PROMOTION — scope=ITEM/ITEM_FAMILY/ITEM_SUBFAMILY, minimumQuantity required,
 *                        benefitType=PERCENTAGE/FIXED/FREE_QUANTITY (Buy N Get M Free)
 *   CART_DISCOUNT      — scope=CART, optional minimumAmount, benefitType=PERCENTAGE/FIXED
 */
@Entity
@Table(
	name = "promotion",
	indexes = {
		@Index(name = "idx_promotion_scope",              columnList = "scope"),
		@Index(name = "idx_promotion_active_dates",       columnList = "active,start_date,end_date"),
		@Index(name = "idx_promotion_item_id",            columnList = "item_id"),
		@Index(name = "idx_promotion_item_family_id",     columnList = "item_family_id"),
		@Index(name = "idx_promotion_item_sub_family_id", columnList = "item_sub_family_id")
	}
)
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Promotion extends _BaseEntity {

	// ─── Identity ────────────────────────────────────────────────────────────

	@Column(nullable = false, unique = true, length = 100)
	private String code;

	@Column(nullable = false)
	private String name;

	@Column(columnDefinition = "NVARCHAR(MAX)")
	private String description;

	// ─── Promotion Type ───────────────────────────────────────────────────────

	/**
	 * High-level promotion type — drives UI flow and POS calculation logic.
	 * SIMPLE_DISCOUNT: no quantity threshold, discount on item/family/subfamily.
	 * QUANTITY_PROMOTION: requires minimumQuantity; benefit can be % off, fixed off, or free items.
	 * CART_DISCOUNT: applied to whole cart total; optionally requires minimumAmount.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "promotion_type", nullable = false, length = 30)
	private PromotionType promotionType;

	// ─── Scope & Target ──────────────────────────────────────────────────────

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private PromotionScope scope;

	/** Populated when scope = ITEM */
	@ManyToOne
	@JoinColumn(name = "item_id")
	private Item item;

	/** Populated when scope = ITEM_FAMILY */
	@ManyToOne
	@JoinColumn(name = "item_family_id")
	private ItemFamily itemFamily;

	/** Populated when scope = ITEM_SUBFAMILY */
	@ManyToOne
	@JoinColumn(name = "item_sub_family_id")
	private ItemSubFamily itemSubFamily;

	/**
	 * Populated when scope = ITEM_GROUP — an arbitrary curated set of items this
	 * promotion targets (not necessarily a family/subfamily).
	 * Kept LAZY and excluded from equals/hashCode/toString so the POS scan path
	 * (which matches promotions by item/family/subfamily FK) never loads it.
	 */
	@ManyToMany(fetch = FetchType.LAZY)
	@JoinTable(
		name = "promotion_group_item",
		joinColumns = @JoinColumn(name = "promotion_id"),
		inverseJoinColumns = @JoinColumn(name = "item_id"),
		indexes = @Index(name = "idx_promo_group_item_item", columnList = "item_id"))
	// Admin UI only needs id/itemCode/name per member — keep the JSON payload flat
	@com.fasterxml.jackson.annotation.JsonIgnoreProperties({"itemFamily", "itemSubFamily"})
	@lombok.ToString.Exclude
	@lombok.EqualsAndHashCode.Exclude
	private Set<Item> groupItems = new HashSet<>();

	/**
	 * Optional cross-product benefit target (QUANTITY_PROMOTION only).
	 * When set, the benefit (percentage / fixed / free units) applies to THIS
	 * item instead of the purchased one: "buy N of <target> → benefit on getItem".
	 * Null = benefit applies to the purchased product itself (previous behavior).
	 * Cross-product promotions are evaluated exclusively by the cart pass —
	 * the per-item scan path skips them so the buy line is never discounted.
	 * LAZY: the scan path only null-checks / reads the id, which a proxy
	 * serves without loading the Item (and its EAGER family/subfamily).
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "get_item_id")
	@com.fasterxml.jackson.annotation.JsonIgnoreProperties(
			{"itemFamily", "itemSubFamily", "hibernateLazyInitializer", "handler"})
	@lombok.ToString.Exclude
	private Item getItem;

	// ─── Thresholds (conditions) ──────────────────────────────────────────────

	/** Minimum quantity of matching items required to trigger the promotion (Types C & D). Null = no threshold. */
	@Column(name = "minimum_quantity")
	private Integer minimumQuantity;

	/** Minimum cart total (TTC) to trigger the promotion (Type E / CART scope). Null = no threshold. */
	@Column(name = "minimum_amount")
	private Double minimumAmount;

	// ─── Benefit (action) ────────────────────────────────────────────────────

	@Enumerated(EnumType.STRING)
	@Column(name = "benefit_type", nullable = false, length = 30)
	private PromotionBenefitType benefitType;

	/** Discount percentage 0–100. Used when benefitType = PERCENTAGE_DISCOUNT. */
	@Column(name = "discount_percentage")
	private Double discountPercentage;

	/** Fixed discount in TND. Used when benefitType = FIXED_DISCOUNT. */
	@Column(name = "discount_amount")
	private Double discountAmount;

	/**
	 * Number of units given for free.
	 * Used when benefitType = FREE_QUANTITY (Buy minimumQuantity Get freeQuantity free).
	 */
	@Column(name = "free_quantity")
	private Integer freeQuantity;

	// ─── Validity ────────────────────────────────────────────────────────────

	/** Promotion valid from this date (inclusive). Null = no start restriction. */
	@Column(name = "start_date")
	private LocalDate startDate;

	/** Promotion valid until this date (inclusive). Null = open-ended. */
	@Column(name = "end_date")
	private LocalDate endDate;

	// ─── Promo Code ──────────────────────────────────────────────────────────

	/**
	 * When true, this promotion is NOT applied automatically by the engine.
	 * The cashier must manually enter the promotion's code at POS to activate it.
	 * The code field (already unique) serves as the promo code to enter.
	 */
	@Column(name = "requires_code", nullable = false)
	private Boolean requiresCode = false;

	// ─── Time Restriction (Happy Hour / Flash Sale) ───────────────────────────

	/**
	 * Comma-separated days of week this promotion is active.
	 * Values: MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY.
	 * Null = active every day.
	 * Example: "MONDAY,FRIDAY" = active only on Mondays and Fridays.
	 */
	@Column(name = "day_of_week", length = 100)
	private String dayOfWeek;

	/** Time of day from which the promotion is active. Null = no start time restriction. */
	@Column(name = "time_start")
	private LocalTime timeStart;

	/** Time of day until which the promotion is active. Null = no end time restriction. */
	@Column(name = "time_end")
	private LocalTime timeEnd;

	/**
	 * Resolution priority when multiple promotions match.
	 * Higher value = applied first. Default 0.
	 */
	@Column(nullable = false)
	private Integer priority = 0;
}
