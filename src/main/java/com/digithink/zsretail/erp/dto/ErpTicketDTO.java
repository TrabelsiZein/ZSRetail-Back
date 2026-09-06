package com.digithink.zsretail.erp.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class ErpTicketDTO {

	private String externalId;
	private String ticketNumber;
	private LocalDateTime saleDate;
	private String customerExternalId;
	private String locationExternalId;
	private BigDecimal totalAmount;
	private String currency;
	private List<ErpTicketLineDTO> lines = new ArrayList<>();
	private List<ErpTicketPaymentDTO> payments = new ArrayList<>();
	
	// Additional fields for ERP export
	private String responsibilityCenter;
	private String cashierSessionId;
	private Double discountPercentage;
	private Boolean posInvoice;
	private String fiscalRegistration;
	private String invoiceCustomerName;

	public String getExternalId() {
		return externalId;
	}

	public void setExternalId(String externalId) {
		this.externalId = externalId;
	}

	public String getTicketNumber() {
		return ticketNumber;
	}

	public void setTicketNumber(String ticketNumber) {
		this.ticketNumber = ticketNumber;
	}

	public LocalDateTime getSaleDate() {
		return saleDate;
	}

	public void setSaleDate(LocalDateTime saleDate) {
		this.saleDate = saleDate;
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

	public BigDecimal getTotalAmount() {
		return totalAmount;
	}

	public void setTotalAmount(BigDecimal totalAmount) {
		this.totalAmount = totalAmount;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}

	public List<ErpTicketLineDTO> getLines() {
		return lines;
	}

	public void setLines(List<ErpTicketLineDTO> lines) {
		this.lines = lines != null ? lines : new ArrayList<>();
	}

	public List<ErpTicketPaymentDTO> getPayments() {
		return payments;
	}

	public void setPayments(List<ErpTicketPaymentDTO> payments) {
		this.payments = payments != null ? payments : new ArrayList<>();
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

	public Double getDiscountPercentage() {
		return discountPercentage;
	}

	public void setDiscountPercentage(Double discountPercentage) {
		this.discountPercentage = discountPercentage;
	}

	public Boolean getPosInvoice() {
		return posInvoice;
	}

	public void setPosInvoice(Boolean posInvoice) {
		this.posInvoice = posInvoice;
	}

	public String getFiscalRegistration() {
		return fiscalRegistration;
	}

	public void setFiscalRegistration(String fiscalRegistration) {
		this.fiscalRegistration = fiscalRegistration;
	}

	public String getInvoiceCustomerName() {
		return invoiceCustomerName;
	}

	public void setInvoiceCustomerName(String invoiceCustomerName) {
		this.invoiceCustomerName = invoiceCustomerName;
	}
}

