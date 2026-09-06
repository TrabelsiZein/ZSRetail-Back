package com.digithink.zsretail.erp.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class ErpSalesDiscountDTO implements ErpTimestamped {

	private String externalId;
	private String type;
	private String code;
	private String salesType;
	private String salesCode;
	private String responsibilityCenterType;
	private String responsibilityCenter;
	private String startingDate;
	private String endingDate;
	private BigDecimal lineDiscount;
	private OffsetDateTime lastModifiedAt;
	private String auxiliaryIndex1;
	private String auxiliaryIndex2;
	private String auxiliaryIndex3;
	private Integer auxiliaryIndex4;

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
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

	public String getResponsibilityCenterType() {
		return responsibilityCenterType;
	}

	public void setResponsibilityCenterType(String responsibilityCenterType) {
		this.responsibilityCenterType = responsibilityCenterType;
	}

	public String getResponsibilityCenter() {
		return responsibilityCenter;
	}

	public void setResponsibilityCenter(String responsibilityCenter) {
		this.responsibilityCenter = responsibilityCenter;
	}

	public String getStartingDate() {
		return startingDate;
	}

	public void setStartingDate(String startingDate) {
		this.startingDate = startingDate;
	}

	public String getEndingDate() {
		return endingDate;
	}

	public void setEndingDate(String endingDate) {
		this.endingDate = endingDate;
	}

	public BigDecimal getLineDiscount() {
		return lineDiscount;
	}

	public void setLineDiscount(BigDecimal lineDiscount) {
		this.lineDiscount = lineDiscount;
	}

	public OffsetDateTime getLastModifiedAt() {
		return lastModifiedAt;
	}

	public void setLastModifiedAt(OffsetDateTime lastModifiedAt) {
		this.lastModifiedAt = lastModifiedAt;
	}

	public String getAuxiliaryIndex1() {
		return auxiliaryIndex1;
	}

	public void setAuxiliaryIndex1(String auxiliaryIndex1) {
		this.auxiliaryIndex1 = auxiliaryIndex1;
	}

	public String getAuxiliaryIndex2() {
		return auxiliaryIndex2;
	}

	public void setAuxiliaryIndex2(String auxiliaryIndex2) {
		this.auxiliaryIndex2 = auxiliaryIndex2;
	}

	public String getAuxiliaryIndex3() {
		return auxiliaryIndex3;
	}

	public void setAuxiliaryIndex3(String auxiliaryIndex3) {
		this.auxiliaryIndex3 = auxiliaryIndex3;
	}

	public Integer getAuxiliaryIndex4() {
		return auxiliaryIndex4;
	}

	public void setAuxiliaryIndex4(Integer auxiliaryIndex4) {
		this.auxiliaryIndex4 = auxiliaryIndex4;
	}
}

