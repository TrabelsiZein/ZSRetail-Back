package com.digithink.zsretail.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.digithink.zsretail.model.enumeration.InvoiceLineGroupingMode;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for invoice list API. Avoids serializing entity relations (e.g. lines, back-references)
 * which cause Jackson circular reference / lazy-load issues.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceListDTO {

	private Long id;
	private String invoiceNumber;
	private LocalDate invoiceDate;
	private CustomerSummary customer;
	private Double subtotal;
	private Double taxAmount;
	private Double discountAmount;
	private Double totalAmount;
	private String notes;
	private InvoiceLineGroupingMode lineGroupingMode;
	private String franchiseLocationCode;
	private LocalDateTime franchiseReceivedAt;

	/** Minimal customer info for list display. */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CustomerSummary {
		private Long id;
		private String name;
		private String customerCode;
	}
}
