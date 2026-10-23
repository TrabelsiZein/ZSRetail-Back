package com.digithink.zsretail.connector.emtop.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "erp.emtop")
@Getter
@Setter
public class DynamicsNavProperties {

	private String baseUrl;
	private String company;
	private String domain;
	private String username;
	private String password;
	/** Opening the connection to NAV: fails only when NAV cannot be reached (VPN down, server off). */
	private int connectTimeoutSeconds = 30;
	/**
	 * Silence allowed while waiting for NAV's data (not the length of the whole read): without it a call NAV never
	 * answers blocks the ERP scheduler for good. Generous for the first full read of the items.
	 */
	private int readTimeoutSeconds = 600;

	public String getCompanyUrlSegment() {
		if (company == null || company.trim().isEmpty()) {
			return "";
		}
		return String.format("Company('%s')", company);
	}
}

