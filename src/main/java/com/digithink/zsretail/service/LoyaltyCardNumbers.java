package com.digithink.zsretail.service;

import java.util.Collection;
import java.util.Locale;

/**
 * Head office plan, step 4: card numbers of a shared loyalty register. A head office numbers its cards LYL-HO-000001, a
 * store whose loyalty is owned by its head office LYL-&lt;store code&gt;-000001, each with its own sequence: the highest
 * number of its prefix plus one. Today's LYL-000001 (loyalty LOCAL) is not made here.
 */
public final class LoyaltyCardNumbers {

	public static final String HEAD_OFFICE_CODE = "HO";

	static final String START = "LYL-";
	static final char LIKE_ESCAPE = '!';

	private LoyaltyCardNumbers() {
	}

	/** LYL-&lt;code&gt;-, the code trimmed and uppercase. */
	public static String prefixOf(String code) {
		return START + code.trim().toUpperCase(Locale.ROOT) + "-";
	}

	/** The next card number of this prefix: the highest six-or-more-digit sequence among the cards given, plus one. */
	public static String next(String prefix, Collection<String> cardNumbers) {
		long max = 0;
		for (String card : cardNumbers) {
			if (card == null || !card.startsWith(prefix)) {
				continue;
			}
			String suffix = card.substring(prefix.length());
			if (!suffix.isEmpty() && suffix.length() <= 18 && suffix.chars().allMatch(Character::isDigit)) {
				max = Math.max(max, Long.parseLong(suffix));
			}
		}
		return prefix + String.format("%06d", max + 1);
	}

	/** A LIKE pattern for every card of the prefix, with '!' escaping the characters LIKE reads (SQL Server: % _ [). */
	public static String likePattern(String prefix) {
		StringBuilder pattern = new StringBuilder();
		for (char c : prefix.toCharArray()) {
			if (c == '%' || c == '_' || c == '[' || c == LIKE_ESCAPE) {
				pattern.append(LIKE_ESCAPE);
			}
			pattern.append(c);
		}
		return pattern.append('%').toString();
	}
}
