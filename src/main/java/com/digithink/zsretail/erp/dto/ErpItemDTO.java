package com.digithink.zsretail.erp.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class ErpItemDTO implements ErpTimestamped {

	private String externalId;
	private String code;
	private String name;
	private String description;
	private String familyExternalId;
	private String subFamilyExternalId;
	private BigDecimal unitPrice;
	public Integer defaultVAT;
	private String itemDiscGroup;
	private Double maximumAuthorizedDiscount;
	private Boolean active;
	private OffsetDateTime lastModifiedAt;

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public String getFamilyExternalId() {
		return familyExternalId;
	}

	public void setFamilyExternalId(String familyExternalId) {
		this.familyExternalId = familyExternalId;
	}

	public String getSubFamilyExternalId() {
		return subFamilyExternalId;
	}

	public void setSubFamilyExternalId(String subFamilyExternalId) {
		this.subFamilyExternalId = subFamilyExternalId;
	}

	public BigDecimal getUnitPrice() {
		return unitPrice;
	}

	public void setUnitPrice(BigDecimal unitPrice) {
		this.unitPrice = unitPrice;
	}

	public Integer getDefaultVAT() {
		return defaultVAT;
	}

	public void setDefaultVAT(Integer defaultVAT) {
		this.defaultVAT = defaultVAT;
	}

	public String getItemDiscGroup() {
		return itemDiscGroup;
	}

	public void setItemDiscGroup(String itemDiscGroup) {
		this.itemDiscGroup = itemDiscGroup;
	}

	public Double getMaximumAuthorizedDiscount() {
		return maximumAuthorizedDiscount;
	}

	public void setMaximumAuthorizedDiscount(Double maximumAuthorizedDiscount) {
		this.maximumAuthorizedDiscount = maximumAuthorizedDiscount;
	}

	public Boolean getActive() {
		return active;
	}

	public void setActive(Boolean active) {
		this.active = active;
	}

	@Override
	public OffsetDateTime getLastModifiedAt() {
		return lastModifiedAt;
	}

	public void setLastModifiedAt(OffsetDateTime lastModifiedAt) {
		this.lastModifiedAt = lastModifiedAt;
	}
}
