package com.digithink.zsretail.erp.navpospages.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

/**
 * ERP catalogue, step 5: the settings of the "POS pages" connector (prefix erp.navpospages), a Dynamics NAV / Business
 * Central whose web services are the pages ItemCategory, PointStockPOS and ItemBarCodePOS. Read only: the connector
 * sends GET requests only. The values are checked before they are bound ({@link NavPosPagesStartupCheck}): a wrong value
 * stops the startup with a message naming the key. Exists only with erp.navpospages.enabled=true.
 */
@Component
@ConditionalOnProperty(prefix = NavPosPagesProperties.PREFIX, name = "enabled", havingValue = "true")
@ConfigurationProperties(prefix = NavPosPagesProperties.PREFIX)
@DependsOn(NavPosPagesStartupCheck.BEAN_NAME)
@Getter
@Setter
public class NavPosPagesProperties {

	public static final String PREFIX = "erp.navpospages";

	private boolean enabled = false;
	/** OData V4 address, e.g. http://host:7048/BC140/ODataV4 */
	private String baseUrl;
	private String company;
	private String domain;
	private String username;
	private String password;
	/** The one location whose rows of the items page are read. */
	private String locationCode;
	private Page page = new Page();
	/** Barcodes read per call ($top). */
	private int barcodePageSize = 1000;
	/** The VAT given to every item (the pages carry none), a whole number from 0 to 100. */
	private Integer defaultVat;
	/** true: Unit_Price includes the VAT and is brought back before VAT; false: Unit_Price is before VAT. */
	private Boolean priceIncludesVat;
	private int connectTimeoutSeconds = 10;
	private int readTimeoutSeconds = 60;
	/** Step 6: rows handed to the import per fetch at most (the rest at the next runs). */
	private int maxChangesPerRun = 1000;
	/**
	 * Step 6: an item run deactivates nothing when the items missing from the ERP read are more than this share (in %) of
	 * the active items that came from the ERP.
	 */
	private int deactivateGuardPercent = 10;
	/** Step 6: read, compare and log the summary only: nothing handed to the import, the state table not touched. */
	private boolean dryRun = false;

	/** Invoices from the ERP, step (a): the franchise invoices read by number. */
	private Invoices invoices = new Invoices();

	/** The page names (web service names) of the ERP. */
	@Getter
	@Setter
	public static class Page {
		private String categories = "ItemCategory";
		private String items = "PointStockPOS";
		private String barcodes = "ItemBarCodePOS";
		/** Invoices from the ERP: the posted franchise invoices, their lines expanded. */
		private String invoices = "FactureFranchise";
	}

	/**
	 * Invoices from the ERP, step (a): how the invoices page is read. The numbers carry the year (FVV26...): one read per
	 * configured year, after the highest number the head office has of that year. No year: the invoices are not read.
	 */
	@Getter
	@Setter
	public static class Invoices {
		/** The navigation property of the lines on the invoices page ($expand). */
		private String linesExpand = "FactureFranchiseSalesInvLines";
		/** The header field naming the customer (its number; Sell_to_Customer_Name on test data without it). */
		private String customerField = "Sell_to_Customer_No";
		/** The start of every number; the year follows it in 2 digits (FVV + 26). */
		private String numberPrefix = "FVV";
		/** The years read at each run, e.g. 2025,2026 (the current one and the previous one). */
		private List<Integer> years = new ArrayList<>();
		/** The last invoice before the go-live; applies only to its year while the head office has none of that year. */
		private String startNumber;
		/** Invoices read per year and run at most. */
		private int maxPerRun = 50;
		/**
		 * Step (c): the seller named on the stores' purchase invoices (read by the head office, HoErpInvoiceService); the
		 * vendor HEAD_OFFICE of a store is created with it.
		 */
		private String sellerName = "Head office";

		/** numberPrefix + the 2 digits of the year: FVV26 for 2026. */
		public String yearPrefix(int year) {
			return numberPrefix.trim() + String.format("%02d", year % 100);
		}
	}

	/** Company('...') path segment, empty when no company. */
	public String getCompanyUrlSegment() {
		if (company == null || company.trim().isEmpty()) {
			return "";
		}
		return "Company('" + company.trim().replace("'", "''") + "')";
	}
}
