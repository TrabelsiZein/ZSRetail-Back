package com.digithink.zsretail.connector.zen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.zen.ZenConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

import lombok.Getter;
import lombok.Setter;

/**
 * Item variants (ZEN), step 8: the settings of ZEN's connector (prefix erp.zen), a NAV whose custom API
 * (api/sat/zsretail/v1.0) gives the pages stockPoints, itemCategories and (step 12a) barCodes. Read only: the connector
 * sends GET requests only. The values are checked before they are bound
 * ({@link ZenStartupCheck}): a wrong value stops the startup with a message naming the key. Exists only with
 * erp.connector=zen.
 */
@Component
@ConditionalOnErpConnector(ZenConnectorDescriptor.NAME)
@ConfigurationProperties(prefix = ZenProperties.PREFIX)
@DependsOn(ZenStartupCheck.BEAN_NAME)
@Getter
@Setter
public class ZenProperties {

	public static final String PREFIX = "erp.zen";

	/** The NAV instance, e.g. http://host:7048/ZEN: the API path and the company follow it. */
	private String baseUrl;
	/** The path of the custom API under base-url. */
	private String apiPath = "api/sat/zsretail/v1.0";
	/**
	 * The company's name, as for Happyness. Step 10: its id in the API (companies(id)) is looked up once by this name,
	 * unless company-id is set.
	 */
	private String company;
	/** Step 10, optional: the company's id in the API (companies(id)); when set, used as it is, no lookup by name. */
	private String companyId;
	private String domain;
	private String username;
	private String password;
	private Page page = new Page();
	/** Barcodes read per call ($top). */
	private int barcodePageSize = 1000;
	/** The VAT of a row that gives none (the API has no VAT field yet), a whole number from 0 to 100. */
	private Integer defaultVat;
	private int connectTimeoutSeconds = 10;
	private int readTimeoutSeconds = 60;
	/** Rows applied per transaction (the catalogue engine's packets). */
	private int packetSize = 500;
	/** Read, compare and log the summary only: nothing handed to the import, the state table not touched. */
	private boolean dryRun = false;

	/**
	 * The pages: a name under the company of the API, or a whole address (http or https) for a page published
	 * elsewhere.
	 */
	@Getter
	@Setter
	public static class Page {
		/** One row per location (point de stock), item and variant. */
		private String stockPoints = "stockPoints";
		/** The tree of the item categories, by indentation: catégorie, famille, sous-famille, section. */
		private String categories = "itemCategories";
		/** The barcodes (step 12a: ZEN's page barCodes, read by lastModifiedDateTime). */
		private String barcodes = "barCodes";
	}

	/** base-url / api-path / companies(companyId) / page; a page written as a whole address as it is. */
	public String pageAddress(String page, String companyId) {
		String name = page == null ? "" : page.trim();
		if (name.startsWith("http://") || name.startsWith("https://")) {
			return name;
		}
		return companyAddress(companyId) + name;
	}

	/** base-url / api-path / companies(companyId) / */
	public String companyAddress(String companyId) {
		return apiAddress() + "companies(" + trimmed(companyId) + ")/";
	}

	/** Step 10: base-url / api-path / (the companies of the API are listed there). */
	public String apiAddress() {
		String base = trimmed(baseUrl);
		String path = trimmed(apiPath);
		while (path.startsWith("/")) {
			path = path.substring(1);
		}
		return (base.endsWith("/") ? base : base + "/") + (path.isEmpty() ? "" : path.endsWith("/") ? path : path + "/");
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}
}
