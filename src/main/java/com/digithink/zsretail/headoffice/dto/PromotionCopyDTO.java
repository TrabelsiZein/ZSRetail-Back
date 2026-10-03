package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.Promotion;
import com.digithink.zsretail.model.enumeration.PromotionBenefitType;
import com.digithink.zsretail.model.enumeration.PromotionScope;
import com.digithink.zsretail.model.enumeration.PromotionType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 3.3: a promotion as it travels down to the stores, by business codes only (promotion code,
 * item code, family code, sub-family code, group item codes, benefit item code), never a database id. The store
 * resolves the codes to its own records. Also used by the store to compare what it has with what it receives: two
 * promotions with equal copies are the same promotion.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromotionCopyDTO {

	private String code;
	private String name;
	private String description;
	private PromotionType promotionType;
	private PromotionScope scope;

	/** Item.itemCode of the target item; null when none. */
	private String itemCode;

	/** ItemFamily.code; null when none. */
	private String itemFamilyCode;

	/** ItemSubFamily.code; null when none. */
	private String itemSubFamilyCode;

	/** Item codes of an ITEM_GROUP promotion, sorted; empty for the other scopes. */
	private List<String> groupItemCodes = new ArrayList<>();

	/** Item code of the cross-product benefit (getItem); null when none. */
	private String getItemCode;

	private Integer minimumQuantity;
	private Double minimumAmount;
	private PromotionBenefitType benefitType;
	private Double discountPercentage;
	private Double discountAmount;
	private Integer freeQuantity;
	private LocalDate startDate;
	private LocalDate endDate;
	private Boolean requiresCode;
	private String dayOfWeek;
	private LocalTime timeStart;
	private LocalTime timeEnd;
	private Integer priority;
	private Boolean active;

	/** The copy of a promotion; reads its lazy group items and benefit item (call inside a transaction). */
	public static PromotionCopyDTO of(Promotion promotion) {
		PromotionCopyDTO copy = new PromotionCopyDTO();
		copy.code = promotion.getCode();
		copy.name = promotion.getName();
		copy.description = promotion.getDescription();
		copy.promotionType = promotion.getPromotionType();
		copy.scope = promotion.getScope();
		copy.itemCode = promotion.getItem() == null ? null : promotion.getItem().getItemCode();
		copy.itemFamilyCode = promotion.getItemFamily() == null ? null : promotion.getItemFamily().getCode();
		copy.itemSubFamilyCode = promotion.getItemSubFamily() == null ? null : promotion.getItemSubFamily().getCode();
		copy.groupItemCodes = promotion.getGroupItems() == null ? new ArrayList<>()
				: promotion.getGroupItems().stream().map(Item::getItemCode).sorted().collect(Collectors.toList());
		copy.getItemCode = promotion.getGetItem() == null ? null : promotion.getGetItem().getItemCode();
		copy.minimumQuantity = promotion.getMinimumQuantity();
		copy.minimumAmount = promotion.getMinimumAmount();
		copy.benefitType = promotion.getBenefitType();
		copy.discountPercentage = promotion.getDiscountPercentage();
		copy.discountAmount = promotion.getDiscountAmount();
		copy.freeQuantity = promotion.getFreeQuantity();
		copy.startDate = promotion.getStartDate();
		copy.endDate = promotion.getEndDate();
		copy.requiresCode = promotion.getRequiresCode();
		copy.dayOfWeek = promotion.getDayOfWeek();
		copy.timeStart = promotion.getTimeStart();
		copy.timeEnd = promotion.getTimeEnd();
		copy.priority = promotion.getPriority();
		copy.active = promotion.getActive();
		return copy;
	}
}
