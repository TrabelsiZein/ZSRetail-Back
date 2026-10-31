package com.digithink.zsretail.connector.parafendri.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Step 3: a row of the page stockPoints of the company, one item at one point de vente (locationCode). The price
 * unitPrice is before VAT, unrounded (18.90756), vatPercent the VAT (19). Read, not used: itemCategoryCode (the
 * sous-famille again), customerNo (step 6).
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ParafendriStockPointRow {

	private String locationCode;
	private String itemNo;
	private String categoryCode;
	private String familyCode;
	private String subFamilyCode;
	private String sectionCode;
	private String itemCategoryCode;
	private String stockPointDescription;
	private String itemDescription;
	private String baseUnitOfMeasure;
	private BigDecimal unitPrice;
	private BigDecimal vatPercent;
	private Boolean itemBlocked;
	private Boolean itemSalesBlocked;
	private Boolean stockPointBlocked;
	private String customerNo;
	private String lastModifiedDateTime;
}
