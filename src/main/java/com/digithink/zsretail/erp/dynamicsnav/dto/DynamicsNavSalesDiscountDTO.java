package com.digithink.zsretail.erp.dynamicsnav.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicsNavSalesDiscountDTO {

	@JsonProperty("Type")
	private String type;

	@JsonProperty("Code")
	private String code;

	@JsonProperty("Sales_Type")
	private String salesType;

	@JsonProperty("Sales_Code")
	private String salesCode;

	@JsonProperty("Responsibility_center_type")
	private String responsibilityCenterType;

	@JsonProperty("Responsibility_Center")
	private String responsibilityCenter;

	@JsonProperty("Starting_Date")
	private String startingDate;

	@JsonProperty("Ending_Date")
	private String endingDate;

	@JsonProperty("Line_Discount")
	private Double lineDiscount;

	@JsonProperty("Modified_At")
	private OffsetDateTime modifiedAt;

	@JsonProperty("AuxiliaryIndex1")
	private String auxiliaryIndex1;

	@JsonProperty("AuxiliaryIndex2")
	private String auxiliaryIndex2;

	@JsonProperty("AuxiliaryIndex3")
	private String auxiliaryIndex3;

	@JsonProperty("AuxiliaryIndex4")
	private Integer auxiliaryIndex4;
}

