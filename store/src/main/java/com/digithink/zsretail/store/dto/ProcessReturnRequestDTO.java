package com.digithink.zsretail.store.dto;

import java.math.BigDecimal;
import java.util.List;

import com.digithink.zsretail.core.model.enumeration.ReturnType;

import lombok.Data;

@Data
public class ProcessReturnRequestDTO {
	
	private String ticketNumber; // Sales number of original ticket
	
	private ReturnType returnType; // SIMPLE_RETURN or RETURN_VOUCHER
	
	private List<ReturnLineDTO> returnLines;
	
	private String notes;

	/** RFID: the tags read at the POS for the lines returned in part (none: empty or null). */
	private List<String> returnedEpcs;

	/**
	 * Step D (3.1.0): the grant token of the badge scan that authorised the return (MAKE_RETURN), when the logged user's
	 * role does not hold the right; null otherwise (TillRightsGuard).
	 */
	private String tillGrant;

	@Data
	public static class ReturnLineDTO {
		private Long salesLineId; // ID of original sales line
		private BigDecimal quantity; // Quantity to return (must be <= original quantity); 2.2.1: read as sent, whole only
	}
}

