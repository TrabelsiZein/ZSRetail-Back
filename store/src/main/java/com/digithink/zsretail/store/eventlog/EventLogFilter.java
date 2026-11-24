package com.digithink.zsretail.store.eventlog;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import lombok.Getter;

/**
 * 3.4.0, event log step 6: the filters of the Journal page and of its Excel file (GET /event-log, /event-log/excel). The
 * period is required and runs on the event's own time (occurred_at), from the first day's midnight to the day after the
 * last one. Every other filter is optional; several importances or codes are an OR, the filters between them an AND.
 * {@link #of} refuses what does not parse, and says why.
 */
@Getter
public final class EventLogFilter {

	/** The free text is cut to this length (the sentence column is 2,000; a search longer than this is a paste). */
	static final int TEXT_LENGTH = 100;

	/** Step 7: the sessions of one filter at most (a SQL Server query takes 2,100 parameters). */
	static final int MAX_SESSIONS = 1000;

	private LocalDate from;
	private LocalDate to;
	private List<Integer> importances = Collections.emptyList();
	private Long userId;
	private Long authorisedById;
	private Long badgeOwnerId;
	private Long posMachineId;
	private Long sessionId;
	/** Step 7: several sessions (the links of "À surveiller": a cashier's sessions of the period); an OR with sessionId. */
	private List<Long> sessionIds = Collections.emptyList();
	/** Step 7: the new values, one of them (a cash movement's kind, a refusal's reason). */
	private List<String> newValues = Collections.emptyList();
	private String sessionNumber;
	private String subjectType;
	private String subjectId;
	private String cartId;
	private List<String> codes = Collections.emptyList();
	private String text;
	private boolean doubtOnly;
	/** The rows of one run of refused logins (the line opened); the list is then not grouped. */
	private String groupKey;

	private EventLogFilter() {
	}

	/** A filter that parses; the reason in {@link Refused} otherwise. */
	static EventLogFilter of(String from, String to, String importances, Long userId, Long authorisedById,
			Long badgeOwnerId, Long posMachineId, Long sessionId, String sessionNumber, String subjectType,
			String subjectId, String cartId, String codes, String text, boolean doubtOnly, String groupKey) {
		return of(from, to, importances, userId, authorisedById, badgeOwnerId, posMachineId, sessionId, sessionNumber,
				subjectType, subjectId, cartId, codes, text, doubtOnly, groupKey, null, null);
	}

	/** Step 7: with {@code sessionIds} and {@code newValues}, comma separated. */
	static EventLogFilter of(String from, String to, String importances, Long userId, Long authorisedById,
			Long badgeOwnerId, Long posMachineId, Long sessionId, String sessionNumber, String subjectType,
			String subjectId, String cartId, String codes, String text, boolean doubtOnly, String groupKey,
			String sessionIds, String newValues) {
		EventLogFilter filter = new EventLogFilter();
		if (blank(from) || blank(to)) {
			throw new Refused("from and to are required (dates YYYY-MM-DD)");
		}
		try {
			filter.from = LocalDate.parse(from.trim());
			filter.to = LocalDate.parse(to.trim());
		} catch (Exception e) {
			throw new Refused("from / to: '" + from + "' to '" + to + "' is not a period (dates YYYY-MM-DD)");
		}
		if (filter.to.isBefore(filter.from)) {
			throw new Refused("to (" + to + ") is before from (" + from + ")");
		}
		filter.importances = numbers(importances);
		filter.userId = userId;
		// step 8: one filter "Badge de", the authoriser or the badge's owner; authorisedById of before (links,
		// bookmarks) is the same filter
		filter.authorisedById = null;
		filter.badgeOwnerId = badgeOwnerId != null ? badgeOwnerId : authorisedById;
		filter.posMachineId = posMachineId;
		filter.sessionId = sessionId;
		filter.sessionNumber = trimmed(sessionNumber);
		filter.subjectType = upper(subjectType);
		filter.subjectId = trimmed(subjectId);
		filter.cartId = trimmed(cartId);
		filter.codes = words(codes);
		String t = trimmed(text);
		filter.text = t != null && t.length() > TEXT_LENGTH ? t.substring(0, TEXT_LENGTH) : t;
		filter.doubtOnly = doubtOnly;
		filter.groupKey = trimmed(groupKey);
		List<Long> sessions = new ArrayList<>();
		for (String word : words(sessionIds)) {
			try {
				sessions.add(Long.valueOf(word));
			} catch (NumberFormatException e) {
				throw new Refused("sessionIds: '" + word + "' is not a session id");
			}
		}
		if (sessionId != null && !sessions.contains(sessionId)) {
			sessions.add(sessionId);
		}
		if (sessions.size() > MAX_SESSIONS) {
			throw new Refused("at most " + MAX_SESSIONS + " sessions, " + sessions.size() + " given");
		}
		filter.sessionIds = sessions;
		filter.sessionId = null;
		filter.newValues = words(newValues);
		return filter;
	}

	LocalDateTime start() {
		return from.atStartOfDay();
	}

	/** The day after the last one, at midnight (excluded). */
	LocalDateTime end() {
		return to.plusDays(1).atStartOfDay();
	}

	/** The same filters with these importances (the counters ignore the importance filter). */
	EventLogFilter withImportances(List<Integer> values) {
		EventLogFilter copy = copy();
		copy.importances = values;
		return copy;
	}

	private EventLogFilter copy() {
		EventLogFilter c = new EventLogFilter();
		c.from = from;
		c.to = to;
		c.importances = importances;
		c.userId = userId;
		c.authorisedById = authorisedById;
		c.badgeOwnerId = badgeOwnerId;
		c.posMachineId = posMachineId;
		c.sessionId = sessionId;
		c.sessionIds = sessionIds;
		c.newValues = newValues;
		c.sessionNumber = sessionNumber;
		c.subjectType = subjectType;
		c.subjectId = subjectId;
		c.cartId = cartId;
		c.codes = codes;
		c.text = text;
		c.doubtOnly = doubtOnly;
		c.groupKey = groupKey;
		return c;
	}

	private static List<Integer> numbers(String csv) {
		List<Integer> values = new ArrayList<>();
		for (String word : words(csv)) {
			try {
				values.add(Integer.valueOf(word));
			} catch (NumberFormatException e) {
				throw new Refused("importance '" + word + "' is not 10, 20 or 30");
			}
		}
		return values;
	}

	private static List<String> words(String csv) {
		List<String> values = new ArrayList<>();
		if (csv != null) {
			for (String word : csv.split(",")) {
				if (!word.trim().isEmpty()) {
					values.add(word.trim().toUpperCase(Locale.ROOT));
				}
			}
		}
		return values;
	}

	private static String upper(String value) {
		String t = trimmed(value);
		return t == null ? null : t.toUpperCase(Locale.ROOT);
	}

	private static String trimmed(String value) {
		return blank(value) ? null : value.trim();
	}

	private static boolean blank(String value) {
		return value == null || value.trim().isEmpty();
	}

	/** A filter that does not parse: the API answers 400 with the message. */
	static final class Refused extends RuntimeException {
		private static final long serialVersionUID = 1L;

		Refused(String message) {
			super(message);
		}
	}
}
