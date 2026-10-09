package com.digithink.zsretail.erp.navpospages.config;

import java.util.ArrayList;
import java.util.List;

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
 * are accepted: the address, the company and the three pages (never a password). Release 2.2: location-code is no
 * longer required; the head office reads its stock points (ho_stock_point).
 */
@Component(NavPosPagesStartupCheck.BEAN_NAME)
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
@Log4j2
public class NavPosPagesStartupCheck {

	public static final String BEAN_NAME = "navPosPagesStartupCheck";

	static final String P = NavPosPagesProperties.PREFIX + ".";
	static final String DYNAMICS_NAV_ENABLED_KEY = "erp.dynamicsnav.enabled";

	/** A field or navigation property of an OData page: it goes into the URL as it is. */
	static final String ODATA_NAME = "[A-Za-z_][A-Za-z0-9_]*";

	public NavPosPagesStartupCheck(Environment environment) {
		check(environment);
		log.info(summary(environment));
		for (String key : ignoredKeys(environment)) {
			log.warn("Property {} is no longer used (release 2.2) and is ignored: the invoices are read after the General"
					+ " Setup \"Read ERP invoices after number\" (empty: every invoice of the page). Remove the line.", key);
		}
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
		for (String key : new String[] { "base-url", "company", "username", "password" }) {
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
		if (env.containsProperty(P + "max-changes-per-run")) {
			throw new IllegalStateException("Property " + P + "max-changes-per-run was renamed " + P
					+ "packet-size (rows applied per transaction, default 500): rename the line.");
		}
		checkRange(env, "packet-size", 1, 5000);
		if (env.containsProperty(P + "dry-run")) {
			String dryRun = trimmed(env.getProperty(P + "dry-run"));
			if (!"true".equalsIgnoreCase(dryRun) && !"false".equalsIgnoreCase(dryRun)) {
				throw new IllegalStateException("Invalid value '" + dryRun + "' for property " + P
						+ "dry-run: true (read and compare only) or false");
			}
		}
		checkRange(env, "connect-timeout-seconds", 1, Integer.MAX_VALUE);
		checkRange(env, "read-timeout-seconds", 1, Integer.MAX_VALUE);
		checkInvoices(env);
	}

	/**
	 * Invoices from the ERP, step (a): page.invoices not blank when present; the prefix without spaces; the customer field
	 * and the lines property OData names; max-per-run from 1 to 1000. Release 2.2: invoices.years and invoices.start-number
	 * are gone (the years come from the General Setup "Read ERP invoices after number"): see {@link #ignoredKeys}.
	 */
	private static void checkInvoices(PropertyResolver env) {
		String pageKey = P + "page.invoices";
		if (env.containsProperty(pageKey) && trimmed(env.getProperty(pageKey)).isEmpty()) {
			throw new IllegalStateException("Missing value for property " + pageKey
					+ ": the web service name of the invoices page (FactureFranchise when the key is absent).");
		}
		String prefixKey = P + "invoices.number-prefix";
		String prefix = trimmed(env.getProperty(prefixKey, "FVV"));
		if (prefix.isEmpty() || !prefix.matches("\\S+")) {
			throw new IllegalStateException("Invalid value '" + trimmed(env.getProperty(prefixKey)) + "' for property "
					+ prefixKey + ": the start of every invoice number, without spaces, e.g. FVV (the year follows in 2"
					+ " digits)");
		}
		for (String key : new String[] { "invoices.customer-field", "invoices.lines-expand" }) {
			if (env.containsProperty(P + key) && !trimmed(env.getProperty(P + key)).matches(ODATA_NAME)) {
				throw new IllegalStateException("Invalid value '" + trimmed(env.getProperty(P + key)) + "' for property "
						+ P + key + ": a field name of the ERP page, letters, digits and _ (e.g. Sell_to_Customer_No)");
			}
		}
		checkRange(env, "invoices.max-per-run", 1, 1000);
	}

	/** Release 2.2: the lines removed from the files; a file that still has them starts, with a WARN line each. */
	static final String[] REMOVED_KEYS = { P + "invoices.years", P + "invoices.start-number" };

	/** The removed lines this file still has (ignored). */
	public static List<String> ignoredKeys(PropertyResolver env) {
		List<String> present = new ArrayList<>();
		for (String key : REMOVED_KEYS) {
			if (env.containsProperty(key)) {
				present.add(key);
			}
		}
		return present;
	}

	/** The startup line: address, company and pages (never a password); the invoices page with the ERP supply. */
	public static String summary(PropertyResolver env) {
		boolean invoices = "ERP".equalsIgnoreCase(trimmed(env.getProperty("headoffice.supply.source")));
		return "ERP connector navpospages (read only, GET): " + trimmed(env.getProperty(P + "base-url")) + ", company "
				+ trimmed(env.getProperty(P + "company")) + ", pages " + env.getProperty(P + "page.categories", "ItemCategory") + ", "
				+ env.getProperty(P + "page.items", "PointStockPOS") + ", "
				+ env.getProperty(P + "page.barcodes", "ItemBarCodePOS")
				+ (invoices ? ", invoices " + trimmed(env.getProperty(P + "page.invoices", "FactureFranchise")) : "");
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
