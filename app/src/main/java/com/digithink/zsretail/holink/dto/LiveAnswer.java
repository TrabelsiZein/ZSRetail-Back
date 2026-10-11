package com.digithink.zsretail.holink.dto;

import com.digithink.zsretail.holink.enumeration.HeadOfficeLinkState;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/**
 * Head office plan, step 4: outcome of a live question to the head office (a request of a user waits for it, with a
 * short timeout). Answered: the head office gave a 200 (body) or a refusal with its status and error text (e.g. 403,
 * 409). Not answered: unreachable, key refused (401), no license (402), or an answer that cannot be read.
 */
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class LiveAnswer<T> {

	/** ONLINE when the head office answered (200 or a refusal of the question); otherwise as for the heartbeat. */
	private final HeadOfficeLinkState state;

	/** Why there is no answer; null when answered. */
	private final String message;

	/** HTTP status of the answer: 200, or the refusal (400, 403, 404, 409...); 0 when not answered. */
	private final int status;

	/** The {"error"} text of a refusal; null otherwise. */
	private final String error;

	/** The body of a 200. */
	private final T body;

	public static <T> LiveAnswer<T> ok(T body) {
		return new LiveAnswer<>(HeadOfficeLinkState.ONLINE, null, 200, null, body);
	}

	public static <T> LiveAnswer<T> refused(int status, String error) {
		return new LiveAnswer<>(HeadOfficeLinkState.ONLINE, null, status, error, null);
	}

	public static <T> LiveAnswer<T> notAnswered(HeadOfficeCallResult failure) {
		return new LiveAnswer<>(failure.getState(), failure.getMessage(), 0, null, null);
	}

	public boolean isAnswered() {
		return state == HeadOfficeLinkState.ONLINE;
	}

	public boolean isOk() {
		return status == 200 && body != null;
	}
}
