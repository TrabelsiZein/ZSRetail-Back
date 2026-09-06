package com.digithink.zsretail.erp.dynamicsnav.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicsNavSalesPriceDTO {

	@JsonProperty("Item_No")
	private String itemNo;

	@JsonProperty("Sales_Type")
	private String salesType;

	@JsonProperty("Sales_Code")
	private String salesCode;

	@JsonProperty("Unit_Price")
	private Double unitPrice;

	@JsonProperty("Price_Includes_VAT")
	private Boolean priceIncludesVat;

	@JsonProperty("Responsibility_Center")
	private String responsibilityCenter;

	@JsonProperty("Responsibility_center_type")
	private String responsibilityCenterType;

	@JsonProperty("Starting_Date")
	private String startingDate;

	@JsonProperty("Ending_Date")
	private String endingDate;

	@JsonProperty("Currency_Code")
	private String currencyCode;

	@JsonProperty("Variant_Code")
	private String variantCode;

	@JsonProperty("Unit_of_Measure_Code")
	private String unitOfMeasureCode;

	@JsonProperty("Minimum_Quantity")
	private Double minimumQuantity;

	@JsonProperty("Modified_At")
	private OffsetDateTime modifiedAt;
}

