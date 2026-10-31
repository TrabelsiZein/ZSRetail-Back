package com.digithink.zsretail.connector.parafendri.config;

import java.net.URI;
import java.net.URISyntaxException;

import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

import lombok.extern.log4j.Log4j2;

/**
 * ParaFendri, step 0: the startup refusals of the connector, as the other connectors: an {@link IllegalStateException}
 * naming the key, so the application does not start. Read from the environment before the settings are bound
 * ({@link ParafendriProperties} depends on this bean). Exists only with erp.connector=parafendri, on a store or a head
 * office alike. One INFO line when the settings are accepted: the address, the API and the company (never a password).
 * No call to Business Central at startup: the connection test is {@code ParafendriRestClient.checkCompany()}.
 */
@Component(ParafendriStartupCheck.BEAN_NAME)
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
@Log4j2
public class ParafendriStartupCheck {

	public static final String BEAN_NAME = "parafendriStartupCheck";

	static final String P = ParafendriProperties.PREFIX + ".";

	/** A company id of Business Central: a GUID, it goes into the URL as it is. */
	static final String GUID = "[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}";

	public ParafendriStartupCheck(Environment environment) {
		check(environment);
		log.info(summary(environment));
	}

	/** Throws {@link IllegalStateException} naming the key when the connector cannot start. */
	public static void check(PropertyResolver env) {
		for (String key : new String[] { "base-url", "company-id", "username", "password" }) {
			if (trimmed(env.getProperty(P + key)).isEmpty()) {
				throw new IllegalStateException("Missing value for property " + P + key + ": required with erp.connector="
						+ ParafendriConnectorDescriptor.NAME + ".");
			}
		}
		String baseUrl = trimmed(env.getProperty(P + "base-url"));
		if (!wellFormed(baseUrl)) {
			throw new IllegalStateException("Invalid value '" + baseUrl + "' for property " + P
					+ "base-url: the address of the Business Central instance, http://host:port/instance");
		}
		String companyId = trimmed(env.getProperty(P + "company-id"));
		if (!companyId.matches(GUID)) {
			throw new IllegalStateException("Invalid value '" + companyId + "' for property " + P
					+ "company-id: the company's id in the API, a GUID (e.g. 89c8b192-3f2e-f111-9f24-7ced8db3f120)");
		}
		checkRange(env, "connect-timeout-seconds", 1, Integer.MAX_VALUE);
		checkRange(env, "read-timeout-seconds", 1, Integer.MAX_VALUE);
	}

	/** The startup line: address, API and company (never a password). */
	public static String summary(PropertyResolver env) {
		return "ERP connector " + ParafendriConnectorDescriptor.NAME + " (read only, GET): "
				+ trimmed(env.getProperty(P + "base-url")) + ", API " + ParafendriProperties.API_PATH + ", company id "
				+ trimmed(env.getProperty(P + "company-id"));
	}

	/** http or https, a host, no query or fragment (the API path is added after it). */
	static boolean wellFormed(String address) {
		try {
			URI uri = new URI(address);
			return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
					&& uri.getHost() != null && uri.getQuery() == null && uri.getFragment() == null;
		} catch (URISyntaxException e) {
			return false;
		}
	}

	/** When present, a whole number from min to max. */
	private static void checkRange(PropertyResolver env, String key, long min, long max) {
		if (!env.containsProperty(P + key)) {
			return;
		}
		String raw = env.getProperty(P + key);
		long value = wholeNumber(raw);
		if (value < min || value > max) {
			throw new IllegalStateException("Invalid value '" + raw + "' for property " + P + key + ": a whole number"
					+ (max == Integer.MAX_VALUE ? ", at least " + min : " from " + min + " to " + max));
		}
	}

	/** The trimmed value as a whole number; -1 when absent or not one. */
	private static long wholeNumber(String raw) {
		try {
			return Long.parseLong(trimmed(raw));
		} catch (NumberFormatException e) {
			return -1;
		}
	}

	private static String trimmed(String raw) {
		return raw == null ? "" : raw.trim();
	}
}
