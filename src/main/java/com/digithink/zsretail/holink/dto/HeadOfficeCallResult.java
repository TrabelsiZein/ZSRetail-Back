package com.digithink.zsretail.holink.dto;

import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/** Outcome of one call to the head office (task 1.4). Never carries the API key. */
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class HeadOfficeCallResult {

	private final HeadOfficeLinkState state;

	/** Why the call failed; null when ONLINE. */
	private final String message;

	/** Head office time sent with the answer (ISO-8601 with offset); only when ONLINE. */
	private final String serverTime;

	public static HeadOfficeCallResult online(String serverTime) {
		return new HeadOfficeCallResult(HeadOfficeLinkState.ONLINE, null, serverTime);
	}

	public static HeadOfficeCallResult failure(HeadOfficeLinkState state, String message) {
		return new HeadOfficeCallResult(state, message, null);
	}
}
