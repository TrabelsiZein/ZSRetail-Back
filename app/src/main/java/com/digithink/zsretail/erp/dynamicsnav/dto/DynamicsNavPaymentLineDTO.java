package com.digithink.zsretail.erp.dynamicsnav.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public class DynamicsNavPaymentLineDTO {

	@JsonProperty("doc_no")
	private String docNo;

	@JsonProperty("cust_no")
	private String custNo;

	@JsonProperty("amount")
	private Double amount;

	@JsonProperty("fenceNo")
	private String fenceNo;

	@JsonProperty("ticketNo")
	private String ticketNo;

	@JsonProperty("title_no")
	private String titleNo;

	@JsonProperty("due_date")
	private String dueDate; // Format: "YYYY-MM-DD" or empty string

	@JsonProperty("drawer_name")
	private String drawerName;

	public String getDocNo() {
		return docNo;
	}

	public void setDocNo(String docNo) {
		this.docNo = docNo;
	}

	public String getCustNo() {
		return custNo;
	}

	public void setCustNo(String custNo) {
		this.custNo = custNo;
	}

	public Double getAmount() {
		return amount;
	}

	public void setAmount(Double amount) {
		this.amount = amount;
	}

	public String getFenceNo() {
		return fenceNo;
	}

	public void setFenceNo(String fenceNo) {
		this.fenceNo = fenceNo;
	}

	public String getTicketNo() {
		return ticketNo;
	}

	public void setTicketNo(String ticketNo) {
		this.ticketNo = ticketNo;
	}

	public String getTitleNo() {
		return titleNo;
	}

	public void setTitleNo(String titleNo) {
		this.titleNo = titleNo;
	}

	public String getDueDate() {
		return dueDate;
	}

	public void setDueDate(String dueDate) {
		this.dueDate = dueDate;
	}

	public String getDrawerName() {
		return drawerName;
	}

	public void setDrawerName(String drawerName) {
		this.drawerName = drawerName;
	}
}
