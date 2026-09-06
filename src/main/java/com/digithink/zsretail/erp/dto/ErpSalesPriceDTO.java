package com.digithink.zsretail.erp.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class ErpSalesPriceDTO implements ErpTimestamped {

	private String externalId;
	private String itemNo;
	private String salesType;
	private String salesCode;
	private BigDecimal unitPrice;
	private Boolean priceIncludesVat;
	private String responsibilityCenter;
	private String responsibilityCenterType;
	private String startingDate;
	private String endingDate;
	private String currencyCode;
	private String variantCode;
	private String unitOfMeasureCode;
	private Double minimumQuantity;
	private OffsetDateTime lastModifiedAt;

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getItemNo() {
		return itemNo;
	}

	public void setItemNo(String itemNo) {
		this.itemNo = itemNo;
	}

	public String getSalesType() {
		return salesType;
	}

	public void setSalesType(String salesType) {
		this.salesType = salesType;
	}

	public String getSalesCode() {
		return salesCode;
	}

	public void setSalesCode(String salesCode) {
		this.salesCode = salesCode;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	public void setUnitPrice(BigDecimal unitPrice) {
		this.unitPrice = unitPrice;
	}

	public Boolean getPriceIncludesVat() {
		return priceIncludesVat;
	}

	public void setPriceIncludesVat(Boolean priceIncludesVat) {
		this.priceIncludesVat = priceIncludesVat;
	}

	public String getResponsibilityCenter() {
		return responsibilityCenter;
	}

	public void setResponsibilityCenter(String responsibilityCenter) {
		this.responsibilityCenter = responsibilityCenter;
	}

	public String getResponsibilityCenterType() {
		return responsibilityCenterType;
	}

	public void setResponsibilityCenterType(String responsibilityCenterType) {
		this.responsibilityCenterType = responsibilityCenterType;
	}

	public String getEndingDate() {
		return endingDate;
	}

	public void setEndingDate(String endingDate) {
		this.endingDate = endingDate;
	}

	public String getStartingDate() {
		return startingDate;
	}

	public void setStartingDate(String startingDate) {
		this.startingDate = startingDate;
	}

	public String getCurrencyCode() {
		return currencyCode;
	}

	public void setCurrencyCode(String currencyCode) {
		this.currencyCode = currencyCode;
	}

	public String getVariantCode() {
		return variantCode;
	}

	public void setVariantCode(String variantCode) {
		this.variantCode = variantCode;
	}

	public String getUnitOfMeasureCode() {
		return unitOfMeasureCode;
	}

	public void setUnitOfMeasureCode(String unitOfMeasureCode) {
		this.unitOfMeasureCode = unitOfMeasureCode;
	}

	public Double getMinimumQuantity() {
		return minimumQuantity;
	}

	public void setMinimumQuantity(Double minimumQuantity) {
		this.minimumQuantity = minimumQuantity;
	}

	public OffsetDateTime getLastModifiedAt() {
		return lastModifiedAt;
	}

	public void setLastModifiedAt(OffsetDateTime lastModifiedAt) {
		this.lastModifiedAt = lastModifiedAt;
	}
}

