package com.digithink.zsretail.erp.dynamicsnav.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DynamicsNavReturnHeaderDTO {

	@JsonProperty("Document_Type")
	private String documentType = "Return Order";

	@JsonProperty("No")
	private String documentNo;

	@JsonProperty("Sell_to_Customer_No")
	private String sellToCustomerNo;

	@JsonProperty("Location_Code")
	private String locationCode;

	@JsonProperty("Posting_Date")
	private LocalDate postingDate;

	@JsonProperty("Fence_No")
	private String fenceNo;

	@JsonProperty("POS_Document_No")
	private String posDocumentNo;

	@JsonProperty("Ticket_Amount")
	private Double ticketAmount;

	@JsonProperty("POS_Order")
	private Boolean posOrder;

	@JsonProperty("Discount_Percent")
	private Double discountPercent;

	// Getters and setters
	public String getDocumentType() {
		return documentType;
	}

	public void setDocumentType(String documentType) {
		this.documentType = documentType;
	}

	public String getDocumentNo() {
		return documentNo;
	}

	// Setter without @JsonProperty to prevent serialization
	// We'll manually set this from the response
	public void setDocumentNo(String documentNo) {
		this.documentNo = documentNo;
	}

	public String getSellToCustomerNo() {
		return sellToCustomerNo;
	}

	public void setSellToCustomerNo(String sellToCustomerNo) {
		this.sellToCustomerNo = sellToCustomerNo;
	}

	public String getLocationCode() {
		return locationCode;
	}

	public void setLocationCode(String locationCode) {
		this.locationCode = locationCode;
	}

	public LocalDate getPostingDate() {
		return postingDate;
	}

	public void setPostingDate(LocalDate postingDate) {
		this.postingDate = postingDate;
	}

	public String getFenceNo() {
		return fenceNo;
	}

	public void setFenceNo(String fenceNo) {
		this.fenceNo = fenceNo;
	}

	public String getPosDocumentNo() {
		return posDocumentNo;
	}

	public void setPosDocumentNo(String posDocumentNo) {
		this.posDocumentNo = posDocumentNo;
	}

	public Double getTicketAmount() {
		return ticketAmount;
	}

	public void setTicketAmount(Double ticketAmount) {
		this.ticketAmount = ticketAmount;
	}

	public Boolean getPosOrder() {
		return posOrder;
	}

	public void setPosOrder(Boolean posOrder) {
		this.posOrder = posOrder;
	}

	public Double getDiscountPercent() {
		return discountPercent;
	}

	public void setDiscountPercent(Double discountPercent) {
		this.discountPercent = discountPercent;
	}
}
