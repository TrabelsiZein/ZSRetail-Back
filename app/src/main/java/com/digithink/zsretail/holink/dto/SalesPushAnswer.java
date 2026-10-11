package com.digithink.zsretail.holink.dto;

import java.util.Collections;
import java.util.List;

import com.digithink.zsretail.headoffice.dto.SalesCopyResultDTO;
import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * Outcome of one batch of sales copies sent to the head office (task 2.4). Delivered: the head office answered, with
 * one result per document. Otherwise (unreachable, key refused, unexpected answer...) no document got an answer.
 */
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class SalesPushAnswer {

	/** ONLINE when delivered; otherwise the state of the failed call, as for the heartbeat. */
	private final HeadOfficeLinkState state;

	/** Why the batch was not delivered; null when delivered. */
	private final String message;

	/** One result per document when delivered; empty otherwise. */
	private final List<SalesCopyResultDTO> results;

	public static SalesPushAnswer delivered(List<SalesCopyResultDTO> results) {
		return new SalesPushAnswer(HeadOfficeLinkState.ONLINE, null, results);
	}

	public static SalesPushAnswer failed(HeadOfficeCallResult failure) {
		return new SalesPushAnswer(failure.getState(), failure.getMessage(), Collections.emptyList());
	}

	public boolean isDelivered() {
		return state == HeadOfficeLinkState.ONLINE;
	}
}
