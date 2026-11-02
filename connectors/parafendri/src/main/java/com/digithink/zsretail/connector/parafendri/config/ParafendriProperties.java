package com.digithink.zsretail.connector.parafendri.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.connector.parafendri.ParafendriConnectorDescriptor;
import com.digithink.zsretail.erp.api.ConditionalOnErpConnector;

import lombok.Getter;
import lombok.Setter;

/**
 * ParaFendri, step 0: the settings of the connector (prefix erp.parafendri), a Business Central 28 on-prem whose custom
 * API api/sat/zsretail/v1.0 is under companies(company-id). The API path and the page names are the connector's own,
 * not keys. The values are checked before they are bound ({@link ParafendriStartupCheck}): a wrong value stops the
 * startup with a message naming the key. Exists only with erp.connector=parafendri.
 */
@Component
@ConditionalOnErpConnector(ParafendriConnectorDescriptor.NAME)
@ConfigurationProperties(prefix = ParafendriProperties.PREFIX)
@DependsOn(ParafendriStartupCheck.BEAN_NAME)
@Getter
@Setter
public class ParafendriProperties {

	public static final String PREFIX = "erp.parafendri";

	/** The custom API of the Business Central instance, after base-url. */
	public static final String API_PATH = "api/sat/zsretail/v1.0";

	/** The Business Central instance, e.g. http://host:7048/BC280: the API path and the company follow it. */
	private String baseUrl;
	/** The company's id in the API (companies(id)), a GUID. */
	private String companyId;
	private String domain;
	private String username;
	private String password;
	/** Opening the connection: fails only when Business Central cannot be reached (VPN down, server off). */
	private int connectTimeoutSeconds = 30;
	/** Silence allowed while waiting for the data (not the length of the whole read), as EMTOP's. */
	private int readTimeoutSeconds = 600;

	/** base-url / api-path / (the companies of the API are listed there). */
	public String apiAddress() {
		String base = baseUrl == null ? "" : baseUrl.trim();
		return (base.endsWith("/") ? base : base + "/") + API_PATH + "/";
	}

	/** base-url / api-path / companies(companyId) / : the pages of the company follow it. */
	public String companyAddress() {
		return apiAddress() + "companies(" + (companyId == null ? "" : companyId.trim()) + ")/";
	}
}
