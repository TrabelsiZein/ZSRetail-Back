package com.digithink.zsretail.holink.dto;

import java.util.Collections;
import java.util.List;

import com.digithink.zsretail.headoffice.dto.LoyaltyMemberResultDTO;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * Head office plan, step 4: outcome of one batch of members sent up. Delivered: one result per member. Otherwise
 * (unreachable, key refused, unexpected answer...) no member got an answer.
 */
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class LoyaltyMemberPushAnswer {

	private final HeadOfficeLinkState state;
	private final String message;
	private final List<LoyaltyMemberResultDTO> results;

	public static LoyaltyMemberPushAnswer delivered(List<LoyaltyMemberResultDTO> results) {
		return new LoyaltyMemberPushAnswer(HeadOfficeLinkState.ONLINE, null, results);
	}

	public static LoyaltyMemberPushAnswer failed(HeadOfficeCallResult failure) {
		return new LoyaltyMemberPushAnswer(failure.getState(), failure.getMessage(), Collections.emptyList());
	}

	public boolean isDelivered() {
		return state == HeadOfficeLinkState.ONLINE;
	}
}
