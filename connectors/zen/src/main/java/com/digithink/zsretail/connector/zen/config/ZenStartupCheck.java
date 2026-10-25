package com.digithink.zsretail.connector.zen.config;

import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertyResolver;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;
import com.digithink.zsretail.erp.api.ErpConnectors;

import lombok.extern.log4j.Log4j2;

/**
 * Item variants (ZEN), step 8: the startup refusals of ZEN's connector, as the other connectors: an
 * {@link IllegalStateException} naming the key, so the application does not start. Read from the environment before
 * the settings are bound ({@link ZenProperties} depends on this bean). Exists only with erp.connector=zen. One INFO
 * line when the settings are accepted: the address, the company and the pages (never a password); step 9: the
 * invoices page and its settings when headoffice.supply.source=ERP.
 */
@Component(ZenStartupCheck.BEAN_NAME)
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
@Log4j2
public class ZenStartupCheck {

	public static final String BEAN_NAME = "zenStartupCheck";

	static final String P = ZenProperties.PREFIX + ".";

	/** A field or navigation property of an OData page: it goes into the URL as it is. */
	static final String ODATA_NAME = "[A-Za-z_][A-Za-z0-9_]*";

	public ZenStartupCheck(Environment environment) {
		check(environment);
		log.info(summary(environment));
	}

	/** Throws {@link IllegalStateException} naming the key when the connector cannot start. */
	public static void check(PropertyResolver env) {
		if (!ErpConnectors.isErpCatalogueOnlyHeadOffice(env)) {
			throw new IllegalStateException("Invalid value '" + ZenConnectorDescriptor.NAME
					+ "' for property erp.connector: this connector only reads a catalogue, on a head office whose catalogue"
					+ " only comes from the ERP (the head office application, ownership.catalogue=ERP, ownership.customers"
					+ " and ownership.supply not ERP).");
		}
		for (String key : new String[] { "base-url", "username", "password" }) {
			if (trimmed(env.getProperty(P + key)).isEmpty()) {
				throw new IllegalStateException("Missing value for property " + P + key + ": required with erp.connector="
						+ ZenConnectorDescriptor.NAME + ".");
			}
		}
		// Step 10: the company by its name (its id looked up once) or by its id
		if (trimmed(env.getProperty(P + "company")).isEmpty() && trimmed(env.getProperty(P + "company-id")).isEmpty()) {
			throw new IllegalStateException("Missing value for property " + P + "company: the company's name (or its id"
					+ " in the API, " + P + "company-id), required with erp.connector=" + ZenConnectorDescriptor.NAME + ".");
		}
		String baseUrl = trimmed(env.getProperty(P + "base-url"));
		if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
			throw new IllegalStateException("Invalid value '" + baseUrl + "' for property " + P
					+ "base-url: the address of the NAV instance, http://host:port/instance");
		}
		String vat = env.getProperty(P + "default-vat");
		long vatValue = wholeNumber(vat);
		if (vatValue < 0 || vatValue > 100) {
			throw new IllegalStateException("Invalid value '" + (vat == null ? "" : vat) + "' for property " + P
					+ "default-vat: a whole number from 0 to 100 (the VAT of a row that gives none, e.g. 19)");
		}
		for (String key : new String[] { "api-path", "page.stock-points", "page.categories", "page.barcodes" }) {
			if (env.containsProperty(P + key) && trimmed(env.getProperty(P + key)).isEmpty()) {
				throw new IllegalStateException("Missing value for property " + P + key
						+ ": remove the line to use the default, or write the value.");
			}
		}
		checkRange(env, "barcode-page-size", 1, 5000);
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
	 * Step 9, the invoices: every key optional; a line written blank is refused, the lines property an OData name, the
	 * prefix without spaces, page-size from 1 to 1000. The company as for the other pages (its name or its id): the
	 * invoices API is under the same companies(id).
	 */
	private static void checkInvoices(PropertyResolver env) {
		for (String key : new String[] { "invoices.page", "invoices.api-path", "invoices.seller-name" }) {
			if (env.containsProperty(P + key) && trimmed(env.getProperty(P + key)).isEmpty()) {
				throw new IllegalStateException("Missing value for property " + P + key
						+ ": remove the line to use the default, or write the value.");
			}
		}
		String expandKey = P + "invoices.lines-expand";
		if (env.containsProperty(expandKey) && !trimmed(env.getProperty(expandKey)).matches(ODATA_NAME)) {
			throw new IllegalStateException("Invalid value '" + trimmed(env.getProperty(expandKey)) + "' for property "
					+ expandKey + ": the navigation property of the lines, letters, digits and _ (e.g."
					+ " salesInvoiceLines)");
		}
		String prefixKey = P + "invoices.number-prefix";
		String prefix = trimmed(env.getProperty(prefixKey, "FVE"));
		if (prefix.isEmpty() || !prefix.matches("\\S+")) {
			throw new IllegalStateException("Invalid value '" + trimmed(env.getProperty(prefixKey)) + "' for property "
					+ prefixKey + ": the start of every invoice number, without spaces, e.g. FVE (the year follows in 2"
					+ " digits)");
		}
		checkRange(env, "invoices.page-size", 1, 1000);
	}

	/** The startup line: address, company and pages (never a password); the invoices page with the ERP supply. */
	public static String summary(PropertyResolver env) {
		return "ERP connector " + ZenConnectorDescriptor.NAME + " (read only, GET): "
				+ trimmed(env.getProperty(P + "base-url")) + ", API " + env.getProperty(P + "api-path", "api/sat/zsretail/v1.0")
				+ ", company " + company(env) + ", pages "
				+ env.getProperty(P + "page.stock-points", "stockPoints") + ", "
				+ env.getProperty(P + "page.categories", "itemCategories") + ", "
				+ env.getProperty(P + "page.barcodes", "barCodes")
				+ (erpSupply(env) ? invoices(env) : "");
	}

	/**
	 * Step 9: ", invoices api/sat/franchise/v1.0 salesInvoices (lines salesInvoiceLines, prefix FVE, 50 per year and run,
	 * seller ZEN)".
	 */
	private static String invoices(PropertyResolver env) {
		return ", invoices " + trimmed(env.getProperty(P + "invoices.api-path", "api/sat/franchise/v1.0")) + " "
				+ trimmed(env.getProperty(P + "invoices.page", "salesInvoices"))
				+ " (lines " + trimmed(env.getProperty(P + "invoices.lines-expand", "salesInvoiceLines")) + ", prefix "
				+ trimmed(env.getProperty(P + "invoices.number-prefix", "FVE")) + ", "
				+ trimmed(env.getProperty(P + "invoices.page-size", "50")) + " per year and run, seller "
				+ trimmed(env.getProperty(P + "invoices.seller-name", "ZEN")) + ")";
	}

	/** The stores' supply documents are the ERP's invoices (headoffice.supply.source=ERP). */
	private static boolean erpSupply(PropertyResolver env) {
		return "ERP".equalsIgnoreCase(trimmed(env.getProperty("headoffice.supply.source")));
	}

	/** The company as the reads find it: "id <id>" when company-id is set (used as it is), else its name. */
	private static String company(PropertyResolver env) {
		String id = trimmed(env.getProperty(P + "company-id"));
		return id.isEmpty() ? trimmed(env.getProperty(P + "company")) : "id " + id;
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
