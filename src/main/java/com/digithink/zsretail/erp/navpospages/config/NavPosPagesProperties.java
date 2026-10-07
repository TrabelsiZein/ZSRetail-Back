package com.digithink.zsretail.erp.navpospages.config;

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

	/** The three page names (web service names) of the ERP. */
	@Getter
	@Setter
	public static class Page {
		private String categories = "ItemCategory";
		private String items = "PointStockPOS";
		private String barcodes = "ItemBarCodePOS";
	}

	/** Company('...') path segment, empty when no company. */
	public String getCompanyUrlSegment() {
		if (company == null || company.trim().isEmpty()) {
			return "";
		}
		return "Company('" + company.trim().replace("'", "''") + "')";
	}
}
