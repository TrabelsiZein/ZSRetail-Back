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
 * <li>communication.&lt;channel&gt;.provider: absent or empty, test; an unknown name stops the start.</li>
 * <li>communication.phone-country-code: absent or empty, 216; otherwise 1 to 3 digits, else the start stops.</li>
 * </ul>
 * See docs/modules/communication.md and docs/deployment-modes.md.
 */
@Component
@ConditionalOnInstallation(Installation.COMMUNICATION)
@Log4j2
public class CommunicationSettings {

	public static final String COUNTRY_CODE_KEY = "communication.phone-country-code";
	static final String DEFAULT_COUNTRY_CODE = "216";
	static final String DEFAULT_PROVIDER = "test";

	private final Map<String, Boolean> enabled = new LinkedHashMap<>();
	private final Map<String, String> providers = new LinkedHashMap<>();
	private final String countryCode;

	@Autowired
	public CommunicationSettings(Environment environment, List<CommunicationProvider> providers) {
		this(environment, providers.stream().map(CommunicationProvider::getName).collect(Collectors.toList()));
	}

	/** With the names of the providers that exist: used by the tests. */
	public CommunicationSettings(PropertyResolver env, Collection<String> providerNames) {
		for (String channel : CommunicationTypes.CHANNELS) {
			enabled.put(channel, channelOn(env, enabledKey(channel)));
			providers.put(channel, provider(env, providerKey(channel), providerNames));
		}
		countryCode = countryCode(env.getProperty(COUNTRY_CODE_KEY));
		log.info("Communication: channels {}, phone country code {}", enabled.entrySet().stream()
				.map(e -> e.getKey().toLowerCase(Locale.ROOT) + (e.getValue() ? " on (" + providers.get(e.getKey()) + ")" : " off"))
				.collect(Collectors.joining(", ")), countryCode);
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

	private static String provider(PropertyResolver env, String key, Collection<String> providerNames) {
		String raw = env.getProperty(key);
		String value = raw == null || raw.trim().isEmpty() ? DEFAULT_PROVIDER : raw.trim().toLowerCase(Locale.ROOT);
		if (!providerNames.contains(value)) {
			throw new IllegalStateException("Invalid value '" + raw + "' for property " + key + ": the providers are "
					+ String.join(", ", new TreeSet<>(providerNames)) + " (absent or empty: " + DEFAULT_PROVIDER + ")");
		}
		return value;
	}

	static String countryCode(String raw) {
		String value = raw == null ? "" : raw.trim();
		if (value.isEmpty()) {
			return DEFAULT_COUNTRY_CODE;
		}
		if (value.startsWith("+")) {
			value = value.substring(1);
		}
		if (!value.matches("\\d{1,3}")) {
			throw new IllegalStateException("Invalid value '" + raw + "' for property " + COUNTRY_CODE_KEY
					+ ": 1 to 3 digits, e.g. 216 (absent or empty: " + DEFAULT_COUNTRY_CODE + ")");
		}
		return value;
	}

	/** WHATSAPP, SMS or EMAIL; an unknown channel is off. */
	public boolean isEnabled(String channel) {
		return Boolean.TRUE.equals(enabled.get(channel));
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
