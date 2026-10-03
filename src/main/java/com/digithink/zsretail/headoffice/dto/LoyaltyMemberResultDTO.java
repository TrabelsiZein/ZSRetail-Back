package com.digithink.zsretail.headoffice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: the head office's answer for one member a store enrolled (POST /ho/loyalty/members).
 * outcome: CREATED (new phone, saved with the store's card number), EXISTS (that card is already known, e.g. sent
 * again), MERGED (the phone already had another card: the store's card becomes an inactive alias of
 * survivingCardNumber, and its movements go to that member). member: the member as the head office holds it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoyaltyMemberResultDTO {

	public static final String CREATED = "CREATED";
	public static final String EXISTS = "EXISTS";
	public static final String MERGED = "MERGED";

	private String cardNumber;
	private boolean accepted;

	/** Why it was rejected; null when accepted. */
	private String message;

	private String outcome;
	private String survivingCardNumber;
	private LoyaltyMemberCopyDTO member;

	public static LoyaltyMemberResultDTO rejected(String cardNumber, String message) {
		return new LoyaltyMemberResultDTO(cardNumber, false, message, null, null, null);
	}
}
