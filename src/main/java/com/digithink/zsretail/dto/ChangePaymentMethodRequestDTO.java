package com.digithink.zsretail.dto;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * Request to change the payment method of a single payment row on a ticket,
 * WITHOUT changing any amount. Only allowed while the ticket's cashier session
 * has not yet been synchronized with NAV.
 */
@Data
public class ChangePaymentMethodRequestDTO {

	/** The Payment row (on the ticket) whose method is being changed. */
	private Long paymentId;

	/** The new payment method to assign. */
	private Long newPaymentMethodId;

	// Optional method-specific fields — required only when the NEW method requires them.
	private String titleNumber;

	@JsonFormat(pattern = "yyyy-MM-dd")
	private LocalDate dueDate;

	private String drawerName;

	private String issuingBank;

	/** Optional free-text reason, stored in the audit log. */
	private String reason;
}
