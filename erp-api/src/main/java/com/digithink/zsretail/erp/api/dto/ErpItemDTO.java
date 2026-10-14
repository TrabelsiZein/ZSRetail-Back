package com.digithink.zsretail.erp.api.dto;

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

	// Item variants (ZEN), step 2: one ERP item per variant (code = ERP item number + "~" + variant code). Null for a
	// connector that gives no variant; the import writes them as they are.

	/** The ERP's item number of the variant (several items share it). */
	private String navItemNo;
	private String variantCode;
	private String variantDescription;
	private String sizeCode;
	private String sizeDescription;
	/** The ERP's order of the sizes (S before M before L). */
	private Integer sizeSortOrder;
	private String colorCode;
	private String colorDescription;
	private String choiceCode;
	private String choiceDescription;
	private String seasonCode;
	private String divisionCode;
	/** The code of the catégorie (the level above the family), as the ERP gives it. */
	private String categoryCode;
	/** The code of the section (the level below the sub-family), as the ERP gives it. */
	private String sectionCode;
	/** The brand (item.brand). */
	private String brand;
	/** The point de stock row's clearance flag (the row of the location read, not the item); null is false. */
	private Boolean clearance;
	/** The point de stock row's clearance price, as read; null when none. */
	private BigDecimal clearancePrice;
	/** The item's VAT rate (percent) when the ERP gives one; null: the connector's default VAT applies. */
	private Integer vatPercent;

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

	public String getNavItemNo() {
		return navItemNo;
	}

	public void setNavItemNo(String navItemNo) {
		this.navItemNo = navItemNo;
	}

	public String getVariantCode() {
		return variantCode;
	}

	public void setVariantCode(String variantCode) {
		this.variantCode = variantCode;
	}

	public String getVariantDescription() {
		return variantDescription;
	}

	public void setVariantDescription(String variantDescription) {
		this.variantDescription = variantDescription;
	}

	public String getSizeCode() {
		return sizeCode;
	}

	public void setSizeCode(String sizeCode) {
		this.sizeCode = sizeCode;
	}

	public String getSizeDescription() {
		return sizeDescription;
	}

	public void setSizeDescription(String sizeDescription) {
		this.sizeDescription = sizeDescription;
	}

	public Integer getSizeSortOrder() {
		return sizeSortOrder;
	}

	public void setSizeSortOrder(Integer sizeSortOrder) {
		this.sizeSortOrder = sizeSortOrder;
	}

	public String getColorCode() {
		return colorCode;
	}

	public void setColorCode(String colorCode) {
		this.colorCode = colorCode;
	}

	public String getColorDescription() {
		return colorDescription;
	}

	public void setColorDescription(String colorDescription) {
		this.colorDescription = colorDescription;
	}

	public String getChoiceCode() {
		return choiceCode;
	}

	public void setChoiceCode(String choiceCode) {
		this.choiceCode = choiceCode;
	}

	public String getChoiceDescription() {
		return choiceDescription;
	}

	public void setChoiceDescription(String choiceDescription) {
		this.choiceDescription = choiceDescription;
	}

	public String getSeasonCode() {
		return seasonCode;
	}

	public void setSeasonCode(String seasonCode) {
		this.seasonCode = seasonCode;
	}

	public String getDivisionCode() {
		return divisionCode;
	}

	public void setDivisionCode(String divisionCode) {
		this.divisionCode = divisionCode;
	}

	public String getCategoryCode() {
		return categoryCode;
	}

	public void setCategoryCode(String categoryCode) {
		this.categoryCode = categoryCode;
	}

	public String getSectionCode() {
		return sectionCode;
	}

	public void setSectionCode(String sectionCode) {
		this.sectionCode = sectionCode;
	}

	public String getBrand() {
		return brand;
	}

	public void setBrand(String brand) {
		this.brand = brand;
	}

	public Boolean getClearance() {
		return clearance;
	}

	public void setClearance(Boolean clearance) {
		this.clearance = clearance;
	}

	public BigDecimal getClearancePrice() {
		return clearancePrice;
	}

	public void setClearancePrice(BigDecimal clearancePrice) {
		this.clearancePrice = clearancePrice;
	}

	public Integer getVatPercent() {
		return vatPercent;
	}

	public void setVatPercent(Integer vatPercent) {
		this.vatPercent = vatPercent;
	}
}
