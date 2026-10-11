package com.digithink.zsretail.erp.dynamicsnav.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class DynamicsNavStockKeepingUnitDTO {

	@JsonProperty("Item_No")
	public String itemNo;
	@JsonProperty("Location_Code")
	public String locationCode;
	@JsonProperty("Unit_Price")
	public BigDecimal unitPrice;
	@JsonProperty("Default_VAT")
	public int defaultVAT;
	@JsonProperty("Item_Category_Code")
	public String itemCategoryCode;
	@JsonProperty("Product_Group_Code")
	public String productGroupCode;
	@JsonProperty("Sub_Family")
	public String subFamily;
	@JsonProperty("Discount")
	public int discount;
	@JsonProperty("Modified_At")
	public OffsetDateTime modifiedAt;
	@JsonProperty("Description")
	public String description;
	@JsonProperty("AuxiliaryIndex1")
	public String auxiliaryIndex1;
	@JsonProperty("Item_Disc_Group")
	public String itemDiscGroup;
	@JsonProperty("Remise_MAX")
	public Double maximumAuthorizedDiscount;
}
