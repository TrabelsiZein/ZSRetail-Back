package com.digithink.zsretail.erp.navpospages.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A row of the items page (PointStockPOS): one item (and variant) of one location. */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class NavPosStockRow {

	@JsonProperty("Item_No")
	private String itemNo;

	@JsonProperty("Variant_Code")
	private String variantCode;

	@JsonProperty("Description")
	private String description;

	@JsonProperty("Unit_Price")
	private BigDecimal unitPrice;

	@JsonProperty("Family")
	private String family;

	@JsonProperty("Subfamily")
	private String subfamily;

	public NavPosStockRow(String itemNo, String variantCode, String description, BigDecimal unitPrice, String family,
			String subfamily) {
		this.itemNo = itemNo;
		this.variantCode = variantCode;
		this.description = description;
		this.unitPrice = unitPrice;
		this.family = family;
		this.subfamily = subfamily;
	}
}
