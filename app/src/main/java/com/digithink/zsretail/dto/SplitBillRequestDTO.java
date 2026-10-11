package com.digithink.zsretail.dto;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * Request DTO for split-bill operation on a pending table ticket.
 * Contains the selected line IDs to pay now and the payment details.
 */
@Data
public class SplitBillRequestDTO {

	/** IDs of SalesLine records to extract and pay in this split */
	private List<Long> selectedLineIds;

	/** Pre-calculated totals for the selected lines (computed on frontend) */
	private Double subtotal;
	private Double taxAmount;
	private Double totalAmount;
	private Double paidAmount;
	private Double changeAmount;

	/** Customer for the split receipt (optional) */
	private Long customerId;

	/** Payments for the split portion */
	private List<PaymentDTO> payments;

	@Data
	public static class PaymentDTO {
		private Long paymentMethodId;
		private Double amount;
		private String reference;
		private String titleNumber;
		@JsonFormat(pattern = "yyyy-MM-dd")
		private LocalDate dueDate;
		private String drawerName;
		private String issuingBank;
	}
}
