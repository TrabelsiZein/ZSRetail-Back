package com.digithink.zsretail.erp.api.dto;

import java.math.BigDecimal;

public class ErpTicketLineDTO {

	private Integer lineNumber;
	private String itemExternalId;
	private String itemDescription;
	private BigDecimal quantity;
	private BigDecimal unitPrice;
	private BigDecimal discountAmount;
	private BigDecimal discountPercentage;
	private BigDecimal totalAmount;
	private String locationCode;
	/** 3.1.1 (a ticket posted whole): the line's total including VAT, before a ticket-wide reduction. */
	private BigDecimal totalAmountIncludingVat;
	/** 3.1.1 (a ticket posted whole): the code of the promotion the line got, null without one. */
	private String promotionCode;

	public Integer getLineNumber() {
		return lineNumber;
	}

	public void setLineNumber(Integer lineNumber) {
		this.lineNumber = lineNumber;
	}

	public String getItemExternalId() {
		return itemExternalId;
	}

	public void setItemExternalId(String itemExternalId) {
		this.itemExternalId = itemExternalId;
	}

	public String getItemDescription() {
		return itemDescription;
	}

	public void setItemDescription(String itemDescription) {
		this.itemDescription = itemDescription;
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

	public BigDecimal getDiscountAmount() {
		return discountAmount;
	}

	public void setDiscountAmount(BigDecimal discountAmount) {
		this.discountAmount = discountAmount;
	}

	public BigDecimal getDiscountPercentage() {
		return discountPercentage;
	}

	public void setDiscountPercentage(BigDecimal discountPercentage) {
		this.discountPercentage = discountPercentage;
	}

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public void setTotalAmount(BigDecimal totalAmount) {
		this.totalAmount = totalAmount;
	}

	public String getLocationCode() {
		return locationCode;
	}

	public void setLocationCode(String locationCode) {
		this.locationCode = locationCode;
	}


	public BigDecimal getTotalAmountIncludingVat() {
		return totalAmountIncludingVat;
	}

	public void setTotalAmountIncludingVat(BigDecimal totalAmountIncludingVat) {
		this.totalAmountIncludingVat = totalAmountIncludingVat;
	}

	public String getPromotionCode() {
		return promotionCode;
	}

	public void setPromotionCode(String promotionCode) {
		this.promotionCode = promotionCode;
	}
}
