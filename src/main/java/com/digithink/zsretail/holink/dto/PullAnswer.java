package com.digithink.zsretail.holink.dto;

import com.digithink.zsretail.headoffice.dto.CopiesDownAnswerDTO;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * Outcome of one pull of copies down (step 3). Delivered: the head office answered with a readable page. Otherwise
 * (unreachable, key refused, unexpected or unreadable answer) there is no page and nothing changes at the store.
 */
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class PullAnswer {

	/** ONLINE when delivered; otherwise the state of the failed call, as for the heartbeat. */
	private final HeadOfficeLinkState state;

	/** Why there is no page; null when delivered. */
	private final String message;

	/** The page when delivered; null otherwise. */
	private final CopiesDownAnswerDTO page;

	public static PullAnswer delivered(CopiesDownAnswerDTO page) {
		return new PullAnswer(HeadOfficeLinkState.ONLINE, null, page);
	}

	public static PullAnswer failed(HeadOfficeCallResult failure) {
		return new PullAnswer(failure.getState(), failure.getMessage(), null);
	}

	public boolean isDelivered() {
		return state == HeadOfficeLinkState.ONLINE;
	}
}
