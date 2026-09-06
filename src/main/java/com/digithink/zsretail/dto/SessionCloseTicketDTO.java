package com.digithink.zsretail.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for session close ticket print (declared amounts only, no system calculations).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionCloseTicketDTO {

	private String sessionNumber;
	private String cashierName;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime openedAt;

	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
	private LocalDateTime closedAt;

	private Double openingCash;
	private Double totalDeclared;

	/**
	 * Declared amount per payment method (as entered by user when closing).
	 */
	private List<PaymentMethodAmount> paymentMethodAmounts;

	private Long ticketsCount;
	private Long returnsCount;
	private Double netEspeces;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PaymentMethodAmount {
		private String paymentMethodName;
		private Double amount;
	}
}
