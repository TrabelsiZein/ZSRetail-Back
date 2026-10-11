package com.digithink.zsretail.headoffice.dto;

import java.util.List;

import com.digithink.zsretail.model.Promotion;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 3.3: body of POST /admin/headoffice/promotions, a promotion created with its stores in one
 * transaction, so no store outside the list ever receives it. allStores true or absent with no storeIds: every store.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromotionWithTargetsDTO {

	/** The promotion, as for POST /promotion. */
	private Promotion promotion;

	private Boolean allStores;

	private List<Long> storeIds;
}
