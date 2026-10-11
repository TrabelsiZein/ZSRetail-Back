package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.digithink.zsretail.model.LoyaltyEarningTier;
import com.digithink.zsretail.model.LoyaltyProgram;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: the active loyalty program of the head office with its tiers, copies down of the LOYALTY
 * domain (record code PROGRAM:&lt;program code&gt;). Only the active program travels; a program closed or deleted at the
 * head office is answered as removed.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoyaltyProgramCopyDTO {

	public static final String KIND = "PROGRAM";
	public static final String CODE_PREFIX = KIND + ":";

	/** Always PROGRAM. */
	private String kind = KIND;

	private String programCode;
	private String name;
	private String description;
	private LocalDate startDate;
	private LocalDate endDate;
	private Double pointsPerDinar;
	private Integer pointValueMillimes;
	private Integer minimumRedemptionPoints;
	private Double maximumRedemptionPercentage;
	private Integer pointsExpiryDays;

	/** Sorted by threshold; empty for a flat program. */
	private List<Tier> earningTiers = new ArrayList<>();

	private Boolean active;

	public static String recordCode(String programCode) {
		return CODE_PREFIX + programCode;
	}

	public static LoyaltyProgramCopyDTO of(LoyaltyProgram program) {
		LoyaltyProgramCopyDTO copy = new LoyaltyProgramCopyDTO();
		copy.setProgramCode(program.getProgramCode());
		copy.setName(program.getName());
		copy.setDescription(program.getDescription());
		copy.setStartDate(program.getStartDate());
		copy.setEndDate(program.getEndDate());
		copy.setPointsPerDinar(program.getPointsPerDinar());
		copy.setPointValueMillimes(program.getPointValueMillimes());
		copy.setMinimumRedemptionPoints(program.getMinimumRedemptionPoints());
		copy.setMaximumRedemptionPercentage(program.getMaximumRedemptionPercentage());
		copy.setPointsExpiryDays(program.getPointsExpiryDays());
		List<Tier> tiers = new ArrayList<>();
		if (program.getEarningTiers() != null) {
			for (LoyaltyEarningTier tier : program.getEarningTiers()) {
				if (tier != null) {
					tiers.add(new Tier(tier.getThresholdAmount(), tier.getPointsPerDinar()));
				}
			}
		}
		tiers.sort(Comparator.comparing(Tier::getThresholdAmount, Comparator.nullsFirst(Comparator.naturalOrder())));
		copy.setEarningTiers(tiers);
		copy.setActive(program.getActive());
		return copy;
	}

	/** One earning tier: above thresholdAmount TND, pointsPerDinar on the whole ticket. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Tier {
		private Double thresholdAmount;
		private Double pointsPerDinar;
	}
}
