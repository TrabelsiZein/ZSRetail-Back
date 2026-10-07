package com.digithink.zsretail.erp.navpospages.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.NodeOwnership;

import lombok.extern.log4j.Log4j2;

/**
 * ERP catalogue, step 5: the startup refusals of the "POS pages" connector, in the style of NodeOwnership: an
 * {@link IllegalStateException} naming the key, so the application does not start. Read from the environment before the
 * settings are bound ({@link NavPosPagesProperties} depends on this bean), so a value such as default-vat=abc gets this
 * message rather than a binding error. Exists only with erp.navpospages.enabled=true. One INFO line when the settings
 * are accepted: the address, the company, the location and the three pages (never a password).
 */
@Component(NavPosPagesStartupCheck.BEAN_NAME)
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
@Log4j2
public class NavPosPagesStartupCheck {

	public static final String BEAN_NAME = "navPosPagesStartupCheck";

	static final String P = NavPosPagesProperties.PREFIX + ".";
	static final String DYNAMICS_NAV_ENABLED_KEY = "erp.dynamicsnav.enabled";

	public NavPosPagesStartupCheck(Environment environment) {
		check(environment);
		log.info(summary(environment));
	}

	/** Throws {@link IllegalStateException} naming the key when the connector cannot start. */
	public static void check(PropertyResolver env) {
		if ("true".equalsIgnoreCase(trimmed(env.getProperty(DYNAMICS_NAV_ENABLED_KEY)))) {
			throw new IllegalStateException("Invalid combination: " + P + "enabled=true with " + DYNAMICS_NAV_ENABLED_KEY
					+ "=true. One ERP connector per installation: set one of them to false.");
		}
		if (!NodeOwnership.isErpCatalogueOnlySet(env)) {
			throw new IllegalStateException("Invalid value 'true' for property " + P + "enabled: this connector only reads"
					+ " a catalogue, on a head office whose catalogue only comes from the ERP (node.type=HEAD_OFFICE,"
					+ " ownership.catalogue=ERP, ownership.customers and ownership.supply not ERP).");
		}
		for (String key : new String[] { "base-url", "company", "username", "password", "location-code" }) {
			if (trimmed(env.getProperty(P + key)).isEmpty()) {
				throw new IllegalStateException("Missing value for property " + P + key
						+ ": required when " + P + "enabled=true.");
			}
		}
		String vat = env.getProperty(P + "default-vat");
		long vatValue = wholeNumber(vat);
		if (vatValue < 0 || vatValue > 100) {
			throw new IllegalStateException("Invalid value '" + (vat == null ? "" : vat) + "' for property " + P
					+ "default-vat: a whole number from 0 to 100 (the VAT given to every item, e.g. 19)");
		}
		String includes = trimmed(env.getProperty(P + "price-includes-vat"));
		if (!"true".equalsIgnoreCase(includes) && !"false".equalsIgnoreCase(includes)) {
			throw new IllegalStateException("Invalid value '" + includes + "' for property " + P
					+ "price-includes-vat: true (Unit_Price includes the VAT) or false (Unit_Price is before VAT)");
		}
		checkRange(env, "barcode-page-size", 1, 5000);
		checkRange(env, "max-changes-per-run", 1, Integer.MAX_VALUE);
		checkRange(env, "deactivate-guard-percent", 0, 100);
		if (env.containsProperty(P + "dry-run")) {
			String dryRun = trimmed(env.getProperty(P + "dry-run"));
			if (!"true".equalsIgnoreCase(dryRun) && !"false".equalsIgnoreCase(dryRun)) {
				throw new IllegalStateException("Invalid value '" + dryRun + "' for property " + P
						+ "dry-run: true (read and compare only) or false");
			}
		}
		checkRange(env, "connect-timeout-seconds", 1, Integer.MAX_VALUE);
		checkRange(env, "read-timeout-seconds", 1, Integer.MAX_VALUE);
	}

	/** The startup line: address, company, location and pages (never a password). */
	public static String summary(PropertyResolver env) {
		return "ERP connector navpospages (read only, GET): " + trimmed(env.getProperty(P + "base-url")) + ", company "
				+ trimmed(env.getProperty(P + "company")) + ", location " + trimmed(env.getProperty(P + "location-code"))
				+ ", pages " + env.getProperty(P + "page.categories", "ItemCategory") + ", "
				+ env.getProperty(P + "page.items", "PointStockPOS") + ", "
				+ env.getProperty(P + "page.barcodes", "ItemBarCodePOS");
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
