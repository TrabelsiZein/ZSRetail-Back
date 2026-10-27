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
 * (api/sat/zsretail/v1.0) gives the pages stockPoints, itemCategories and (step 12a) barCodes; step 9: the supply
 * invoices on ZEN's second custom API, api/sat/franchise/v1.0, page salesInvoices ({@link Invoices}). Read only: the
 * connector sends GET requests only. The values are checked before they are bound
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
	 * The company's name, as for Happyness (ZEN's is ESP). Step 10: its id in the API (companies(id)) is looked up once by
	 * this name, unless company-id is set. Step 9: the same id addresses the invoices API.
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
	/** The VAT of a row without vatPercent, a whole number from 0 to 100. */
	private Integer defaultVat;
	private int connectTimeoutSeconds = 10;
	private int readTimeoutSeconds = 60;
	/** Rows applied per transaction (the catalogue engine's packets). */
	private int packetSize = 500;
	/** Read, compare and log the summary only: nothing handed to the import, the state table not touched. */
	private boolean dryRun = false;
	/** Step 9: the supply invoices, read on ZEN's franchise API (not the zsretail one). */
	private Invoices invoices = new Invoices();
	/**
	 * 3.1.1 (store orders): the live stock of the variants of an item for a store's ERP customer, ZEN's page infoArticles
	 * (key erp.zen.variantstock.path).
	 */
	private Path variantstock = new Path("infoArticles");
	/**
	 * 3.1.1 (store orders): the sales orders of the stores, looked up by externalDocumentNo then posted (key
	 * erp.zen.salesorders.path): the only address this connector sends a POST to.
	 */
	private Path salesorders = new Path("salesOrders");

	/**
	 * 3.1.1: one page of the store orders: a name under companies(id) of the custom API (api-path), as the other pages,
	 * or a whole address (http or https), e.g. when ZEN publishes it elsewhere: a change of address is a change of key.
	 */
	@Getter
	@Setter
	public static class Path {
		private String path;

		public Path() {
		}

		public Path(String path) {
			this.path = path;
		}
	}

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

	/**
	 * Step 9: how ZEN's posted sales invoices are read, as Happyness's (one read per year prefix, after the highest number
	 * the head office has of that year): the page salesInvoices of ZEN's franchise API, under companies(id) (the id of
	 * the zsretail API: one company), every key optional. Where the reading starts is the General Setup "Read ERP
	 * invoices after number", never a key here.
	 */
	@Getter
	@Setter
	public static class Invoices {
		/** The posted sales invoices, a name under companies(id) of the invoices API, or a whole address. */
		private String page = "salesInvoices";
		/** The navigation property of the lines on the invoices page ($expand). */
		private String linesExpand = "salesInvoiceLines";
		/** The path of ZEN's franchise API under base-url. */
		private String apiPath = "api/sat/franchise/v1.0";
		/** The start of every number; the year follows it in 2 digits (FVE + 26). */
		private String numberPrefix = "FVE";
		/** Invoices read per year and run at most (the cancelled ones not counted). */
		private int pageSize = 50;
		/** The seller named on the stores' purchase invoices (the vendor HEAD_OFFICE of a store is created with it). */
		private String sellerName = "ZEN";

		/** numberPrefix + the 2 digits of the year: FVE26 for 2026. */
		public String yearPrefix(int year) {
			return numberPrefix.trim() + String.format("%02d", year % 100);
		}

		/** The year of an invoice number (FVE26... gives 2026), or null when it does not start like one. */
		public Integer yearOf(String number) {
			String prefix = numberPrefix.trim();
			String text = number == null ? "" : number.trim();
			if (!text.startsWith(prefix) || text.length() < prefix.length() + 2) {
				return null;
			}
			String yy = text.substring(prefix.length(), prefix.length() + 2);
			return yy.chars().allMatch(Character::isDigit) ? 2000 + Integer.parseInt(yy) : null;
		}

		/** An invoice number of the ERP: the prefix, the year in 2 digits, then digits (FVE2600001). */
		public boolean looksLikeNumber(String number) {
			String text = number == null ? "" : number.trim();
			return text.matches(java.util.regex.Pattern.quote(numberPrefix.trim()) + "[0-9]{2}[0-9]+");
		}
	}

	/**
	 * Step 9: base-url / invoices.api-path / companies(companyId) / the invoices page; a page written as a whole address
	 * as it is. The company's id is the zsretail API's (one company, one id).
	 */
	public String invoicesPageAddress(String companyId) {
		String name = trimmed(invoices.getPage());
		if (name.startsWith("http://") || name.startsWith("https://")) {
			return name;
		}
		return apiAddress(invoices.getApiPath()) + "companies(" + trimmed(companyId) + ")/" + name;
	}

	/** Step 9: the invoices page is a whole address (no company id needed). */
	public boolean invoicesPageIsAddress() {
		String name = trimmed(invoices.getPage());
		return name.startsWith("http://") || name.startsWith("https://");
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
		return apiAddress(apiPath);
	}

	/** base-url / the given API path / */
	private String apiAddress(String apiPath) {
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
