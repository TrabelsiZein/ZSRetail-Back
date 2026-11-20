package com.digithink.zsretail.store.holink.dto;

import java.util.Collections;
import java.util.List;

import com.digithink.zsretail.core.link.dto.CommunicationEventAnswerDTO;
import com.digithink.zsretail.store.holink.enumeration.HeadOfficeLinkState;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * 3.3.0, communication step 6: outcome of one batch of events sent up. Delivered: one result per event. Not taken: the
 * head office takes no events (its module is off, or an older version without the endpoint: 404); nothing is counted.
 * Otherwise (unreachable, key refused, unexpected answer) no event got an answer.
 */
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class CommunicationPushAnswer {

	private final HeadOfficeLinkState state;
	private final boolean taken;
	private final String message;
	private final List<CommunicationEventAnswerDTO.Result> results;

	public static CommunicationPushAnswer delivered(List<CommunicationEventAnswerDTO.Result> results) {
		return new CommunicationPushAnswer(HeadOfficeLinkState.ONLINE, true, null, results);
	}

	/** The head office answered but takes no events. */
	public static CommunicationPushAnswer notTaken(String why) {
		return new CommunicationPushAnswer(HeadOfficeLinkState.ONLINE, false, why, Collections.emptyList());
	}

	public static CommunicationPushAnswer failed(HeadOfficeCallResult failure) {
		return new CommunicationPushAnswer(failure.getState(), true, failure.getMessage(), Collections.emptyList());
	}

	public boolean isDelivered() {
		return state == HeadOfficeLinkState.ONLINE && taken;
	}
}
