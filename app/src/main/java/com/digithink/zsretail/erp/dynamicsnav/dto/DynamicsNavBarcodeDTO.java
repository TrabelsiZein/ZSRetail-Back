package com.digithink.zsretail.erp.dynamicsnav.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicsNavBarcodeDTO {

	@JsonProperty("Item_No")
	public String itemNo;
	@JsonProperty("Cross_Reference_No")
	public String crossReferenceNo;
	@JsonProperty("Modified_At")
	public OffsetDateTime modifiedAt;
	@JsonProperty("AuxiliaryIndex1")
	public String auxiliaryIndex1;
	@JsonProperty("AuxiliaryIndex2")
	public String auxiliaryIndex2;
	@JsonProperty("AuxiliaryIndex3")
	public String auxiliaryIndex3;
	@JsonProperty("AuxiliaryIndex4")
	public String auxiliaryIndex4;
}
