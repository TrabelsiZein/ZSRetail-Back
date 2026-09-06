package com.digithink.zsretail.erp.dynamicsnav.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DynamicsNavCustomerDTO {

	@JsonProperty("No")
	private String number;

	@JsonProperty("Name")
	private String name;

	@JsonProperty("Address")
	private String address;

	@JsonProperty("Phone_No")
	private String phoneNumber;

	@JsonProperty("E_Mail")
	private String email;

	@JsonProperty("Modified_At")
	private OffsetDateTime lastModifiedAt;

	@JsonProperty("Status")
	public String status;
	@JsonProperty("Customer_Price_Group")
	public String customerPriceGroup;
	@JsonProperty("Customer_Disc_Group")
	public String customerDiscGroup;
	@JsonProperty("AuxiliaryIndex1")
	public String auxiliaryIndex1;
}
