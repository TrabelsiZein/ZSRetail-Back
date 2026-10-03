package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/** One payment of a {@link TicketCopyDTO} (task 2.2). */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class PaymentCopyDTO {

	private String paymentMethodCode;

	private String paymentMethodName;

	private Double amount;

	private LocalDateTime paymentDate;

	/** Cheque or draft number, when the payment method asks for one. */
	private String titleNumber;

	private LocalDate dueDate;
}
