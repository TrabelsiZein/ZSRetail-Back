package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 5: a manual point adjustment asked by a store that has the right
 * (POST /ho/loyalty/members/{cardNumber}/adjust). delta signed: positive adds, negative removes (never below zero).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoyaltyPointsAdjustDTO {

	private Integer delta;
	private String reason;

	/** The store user who asked, shown in the ledger with the store code. */
	private String adjustedBy;
}
