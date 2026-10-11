package com.digithink.zsretail.erp.dynamicsnav.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Data
public class DynamicsNavSessionDTO {

	@JsonProperty("fence_no")
	private String fenceNo;

	@JsonProperty("location")
	private String location;

	@JsonProperty("nber_ticket")
	private Integer nberTicket;

	@JsonProperty("closing_amount")
	private Double closingAmount;

	@JsonProperty("nber_return_cashed")
	private Integer nberReturnCashed;

	@JsonProperty("amount_return_cashed")
	private Double amountReturnCashed;

	@JsonProperty("nber_return")
	private Integer nberReturn;

}
