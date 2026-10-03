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

	/** Step 4: the store's loyalty rights sent with a heartbeat answer; null when not sent (older head office). */
	private final Boolean canEditMembers;
	private final Boolean canAdjustPoints;

	public static HeadOfficeCallResult online(String serverTime) {
		return new HeadOfficeCallResult(HeadOfficeLinkState.ONLINE, null, serverTime, null, null);
	}

	/** Step 4: a heartbeat answer with the store's loyalty rights. */
	public static HeadOfficeCallResult online(String serverTime, Boolean canEditMembers, Boolean canAdjustPoints) {
		return new HeadOfficeCallResult(HeadOfficeLinkState.ONLINE, null, serverTime, canEditMembers, canAdjustPoints);
	}

	public static HeadOfficeCallResult failure(HeadOfficeLinkState state, String message) {
		return new HeadOfficeCallResult(state, message, null, null, null);
	}
}
