package com.digithink.zsretail.dto;

import java.time.LocalDate;

import com.digithink.zsretail.model.enumeration.InvoiceLineGroupingMode;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for purchase invoice list API.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PurchaseInvoiceListDTO {

	private Long id;
	private String invoiceNumber;
	private LocalDate invoiceDate;
	private VendorSummary vendor;
	private Double subtotal;
	private Double taxAmount;
	private Double discountAmount;
	private Double totalAmount;
	private String notes;
	private InvoiceLineGroupingMode lineGroupingMode;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class VendorSummary {
		private Long id;
		private String name;
		private String vendorCode;
	}
}
