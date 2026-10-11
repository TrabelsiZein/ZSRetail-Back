package com.digithink.zsretail.erp.dynamicsnav.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class DynamicsNavSalesOrderLineDTO {

	@JsonProperty("Document_Type")
	private String documentType = "Order";

	@JsonProperty("Document_No")
	private String documentNo;

	@JsonProperty("Line_No")
	private Integer lineNo;

	// Type is read-only in NAV, so it should not be serialized in requests
	// @JsonIgnore on getter prevents serialization
	@JsonProperty("Type")
	private String type = "Item";

	@JsonProperty("No")
	private String no;

	@JsonProperty("Quantity")
	private Double quantity;

	@JsonProperty("Unit_Price")
	private Double unitPrice;

	@JsonProperty("Line_Discount_Percent")
	private Double lineDiscountPercent;

	@JsonProperty("Location_Code")
	private String locationCode;

}
