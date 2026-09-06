package com.digithink.zsretail.erp.dynamicsnav.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicsNavFamilyDTO {

	@JsonProperty("Code")
	private String code;

	@JsonProperty("Description")
	private String description;

	@JsonProperty("AuxiliaryIndex1")
	private String auxiliaryIndex1;
}

