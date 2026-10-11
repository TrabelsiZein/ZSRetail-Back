package com.digithink.zsretail.erp.dynamicsnav.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

/**
 * DTO for Business Central Log API entries (deletion records).
 * Contains all key fields for Sales Price (9) and Sales Discount (10) so that
 * external IDs can be built and matching POS records deleted.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicsNavLogEntryDTO {

	@JsonProperty("Source_Table")
	private String sourceTable;

	@JsonProperty("Item_No")
	private String itemNo;

	@JsonProperty("Location_Code")
	private String locationCode;

	@JsonProperty("Variant_Code")
	private String variantCode;

	@JsonProperty("Sales_Type")
	private String salesType;

	@JsonProperty("Sales_Code")
	private String salesCode;

	@JsonProperty("Starting_Date")
	private String startingDate;

	@JsonProperty("Ending_Date")
	private String endingDate;

	@JsonProperty("Responsibility_Center")
	private String responsibilityCenter;

	@JsonProperty("Type")
	private String type;

	@JsonProperty("Code")
	private String code;

	@JsonProperty("Currency_Code")
	private String currencyCode;

	@JsonProperty("Unit_of_Measure_Code")
	private String unitOfMeasureCode;

	@JsonProperty("Minimum_Quantity")
	private Double minimumQuantity;

	@JsonProperty("AuxiliaryIndex1")
	private String auxiliaryIndex1;

	@JsonProperty("AuxiliaryIndex2")
	private String auxiliaryIndex2;

	@JsonProperty("AuxiliaryIndex3")
	private String auxiliaryIndex3;

	@JsonProperty("AuxiliaryIndex4")
	private Integer auxiliaryIndex4;

	@JsonProperty("Modified_At")
	private OffsetDateTime modifiedAt;

	@JsonProperty("Deleted_By")
	private String deletedBy;
}
