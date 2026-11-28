package com.digithink.zsretail.core.convention;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 3.6.0, conventions (decision 6): the characters of a convention code, a card prefix and a card number: A-Z, 0-9 and
 * "-", trimmed and upper-cased in code (SQL Server's collation ignores case, the tests' H2 does not). The lengths are
 * the columns'.
 */
public final class ConventionNumbers {

	private static final Pattern CHARACTERS = Pattern.compile("[A-Z0-9-]+");

	private ConventionNumbers() {
	}

	/** Trimmed and upper-cased; null when empty. */
	public static String normalise(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
	}

	/** Only A-Z, 0-9 and "-" (a normalised value; null is not). */
	public static boolean wellFormed(String normalised) {
		return normalised != null && CHARACTERS.matcher(normalised).matches();
	}
}
