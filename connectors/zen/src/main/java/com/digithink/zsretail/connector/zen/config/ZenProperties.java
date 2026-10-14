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
 * (api/sat/zsretail/v1.0) gives the pages stockPoints and itemCategories, and the barcodes page of Happyness with the
 * variant code. Read only: the connector sends GET requests only. The values are checked before they are bound
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
	/** The company's id in the API (companies(id)), e.g. 89c8b192-3f2e-f111-9f24-7ced8db3f120. */
	private String company;
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
		/** The barcodes: Happyness's barcode page and Variant_Code (the name to confirm with ZEN). */
		private String barcodes = "ItemBarCodePOS";
	}

	/** base-url / api-path / companies(company) / page; a page written as a whole address as it is. */
	public String pageAddress(String page) {
		String name = page == null ? "" : page.trim();
		if (name.startsWith("http://") || name.startsWith("https://")) {
			return name;
		}
		return companyAddress() + name;
	}

	/** base-url / api-path / companies(company) / */
	public String companyAddress() {
		String base = trimmed(baseUrl);
		String path = trimmed(apiPath);
		while (path.startsWith("/")) {
			path = path.substring(1);
		}
		return (base.endsWith("/") ? base : base + "/") + (path.isEmpty() ? "" : path.endsWith("/") ? path : path + "/")
				+ "companies(" + trimmed(company) + ")/";
	}

	private static String trimmed(String value) {
		return value == null ? "" : value.trim();
	}
}
