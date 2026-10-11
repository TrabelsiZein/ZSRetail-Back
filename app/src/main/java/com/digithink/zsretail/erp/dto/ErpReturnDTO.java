package com.digithink.zsretail.erp.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ErpReturnDTO {

	private String externalId;
	private String returnNumber;
	private LocalDateTime returnDate;
	private String customerExternalId;
	private String locationExternalId;
	private BigDecimal totalReturnAmount;
	private String currency;
	private List<ErpReturnLineDTO> lines = new ArrayList<>();

	// Additional fields for ERP export
	private String responsibilityCenter;
	private String cashierSessionId;
	private String originalSalesNumber; // Reference to original sales header
	private Double discountPercentage;

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getReturnNumber() {
		return returnNumber;
	}

	public void setReturnNumber(String returnNumber) {
		this.returnNumber = returnNumber;
	}

	public LocalDateTime getReturnDate() {
		return returnDate;
	}

	public void setReturnDate(LocalDateTime returnDate) {
		this.returnDate = returnDate;
	}

	public String getCustomerExternalId() {
		return customerExternalId;
	}

	public void setCustomerExternalId(String customerExternalId) {
		this.customerExternalId = customerExternalId;
	}

	public String getLocationExternalId() {
		return locationExternalId;
	}

	public void setLocationExternalId(String locationExternalId) {
		this.locationExternalId = locationExternalId;
	}

	public BigDecimal getTotalReturnAmount() {
		return totalReturnAmount;
	}

	public void setTotalReturnAmount(BigDecimal totalReturnAmount) {
		this.totalReturnAmount = totalReturnAmount;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public List<ErpReturnLineDTO> getLines() {
		return lines;
	}

	public void setLines(List<ErpReturnLineDTO> lines) {
		this.lines = lines != null ? lines : new ArrayList<>();
	}

	public String getResponsibilityCenter() {
		return responsibilityCenter;
	}

	public void setResponsibilityCenter(String responsibilityCenter) {
		this.responsibilityCenter = responsibilityCenter;
	}

	public String getCashierSessionId() {
		return cashierSessionId;
	}

	public void setCashierSessionId(String cashierSessionId) {
		this.cashierSessionId = cashierSessionId;
	}

	public String getOriginalSalesNumber() {
		return originalSalesNumber;
	}

	public void setOriginalSalesNumber(String originalSalesNumber) {
		this.originalSalesNumber = originalSalesNumber;
	}

	public Double getDiscountPercentage() {
		return discountPercentage;
	}

	public void setDiscountPercentage(Double discountPercentage) {
		this.discountPercentage = discountPercentage;
	}
}
