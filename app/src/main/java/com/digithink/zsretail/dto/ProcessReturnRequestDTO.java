package com.digithink.zsretail.dto;

import java.math.BigDecimal;
import java.util.List;

import com.digithink.zsretail.model.enumeration.ReturnType;

import lombok.Data;

@Data
public class ProcessReturnRequestDTO {
	
	private String ticketNumber; // Sales number of original ticket
	
	private ReturnType returnType; // SIMPLE_RETURN or RETURN_VOUCHER
	
	private List<ReturnLineDTO> returnLines;
	
	private String notes;
	
	@Data
	public static class ReturnLineDTO {
		private Long salesLineId; // ID of original sales line
		private BigDecimal quantity; // Quantity to return (must be <= original quantity); 2.2.1: read as sent, whole only
	}
}

