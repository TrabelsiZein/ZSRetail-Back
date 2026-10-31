package com.digithink.zsretail.core.dto;

import java.util.List;

import com.digithink.zsretail.core.model.LoyaltyEarningTier;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Public loyalty configuration sent to the frontend on page load.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyConfigDTO {

	private boolean loyaltyEnabled;
	private LoyaltyProgramDTO activeProgram;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class LoyaltyProgramDTO {
		private Long id;
		private String programCode;
		private String name;
		private String description;
		private String startDate;
		private String endDate;
		private Double pointsPerDinar;
		private Integer pointValueMillimes;
		private Integer minimumRedemptionPoints;
		private Double maximumRedemptionPercentage;
		private Integer pointsExpiryDays;
		/**
		 * 3.1.1: the earning tiers, ascending thresholds (empty: a flat program), so the till can show the points a
		 * ticket will earn with the sale's rule (LoyaltyService.earnPoints).
		 */
		private List<LoyaltyEarningTier> earningTiers;
	}
}
