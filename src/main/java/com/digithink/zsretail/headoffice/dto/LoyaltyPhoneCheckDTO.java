package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: answer of GET /ho/loyalty/members/by-phone: whether the phone already has a card in the
 * network, and that member (the active card first, as the duplicate message names it).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoyaltyPhoneCheckDTO {

	private boolean found;
	private LoyaltyMemberCopyDTO member;
}
