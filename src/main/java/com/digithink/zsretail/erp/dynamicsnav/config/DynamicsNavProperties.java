package com.digithink.zsretail.erp.dynamicsnav.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Getter;
import lombok.Setter;

@Component
@ConfigurationProperties(prefix = "erp.dynamicsnav")
@Getter
@Setter
public class DynamicsNavProperties {

	private String baseUrl;
	private String company;
	private String domain;
	private String username;
	private String password;
	private boolean enabled = false;

	public String getCompanyUrlSegment() {
		if (company == null || company.trim().isEmpty()) {
			return "";
		}
		return String.format("Company('%s')", company);
	}
}

