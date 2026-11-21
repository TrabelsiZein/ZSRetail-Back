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

	/** The fields a template may use. Step 7: pointsBalance, the member's balance now (a welcome, a campaign). */
	public static final Set<String> FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList("firstName",
			"lastName", "cardNumber", "ticketNumber", "total", "pointsEarned", "pointsSpent", "balanceAfter", "storeName",
			"companyName", "pointsBalance")));

	/** Step 7: the fields a campaign fills (no sale behind it): the member's, its balance, the store's and company's names. */
	public static final Set<String> CAMPAIGN_FIELDS = Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
			"firstName", "lastName", "cardNumber", "pointsBalance", "storeName", "companyName")));

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

	/**
	 * Step 4, the preview: as {@link #render}, but an unknown field is kept as typed ({xyz}) and nothing is logged. Step 7:
	 * a known field without a value is kept as typed too ({companyName}): the hole shows, never an empty text.
	 */
	public static String preview(String body, Map<String, Object> values) {
		if (body == null) {
			return "";
		}
		Matcher matcher = FIELD.matcher(body);
		StringBuffer text = new StringBuffer();
		while (matcher.find()) {
			String field = matcher.group(1);
			String value = FIELDS.contains(field) ? text(field, values == null ? null : values.get(field)) : "";
			matcher.appendReplacement(text, Matcher.quoteReplacement(value.trim().isEmpty() ? matcher.group() : value));
		}
		matcher.appendTail(text);
		return text.toString();
	}

	/** Step 7: the known {fields} a text uses, each once, in the order met. */
	public static java.util.List<String> usedFields(String... texts) {
		Set<String> used = new LinkedHashSet<>();
		for (String body : texts) {
			if (body != null) {
				Matcher matcher = FIELD.matcher(body);
				while (matcher.find()) {
					if (FIELDS.contains(matcher.group(1))) {
						used.add(matcher.group(1));
					}
				}
			}
		}
		return new java.util.ArrayList<>(used);
	}

	/** Step 7: the fields of a text a campaign cannot fill (a sale's: ticket number, total, points of the sale). */
	public static java.util.List<String> saleFields(String... texts) {
		java.util.List<String> sale = usedFields(texts);
		sale.removeIf(CAMPAIGN_FIELDS::contains);
		return sale;
	}

	/**
	 * Step 7, the empty-field guard: the known fields a text uses whose value is empty (null, or only spaces). A message
	 * with one of them is not made: never a text with a hole sent silently.
	 */
	public static java.util.List<String> emptyFields(Map<String, Object> values, String... texts) {
		java.util.List<String> empty = new java.util.ArrayList<>();
		for (String field : usedFields(texts)) {
			if (text(field, values == null ? null : values.get(field)).trim().isEmpty()) {
				empty.add(field);
			}
		}
		return empty;
	}

	/**
	 * Step 7, the empty-field guard: the values with the names that stand in for each other. The company's name empty: the
	 * store's; the store's name empty: the company's; both empty: the licence holder's (Settings, Licence). The other
	 * values are kept as they are. A new map: the one given is not changed.
	 */
	public static Map<String, Object> withFallbacks(Map<String, Object> values, String licenceName) {
		Map<String, Object> filled = values == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(values);
		String company = blank(filled.get("companyName"));
		String store = blank(filled.get("storeName"));
		String licence = blank(licenceName);
		if (company == null) {
			company = store != null ? store : licence;
		}
		if (store == null) {
			store = company;
		}
		filled.put("companyName", company);
		filled.put("storeName", store);
		return filled;
	}

	/**
	 * Step 7: the member's fields put in the values where they are missing or empty (a payload of before step 7 has no
	 * pointsBalance; a store's payload holds the card of the sale, kept when present).
	 */
	public static Map<String, Object> withMember(Map<String, Object> values, String cardNumber, String firstName,
			String lastName, Integer points) {
		Map<String, Object> filled = values == null ? new java.util.LinkedHashMap<>() : new java.util.LinkedHashMap<>(values);
		putIfEmpty(filled, "cardNumber", cardNumber);
		putIfEmpty(filled, "firstName", firstName);
		putIfEmpty(filled, "lastName", lastName);
		putIfEmpty(filled, "pointsBalance", points == null ? 0 : points);
		return filled;
	}

	private static void putIfEmpty(Map<String, Object> values, String field, Object value) {
		if (blank(values.get(field)) == null && value != null) {
			values.put(field, value);
		}
	}

	private static String blank(Object value) {
		if (value == null) {
			return null;
		}
		String text = value.toString().trim();
		return text.isEmpty() ? null : text;
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

	// ─── Step 8c, WhatsApp at Meta ───────────────────────────────────

	/** A template's values at Meta numbered {{1}}, {{2}}... in the order of first appearance of our {fields}. */
	public static final String POSITIONAL = "POSITIONAL";
	/** A template's values at Meta named {{first_name}} for our {firstName} (lowercase letters and underscores). */
	public static final String NAMED = "NAMED";
	/** Meta's maximum length of a template's body. */
	public static final int WHATSAPP_BODY_MAX = 1024;

	private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

	/** Meta's name of one of our fields: firstName -> first_name. */
	public static String namedParameter(String field) {
		return field.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
	}

	/** POSITIONAL or NAMED; anything else (null, empty) POSITIONAL. */
	public static String parameterFormat(String raw) {
		return raw != null && NAMED.equalsIgnoreCase(raw.trim()) ? NAMED : POSITIONAL;
	}

	/**
	 * The template at Meta as JSON {name, language, format}: what a campaign keeps at its launch and what each WhatsApp
	 * message carries with its values. Null without a provider name.
	 */
	public static String providerTemplate(com.digithink.zsretail.core.communication.model.CommunicationTemplate template) {
		if (template.getProviderTemplateName() == null || template.getProviderTemplateName().trim().isEmpty()) {
			return null;
		}
		com.fasterxml.jackson.databind.node.ObjectNode node = JSON.createObjectNode();
		node.put("name", template.getProviderTemplateName().trim());
		node.put("language", template.providerLanguageOrOwn());
		node.put("format", parameterFormat(template.getProviderParameterFormat()));
		return node.toString();
	}

	/**
	 * A WhatsApp message's provider params: the template at Meta (providerTemplate) and the values of the body's
	 * {fields}, each once, in the order of their first appearance, filled as the text is ({@link #render}): JSON {name,
	 * language, format, values: [{field, name, value}]} (name: Meta's named parameter). Null without a template at Meta.
	 */
	public static String providerParams(String providerTemplate, String body, Map<String, Object> values) {
		if (providerTemplate == null) {
			return null;
		}
		try {
			com.fasterxml.jackson.databind.node.ObjectNode node = (com.fasterxml.jackson.databind.node.ObjectNode) JSON
					.readTree(providerTemplate);
			com.fasterxml.jackson.databind.node.ArrayNode list = node.putArray("values");
			for (String field : usedFields(body)) {
				com.fasterxml.jackson.databind.node.ObjectNode value = list.addObject();
				value.put("field", field);
				value.put("name", namedParameter(field));
				value.put("value", text(field, values == null ? null : values.get(field)));
			}
			return node.toString();
		} catch (java.io.IOException | ClassCastException e) {
			return null;
		}
	}
}
