package com.digithink.zsretail.store.holink.dto;

import com.digithink.zsretail.store.holink.enumeration.LinkJobResult;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;

/** Outcome of one run of a head office link job (task 2.6): its last result and message on the jobs list. */
@Getter
@ToString
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public final class LinkJobRun {

	private final LinkJobResult result;

	private final String message;

	/** More work is waiting: the next run comes after the catch-up delay instead of the frequency. */
	private final boolean runAgainSoon;

	/**
	 * 3.1.0: the run failed on a timeout or an I/O error (the head office too slow or not reachable for a moment): the
	 * scheduler runs it again soon, a few times, before it goes back to its frequency.
	 */
	private final boolean retryable;

	public static LinkJobRun of(LinkJobResult result, String message, boolean runAgainSoon) {
		return new LinkJobRun(result, message, runAgainSoon, false);
	}

	/** A failed run worth a quick retry (a timeout or an I/O error). */
	public static LinkJobRun retryable(LinkJobResult result, String message) {
		return new LinkJobRun(result, message, false, true);
	}

	public static LinkJobRun success(String message) {
		return new LinkJobRun(LinkJobResult.SUCCESS, message, false, false);
	}

	public static LinkJobRun error(String message) {
		return new LinkJobRun(LinkJobResult.ERROR, message, false, false);
	}

	/** The same run with another message (the scheduler adds its retry to it). */
	public LinkJobRun withMessage(String newMessage) {
		return new LinkJobRun(result, newMessage, runAgainSoon, retryable);
	}
}
