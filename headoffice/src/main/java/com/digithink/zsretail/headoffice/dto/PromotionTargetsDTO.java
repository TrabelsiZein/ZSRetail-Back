package com.digithink.zsretail.headoffice.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, task 3.3: the stores a promotion is addressed to. Request: {"allStores": true} or
 * {"allStores": false, "storeIds": [1, 2]}. Answer: the same plus the promotion and the stores' code and name.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PromotionTargetsDTO {

	/** promotion.id at the head office; answer only. */
	private Long promotionId;

	/** Promotion code; answer only. */
	private String code;

	/** True: every store, including stores created later. */
	private Boolean allStores;

	/** ho_store ids when not every store, sorted. */
	private List<Long> storeIds = new ArrayList<>();

	/** {id, code, name, active, ownership} of each listed store; answer only (the single promotion view). */
	private List<StoreOptionDTO> stores;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class StoreOptionDTO {
		private Long id;
		private String code;
		private String name;
		private Boolean active;

		/**
		 * Task 3.6: what the store reported it owns (DataDomain to DataOwner); null when unknown. A store whose
		 * PROMOTIONS owner is LOCAL owns its promotions and never pulls them: it is accepted in the list anyway.
		 */
		private Map<String, String> ownership;
	}
}
