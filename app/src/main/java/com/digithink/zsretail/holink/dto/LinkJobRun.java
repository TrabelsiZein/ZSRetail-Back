package com.digithink.zsretail.holink.dto;

import com.digithink.zsretail.holink.enumeration.LinkJobResult;

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

	public static LinkJobRun of(LinkJobResult result, String message, boolean runAgainSoon) {
		return new LinkJobRun(result, message, runAgainSoon);
	}

	public static LinkJobRun success(String message) {
		return new LinkJobRun(LinkJobResult.SUCCESS, message, false);
	}

	public static LinkJobRun error(String message) {
		return new LinkJobRun(LinkJobResult.ERROR, message, false);
	}
}
