package com.digithink.zsretail.dto.franchise;

import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Invoice data sent by the franchise admin to franchise clients.
 * The client creates a local PurchaseHeader + PurchaseLines from this DTO.
 * unitPrice on each line becomes the client's lastDirectCost (purchase price).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FranchiseInvoiceDTO {

	private Long id;
	private String invoiceNumber;
	private LocalDate invoiceDate;
	private String franchiseLocationCode;
	private Double subtotal;
	private Double taxAmount;
	private Double totalAmount;
	private String notes;
	private List<FranchiseInvoiceLineDTO> lines;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	public static class FranchiseInvoiceLineDTO {
		private String itemCode;
		private String itemName;
		private Integer quantity;
		private Double unitPrice;
		private Double unitPriceIncludingVat;
		private Double subtotal;
		private Double taxAmount;
		private Double totalAmount;
		private Integer vatPercent;
	}
}
