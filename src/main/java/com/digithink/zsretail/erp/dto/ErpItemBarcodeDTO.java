package com.digithink.zsretail.erp.dto;

import java.time.OffsetDateTime;

public class ErpItemBarcodeDTO implements ErpTimestamped {

	private String externalId;
	private String itemExternalId;
	private String barcode;
	private Boolean primaryBarcode;
	private OffsetDateTime lastModifiedAt;

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getItemExternalId() {
		return itemExternalId;
	}

	public void setItemExternalId(String itemExternalId) {
		this.itemExternalId = itemExternalId;
	}

	public String getBarcode() {
		return barcode;
	}

	public void setBarcode(String barcode) {
		this.barcode = barcode;
	}

	public Boolean getPrimaryBarcode() {
		return primaryBarcode;
	}

	public void setPrimaryBarcode(Boolean primaryBarcode) {
		this.primaryBarcode = primaryBarcode;
	}

	@Override
	public OffsetDateTime getLastModifiedAt() {
		return lastModifiedAt;
	}

	public void setLastModifiedAt(OffsetDateTime lastModifiedAt) {
		this.lastModifiedAt = lastModifiedAt;
	}
}
