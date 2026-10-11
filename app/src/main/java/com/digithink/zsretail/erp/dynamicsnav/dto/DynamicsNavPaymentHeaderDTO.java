package com.digithink.zsretail.erp.dynamicsnav.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DynamicsNavPaymentHeaderDTO {

	@JsonProperty("payment_class")
	private String paymentClass;

	@JsonProperty("postdate")
	private String postdate; // Format: "YYYY-MM-DD"

	public String getPaymentClass() {
		return paymentClass;
	}

	public void setPaymentClass(String paymentClass) {
		this.paymentClass = paymentClass;
	}

	public String getPostdate() {
		return postdate;
	}

	public void setPostdate(String postdate) {
		this.postdate = postdate;
	}
}

