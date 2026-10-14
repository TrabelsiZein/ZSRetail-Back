package com.digithink.zsretail.erp.api.dto;

/**
 * Item variants (ZEN), step 2: a section of the ERP, the level below the sub-family it names
 * ({@link #getSubFamilyExternalId()}). Same shape as {@link ErpItemSubFamilyDTO}.
 */
public class ErpItemSectionDTO {

	private String externalId;
	private String code;
	private String name;
	private String description;
	private Integer displayOrder;
	private Boolean active;
	private String subFamilyExternalId;

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

	public Integer getDisplayOrder() {
		return displayOrder;
	}

	public void setDisplayOrder(Integer displayOrder) {
		this.displayOrder = displayOrder;
	}

	public Boolean getActive() {
		return active;
	}

	public void setActive(Boolean active) {
		this.active = active;
	}

	public String getSubFamilyExternalId() {
		return subFamilyExternalId;
	}

	public void setSubFamilyExternalId(String subFamilyExternalId) {
		this.subFamilyExternalId = subFamilyExternalId;
	}
}
