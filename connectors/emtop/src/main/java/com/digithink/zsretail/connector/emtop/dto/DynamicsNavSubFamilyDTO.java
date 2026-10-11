package com.digithink.zsretail.connector.emtop.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicsNavSubFamilyDTO {

	@JsonProperty("code_sous_famille")
	private String code;

	@JsonProperty("description_sous_famille")
	private String description;

	@JsonProperty("code_famille")
	private String familyCode;

	@JsonProperty("AuxiliaryIndex1")
	private String auxiliaryIndex1;
}

