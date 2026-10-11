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

	public String getCompanyUrlSegment() {
		if (company == null || company.trim().isEmpty()) {
			return "";
		}
		return String.format("Company('%s')", company);
	}
}

