package com.digithink.zsretail.core.communication.service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication module, step 3: a template's text with its {fields} filled from an event's payload. A field
 * without a value gives an empty text, never "null"; a field that is not in {@link #FIELDS} is left out (empty) and
 * logged once. The amount (total) is written with 3 decimals, as on the ticket.
 */
@Log4j2
public final class CommunicationTemplates {

	/** The fields a template may use. */
	public static final Set<String> FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("firstName",
			"lastName", "cardNumber", "ticketNumber", "total", "pointsEarned", "pointsSpent", "balanceAfter", "storeName",
			"companyName")));

	private static final Pattern FIELD = Pattern.compile("\\{(\\w+)\\}");

	/** The unknown fields already logged. */
	private static final Set<String> LOGGED = ConcurrentHashMap.newKeySet();

	private CommunicationTemplates() {
	}

	public static String render(String templateCode, String body, Map<String, Object> values) {
		if (body == null) {
			return "";
		}
		Matcher matcher = FIELD.matcher(body);
		StringBuffer text = new StringBuffer();
		while (matcher.find()) {
			String field = matcher.group(1);
			String value;
			if (!FIELDS.contains(field)) {
				if (LOGGED.add(templateCode + ":" + field)) {
					log.warn("Communication: template {} uses the unknown field {{}}: left out", templateCode, field);
				}
				value = "";
			} else {
				value = text(field, values == null ? null : values.get(field));
			}
			matcher.appendReplacement(text, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(text);
		return text.toString();
	}

	/** Step 4: the {fields} of a body that are not in {@link #FIELDS}, each once, in the order met. */
	public static java.util.List<String> unknownFields(String body) {
		Set<String> unknown = new LinkedHashSet<>();
		if (body != null) {
			Matcher matcher = FIELD.matcher(body);
			while (matcher.find()) {
				if (!FIELDS.contains(matcher.group(1))) {
					unknown.add(matcher.group(1));
				}
			}
		}
		return new java.util.ArrayList<>(unknown);
	}

	/** Step 4, the preview: as {@link #render}, but an unknown field is kept as typed ({xyz}) and nothing is logged. */
	public static String preview(String body, Map<String, Object> values) {
		if (body == null) {
			return "";
		}
		Matcher matcher = FIELD.matcher(body);
		StringBuffer text = new StringBuffer();
		while (matcher.find()) {
			String field = matcher.group(1);
			String value = FIELDS.contains(field) ? text(field, values == null ? null : values.get(field))
					: matcher.group();
			matcher.appendReplacement(text, Matcher.quoteReplacement(value));
		}
		matcher.appendTail(text);
		return text.toString();
	}

	private static String text(String field, Object value) {
		if (value == null) {
			return "";
		}
		if ("total".equals(field) && value instanceof Number) {
			return String.format(Locale.ROOT, "%.3f", ((Number) value).doubleValue());
		}
		if (value instanceof Double || value instanceof Float) {
			double d = ((Number) value).doubleValue();
			return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
		}
		return value.toString();
	}
}
