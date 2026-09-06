package com.digithink.zsretail.erp.dto;

import java.math.BigDecimal;

public class ErpReturnLineDTO {

	private String itemExternalId;
	private BigDecimal quantity;
	private BigDecimal unitPrice;
	private BigDecimal unitPriceIncludingVat;
	private BigDecimal lineTotal;
	private BigDecimal lineTotalIncludingVat;
	private BigDecimal discountPercentage; // Discount percentage from original sales line
	private String originalSalesLineNumber; // Reference to original sales line
	private String locationCode;

	public String getItemExternalId() {
		return itemExternalId;
	}

	public void setItemExternalId(String itemExternalId) {
		this.itemExternalId = itemExternalId;
	}

	public BigDecimal getQuantity() {
		return quantity;
	}

	public void setQuantity(BigDecimal quantity) {
		this.quantity = quantity;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	public void setUnitPrice(BigDecimal unitPrice) {
		this.unitPrice = unitPrice;
	}

	public BigDecimal getUnitPriceIncludingVat() {
		return unitPriceIncludingVat;
	}

	public void setUnitPriceIncludingVat(BigDecimal unitPriceIncludingVat) {
		this.unitPriceIncludingVat = unitPriceIncludingVat;
	}

	public BigDecimal getLineTotal() {
		return lineTotal;
	}

	public void setLineTotal(BigDecimal lineTotal) {
		this.lineTotal = lineTotal;
	}

	public BigDecimal getLineTotalIncludingVat() {
		return lineTotalIncludingVat;
	}

	public void setLineTotalIncludingVat(BigDecimal lineTotalIncludingVat) {
		this.lineTotalIncludingVat = lineTotalIncludingVat;
	}

	public String getOriginalSalesLineNumber() {
		return originalSalesLineNumber;
	}

	public void setOriginalSalesLineNumber(String originalSalesLineNumber) {
		this.originalSalesLineNumber = originalSalesLineNumber;
	}

	public BigDecimal getDiscountPercentage() {
		return discountPercentage;
	}

	public void setDiscountPercentage(BigDecimal discountPercentage) {
		this.discountPercentage = discountPercentage;
	}

	public String getLocationCode() {
		return locationCode;
	}

	public void setLocationCode(String locationCode) {
		this.locationCode = locationCode;
	}

}
