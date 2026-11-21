package com.digithink.zsretail.core.communication.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.core.communication.CommunicationTypes;
import com.digithink.zsretail.core.communication.provider.CommunicationProvider;
import com.digithink.zsretail.core.config.ConditionalOnInstallation;
import com.digithink.zsretail.core.config.Installation;

import lombok.extern.log4j.Log4j2;

/**
 * 3.3.0, communication module, step 3: the channel lines, read once at the start, only with the module on
 * (communication.enabled is the master: off, nothing here is read).
 * <ul>
 * <li>communication.&lt;whatsapp|sms|email&gt;.enabled: true turns the channel on; absent, empty or false: off. Never
 * blocks the start: another value is read as off, with one WARN line.</li>
 * <li>communication.&lt;channel&gt;.provider: step 8e, no default: a channel switched on with no provider line, or an
 * unknown name, is off with one WARN line (before: test by default, an unknown name stopped the start). test only when
 * written: one WARN at the start (nothing leaves, the messages are recorded as sent).</li>
 * <li>communication.phone-country-code: absent or empty, 216; otherwise 1 to 3 digits; step 8e: another value is one
 * WARN and 216 (it stopped the start before).</li>
 * </ul>
 * Step 8e: only where messages are sent (COMMUNICATION_SENDS_HERE): a store whose loyalty is the head office's never
 * reads these lines (CommunicationIgnoredLines names them in one WARN).
 * See docs/modules/communication.md and docs/deployment-modes.md.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION_SENDS_HERE)
@Log4j2
public class CommunicationSettings {

	public static final String COUNTRY_CODE_KEY = "communication.phone-country-code";
	static final String DEFAULT_COUNTRY_CODE = "216";
	/** Step 8e: the provider that sends nothing; only when written (no default provider any more). */
	static final String TEST_PROVIDER = "test";

	/** Step 8a: the test-recipient mode, one line per kind of address. */
	public static final String TEST_PHONE_KEY = "communication.test-recipient.phone";
	public static final String TEST_EMAIL_KEY = "communication.test-recipient.email";

	private final Map<String, Boolean> enabled = new LinkedHashMap<>();
	private final Map<String, String> providers = new LinkedHashMap<>();
	/** Step 8e: why a channel's provider line cannot be used (empty, unknown), null when it can. */
	private final Map<String, String> providerProblems = new LinkedHashMap<>();
	/** Step 8a: why a channel set on is off (its provider cannot send, its test recipient is not an address). */
	private final Map<String, String> offReasons = new LinkedHashMap<>();
	private final String countryCode;
	private final String testPhone;
	private final String testEmail;

	@Autowired
	public CommunicationSettings(Environment environment, List<CommunicationProvider> providers) {
		this(environment, providers.stream().map(CommunicationProvider::getName).collect(Collectors.toList()), providers);
	}

	/** With the names of the providers that exist: used by the tests. */
	public CommunicationSettings(PropertyResolver env, Collection<String> providerNames) {
		this(env, providerNames, java.util.Collections.emptyList());
	}

	/** Step 8a: with the providers, a channel whose provider cannot send is off. */
	public CommunicationSettings(PropertyResolver env, Collection<String> providerNames,
			List<CommunicationProvider> providerBeans) {
		for (String channel : CommunicationTypes.CHANNELS) {
			enabled.put(channel, channelOn(env, enabledKey(channel)));
			String raw = env.getProperty(providerKey(channel));
			String name = raw == null || raw.trim().isEmpty() ? null : raw.trim().toLowerCase(Locale.ROOT);
			if (name == null) {
				providerProblems.put(channel, providerKey(channel) + " is empty: write the channel's provider ("
						+ String.join(", ", new TreeSet<>(providerNames)) + ")");
			} else if (!providerNames.contains(name)) {
				providerProblems.put(channel, providerKey(channel) + "='" + raw.trim() + "' is not a provider ("
						+ String.join(", ", new TreeSet<>(providerNames)) + ")");
				name = null;
			}
			providers.put(channel, name);
		}
		countryCode = countryCode(env.getProperty(COUNTRY_CODE_KEY));
		testPhone = testPhone(env.getProperty(TEST_PHONE_KEY), countryCode);
		testEmail = testEmail(env.getProperty(TEST_EMAIL_KEY));
		for (String channel : CommunicationTypes.CHANNELS) {
			if (!isEnabled(channel)) {
				continue;
			}
			String name = providers.get(channel);
			if (name == null) {
				// Step 8e: no provider line (or an unknown name): the channel off, like any missing setting
				enabled.put(channel, false);
				offReasons.put(channel, providerProblems.get(channel));
				log.warn("Communication: {} is on, but {}: the channel is off", channel.toLowerCase(Locale.ROOT),
						providerProblems.get(channel));
				continue;
			}
			CommunicationProvider bean = providerBeans.stream().filter(p -> p.getName().equals(name)).findFirst()
					.orElse(null);
			// Step 8b: a provider sends its own channels only (smtp email, http SMS)
			String cannot = bean == null ? null : !bean.serves(channel)
					? "the provider " + name + " does not send " + channel.toLowerCase(Locale.ROOT) + " messages"
					: bean.unavailableReason();
			if (cannot == null && blank(env.getProperty(testKey(channel))) != null && testRecipient(channel) == null) {
				cannot = testKey(channel) + "='" + env.getProperty(testKey(channel)) + "' is not "
						+ (CommunicationTypes.EMAIL.equals(channel) ? "an email" : "a phone (8 digits, or + and 8 to 15 digits)");
			}
			if (cannot != null) {
				enabled.put(channel, false);
				offReasons.put(channel, cannot);
				log.warn("Communication: {} is on with the provider {}, but {}: the channel is off",
						channel.toLowerCase(Locale.ROOT), name, cannot);
			}
		}
		// Step 8e: a channel that runs on the provider test says so: nothing leaves
		enabled.forEach((channel, on) -> {
			if (Boolean.TRUE.equals(on) && TEST_PROVIDER.equals(providers.get(channel))) {
				log.warn("Communication: {} runs on the provider test: nothing leaves, every message is recorded as sent",
						channel.toLowerCase(Locale.ROOT));
			}
		});
		log.info("Communication: channels {}, phone country code {}", enabled.entrySet().stream()
				.map(e -> e.getKey().toLowerCase(Locale.ROOT) + (e.getValue() ? " on (" + providers.get(e.getKey()) + ")" : " off"))
				.collect(Collectors.joining(", ")), countryCode);
		if (testPhone != null || testEmail != null) {
			log.info("Communication: test-recipient mode: phones to {}, emails to {}", testPhone == null ? "-" : testPhone,
					testEmail == null ? "-" : testEmail);
		}
	}

	/** Step 8a: the test-recipient line of a channel's kind of address. */
	public static String testKey(String channel) {
		return CommunicationTypes.EMAIL.equals(channel) ? TEST_EMAIL_KEY : TEST_PHONE_KEY;
	}

	/** +216 and the 8 digits, or + and 8 to 15 digits as written; null when empty or not a phone. */
	static String testPhone(String raw, String countryCode) {
		String value = blank(raw);
		if (value == null) {
			return null;
		}
		String digits = value.replace(" ", "");
		if (digits.matches("\\d{8}")) {
			return "+" + countryCode + digits;
		}
		return digits.matches("\\+\\d{8,15}") ? digits : null;
	}

	/** The email as written; null when empty or not an email. */
	static String testEmail(String raw) {
		String value = blank(raw);
		return value != null && value.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+") ? value : null;
	}

	private static String blank(String raw) {
		return raw == null || raw.trim().isEmpty() ? null : raw.trim();
	}

	/**
	 * Step 8a, the test-recipient mode: the address every message of this channel goes to instead of its own (the
	 * phone for SMS and WhatsApp, the email for email); null when the mode is off for it.
	 */
	public String testRecipient(String channel) {
		return CommunicationTypes.EMAIL.equals(channel) ? testEmail : testPhone;
	}

	/** Step 8a: why a channel set on in the properties is off (null: it is not). */
	public String offReason(String channel) {
		return offReasons.get(channel);
	}

	public static String enabledKey(String channel) {
		return "communication." + channel.toLowerCase(Locale.ROOT) + ".enabled";
	}

	public static String providerKey(String channel) {
		return "communication." + channel.toLowerCase(Locale.ROOT) + ".provider";
	}

	private static boolean channelOn(PropertyResolver env, String key) {
		String raw = env.getProperty(key);
		String value = raw == null ? "" : raw.trim();
		if ("true".equalsIgnoreCase(value)) {
			return true;
		}
		if (!value.isEmpty() && !"false".equalsIgnoreCase(value)) {
			log.warn("Communication: {}='{}' is neither true nor false: the channel is off", key, raw);
		}
		return false;
	}

	/** Step 8e: the channel runs on the provider test (nothing leaves): the Canaux tab and the campaign window say so. */
	public boolean isTestProvider(String channel) {
		return isEnabled(channel) && TEST_PROVIDER.equals(providers.get(channel));
	}

	public static String countryCode(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			return DEFAULT_COUNTRY_CODE;
		}
		if (value.startsWith("+")) {
			value = value.substring(1);
		}
		if (!value.matches("\\d{1,3}")) {
			// Step 8e: one WARN and 216, never a refused start
			if (COUNTRY_CODE_WARNED.add(raw)) {
				log.warn("Communication: {}='{}' is not 1 to 3 digits: {}", COUNTRY_CODE_KEY, raw, DEFAULT_COUNTRY_CODE);
			}
			return DEFAULT_COUNTRY_CODE;
		}
		return value;
	}

	/** Step 8e: the wrong country codes already warned about (read by the settings and the SMS provider). */
	private static final java.util.Set<String> COUNTRY_CODE_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** WHATSAPP, SMS or EMAIL; an unknown channel is off. */
	public boolean isEnabled(String channel) {
		return Boolean.TRUE.equals(enabled.get(channel));
	}

	/** Step 4: the channels switched on, in CommunicationTypes.CHANNELS order (GET /config, the page's lists). */
	public List<String> enabledChannels() {
		return CommunicationTypes.CHANNELS.stream().filter(this::isEnabled).collect(Collectors.toList());
	}

	/** The provider of the channel, e.g. test; null for an unknown channel. */
	public String provider(String channel) {
		return providers.get(channel);
	}

	/** Digits only, e.g. 216. */
	public String getCountryCode() {
		return countryCode;
	}
}
