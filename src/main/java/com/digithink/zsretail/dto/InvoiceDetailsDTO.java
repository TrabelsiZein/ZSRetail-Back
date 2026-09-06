package com.digithink.zsretail.dto;

import java.time.LocalDate;

import com.digithink.zsretail.model.enumeration.InvoiceLineGroupingMode;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTOs for GET /admin/invoices/{id}/details to avoid Jackson circular reference
 * when serializing entities (InvoiceHeader, InvoiceLine, SalesHeader).
 */
public class InvoiceDetailsDTO {

	/** Invoice header for details view (no lines collection). */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class InvoiceHeaderDetail {
		private Long id;
		private String invoiceNumber;
		private LocalDate invoiceDate;
		private CustomerSummary customer;
		private UserSummary createdByUser;
		private InvoiceLineGroupingMode lineGroupingMode;
		private Double subtotal;
		private Double taxAmount;
		private Double discountAmount;
		private Double totalAmount;
		private String notes;
		// Editable snapshot fields (may override customer FK data on the invoice)
		private String snapshotCustomerName;
		private String snapshotCustomerAddress;
		private String snapshotCustomerPhone;
		private String snapshotCustomerTaxRegNo;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class CustomerSummary {
		private Long id;
		private String name;
		private String customerCode;
		private String address;
		private String phone;
		private String taxRegistrationNo;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class UserSummary {
		private Long id;
		private String username;
		private String fullName;
	}

	/** One invoice line for details/print (no back-reference to invoice). */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class InvoiceLineDetail {
		private Long id;
		private ItemSummary item;
		private ItemFamilySummary itemFamily;
		private ItemSubFamilySummary itemSubFamily;
		private String lineDescription;
		private Integer quantity;
		private Double unitPrice;
		private Double unitPriceIncludingVat;
		private Double subtotal;
		private Double taxAmount;
		private Double totalAmount;
		private Double lineTotalIncludingVat;
		private Integer vatPercent;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ItemSummary {
		private Long id;
		private String name;
		private String itemCode;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ItemFamilySummary {
		private Long id;
		private String name;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class ItemSubFamilySummary {
		private Long id;
		private String name;
	}

	/** Ticket summary for details/print (no invoice back-reference). */
	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class TicketSummary {
		private Long id;
		private String salesNumber;
		private java.time.LocalDateTime salesDate;
		private Double totalAmount;
	}
}
