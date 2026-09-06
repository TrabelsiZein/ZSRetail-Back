package com.digithink.zsretail.erp.dynamicsnav.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class DynamicsNavSalesOrderHeaderDTO {

	@JsonProperty("Document_Type")
	private String documentType = "Order";

	@JsonProperty("No")
	private String documentNo;

	@JsonProperty("Sell_to_Customer_No")
	private String sellToCustomerNo;

	@JsonProperty("Sell_to_Customer_Name")
	private String sellToCustomerName;

	@JsonProperty("Bill_to_Name_2")
	private String billToName2;

	@JsonProperty("Responsibility_Center")
	private String responsibilityCenter;

	@JsonProperty("Location_Code")
	private String locationCode;

	@JsonProperty("Posting_Date")
	private LocalDate postingDate;

	@JsonProperty("Fence_No")
	private String fenceNo;

	@JsonProperty("POS_Document_No")
	private String posDocumentNo;

	@JsonProperty("POS_Invoice")
	private Boolean posInvoice = false;

	@JsonProperty("Fiscal_Registration")
	private String fiscalRegistration;

	@JsonProperty("Discount_Percent")
	private Double discountPercent;

	@JsonProperty("POS_Order")
	private Boolean posOrder = false;

	@JsonProperty("Billing_type")
	public String billingType;

	@JsonProperty("Ship_to_Code")
	public String shipToCode;

	@JsonProperty("Ticket_Amount")
	public Double ticketAmount;

}
