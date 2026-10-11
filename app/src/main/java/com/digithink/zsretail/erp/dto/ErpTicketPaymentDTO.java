package com.digithink.zsretail.erp.dto;

import java.math.BigDecimal;

public class ErpTicketPaymentDTO {

	private String paymentMethodExternalId;
	private String paymentReference;
	private BigDecimal amount;
	private String currency;

	public String getPaymentMethodExternalId() {
		return paymentMethodExternalId;
	}

	public void setPaymentMethodExternalId(String paymentMethodExternalId) {
		this.paymentMethodExternalId = paymentMethodExternalId;
	}

	public String getPaymentReference() {
		return paymentReference;
	}

	public void setPaymentReference(String paymentReference) {
		this.paymentReference = paymentReference;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public void setAmount(BigDecimal amount) {
		this.amount = amount;
	}

	public String getCurrency() {
		return currency;
	}

	public void setCurrency(String currency) {
		this.currency = currency;
	}
}

