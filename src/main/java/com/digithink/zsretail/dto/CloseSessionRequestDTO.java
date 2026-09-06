package com.digithink.zsretail.dto;

import java.util.List;

import lombok.Data;

/**
 * DTO for closing a cashier session with cash count details
 */
@Data
public class CloseSessionRequestDTO {

	private Double actualCash;
	private String notes;
	private List<CashCountLineDTO> cashCountLines;
	private String badgeCode; // Badge code scanned for discrepancy validation
	private String badgePermission; // Permission being validated (should be "CLOSE_SESSION_WITH_DISCREPANCY")

	@Data
	public static class CashCountLineDTO {
		private Double denominationValue;
		private Integer quantity;
		private Long paymentMethodId; // null for cash
		private String referenceNumber; // e.g., check number, card last 4
		private String notes;
	}
}

