package com.digithink.zsretail.core.eventlog.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 3.4.0, event log step 4: the refused logins in a row, in memory (lost at a restart, which is fine: it only decides the
 * importance of the next refusal). One run per existing user's login and one per address. A run ends at a successful
 * login of that login or from that address, or after {@link #RUN_ENDS_AFTER} without a refusal: a typo a day never adds
 * up to an Alert. Bounded: past {@link #MAX_RUNS} runs, the ended ones go, then all.
 */
final class LoginTries {

	/** The refusal in a row from which the event is an Alert (LOGIN_REFUSED_REPEATED). */
	static final int ALERT_FROM = 5;
	static final Duration RUN_ENDS_AFTER = Duration.ofMinutes(15);
	static final int MAX_RUNS = 10_000;

	/** A run of refusals: how many, since when, on which key ("U" + user id, "A" + address). */
	static final class Run {
		final String key;
		final int count;
		final LocalDateTime first;
		final LocalDateTime last;

		Run(String key, int count, LocalDateTime first, LocalDateTime last) {
			this.key = key;
			this.count = count;
			this.first = first;
			this.last = last;
		}

		boolean onLogin() {
			return key.startsWith("U");
		}
	}

	private final Map<String, Run> runs = new HashMap<>();

	static String loginKey(Long userId) {
		return userId == null ? null : "U" + userId;
	}

	static String addressKey(String address) {
		return address == null || address.isEmpty() ? null : "A" + address;
	}

	/** Counts a refusal on its keys (null: none); the longer of the two runs, null without a key. */
	synchronized Run refused(String loginKey, String addressKey, LocalDateTime now) {
		if (runs.size() >= MAX_RUNS) {
			runs.values().removeIf(run -> ended(run, now));
			if (runs.size() >= MAX_RUNS) {
				runs.clear();
			}
		}
		Run onLogin = count(loginKey, now);
		Run onAddress = count(addressKey, now);
		if (onLogin == null) {
			return onAddress;
		}
		return onAddress != null && onAddress.count > onLogin.count ? onAddress : onLogin;
	}

	/** A login answered with a token: its login's run and its address's run end. */
	synchronized void succeeded(String loginKey, String addressKey) {
		if (loginKey != null) {
			runs.remove(loginKey);
		}
		if (addressKey != null) {
			runs.remove(addressKey);
		}
	}

	private Run count(String key, LocalDateTime now) {
		if (key == null) {
			return null;
		}
		Run before = runs.get(key);
		Run after = before == null || ended(before, now) ? new Run(key, 1, now, now)
				: new Run(key, before.count + 1, before.first, now);
		runs.put(key, after);
		return after;
	}

	private static boolean ended(Run run, LocalDateTime now) {
		return run.last.plus(RUN_ENDS_AFTER).isBefore(now);
	}
}
