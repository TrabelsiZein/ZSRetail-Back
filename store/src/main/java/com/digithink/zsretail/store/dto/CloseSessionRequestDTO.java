package com.digithink.zsretail.store.dto;

import java.util.List;

import com.digithink.zsretail.core.utils.WholeQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import lombok.Data;

/**
 * DTO for closing a cashier session with cash count details
 */
@Data
public class CloseSessionRequestDTO {

	private Double actualCash;
	private String notes;
	private List<CashCountLineDTO> cashCountLines;
	/**
	 * Step D (3.1.0): the grant token of the badge scan that authorised closing with a cash difference
	 * (CLOSE_SESSION_WITH_DISCREPANCY); replaces the badge code and action sent before.
	 */
	private String tillGrant;
	/**
	 * The logged user saw the cash difference warning and confirmed it; it counts when his role holds
	 * till:close-session-with-discrepancy (step C), and needs no badge.
	 */
	private boolean discrepancyConfirmed;

	@Data
	public static class CashCountLineDTO {
		private Double denominationValue;
		@JsonDeserialize(using = WholeQuantityDeserializer.class) // 2.2.1: whole only, 1.5 refused (400)
		private Integer quantity;
		private Long paymentMethodId; // null for cash
		private String referenceNumber; // e.g., check number, card last 4
		private String notes;
	}
}

