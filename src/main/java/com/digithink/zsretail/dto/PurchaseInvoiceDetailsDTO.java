package com.digithink.zsretail.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.digithink.zsretail.model.enumeration.InvoiceLineGroupingMode;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTOs for GET /admin/purchase-invoices/{id}/details.
 */
public class PurchaseInvoiceDetailsDTO {

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PurchaseInvoiceHeaderDetail {
		private Long id;
		private String invoiceNumber;
		private LocalDate invoiceDate;
		private VendorSummary vendor;
		private UserSummary createdByUser;
		private InvoiceLineGroupingMode lineGroupingMode;
		private Double subtotal;
		private Double taxAmount;
		private Double discountAmount;
		private Double totalAmount;
		private String notes;
		// Editable snapshot fields (may override vendor FK data on the invoice)
		private String snapshotVendorName;
		private String snapshotVendorAddress;
		private String snapshotVendorPhone;
		private String snapshotVendorTaxRegNo;
	}

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class VendorSummary {
		private Long id;
		private String name;
		private String vendorCode;
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

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PurchaseInvoiceLineDetail {
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

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class PurchaseSummary {
		private Long id;
		private String purchaseNumber;
		private LocalDateTime purchaseDate;
		private Double totalAmount;
	}
}
