package com.digithink.zsretail.erp.dynamicsnav.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicsNavLocationDTO {

	@JsonProperty("Code")
	private String code;

	@JsonProperty("Name")
	private String name;

	@JsonProperty("Responsibility_Center")
	private String responsibilityCenter;

//	@JsonProperty("Address")
//	private String address;
//
//	@JsonProperty("City")
//	private String city;
//
//	@JsonProperty("CountryRegionCode")
//	private String countryRegionCode;
//
//	@JsonProperty("Blocked")
//	private Boolean blocked;
}
