package com.digithink.zsretail.dto;

import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

/**
 * DTO for processing a complete sale transaction
 */
@Data
public class ProcessSaleRequestDTO {
	
	private Double subtotal;
	private Double taxAmount;
	private Double discountAmount;
	private Double discountPercentage;
	private Double totalAmount;

	/** MANUAL | PROMOTION — null when no header discount. */
	private String discountSource;

	/** ID of the cart-level promotion applied (set when discountSource = PROMOTION). */
	private Long promotionId;
	private Double paidAmount;
	private Double changeAmount;
	private Long customerId;
	private String notes;

	/** Loyalty member card holder attached to this sale (independent from customerId) */
	private Long loyaltyMemberId;

	/** Points the loyalty member wants to redeem on this sale (0 = no redemption) */
	private Integer loyaltyPointsToRedeem = 0;

	/** Table number (set when table management is enabled, null otherwise) */
	private Integer tableNumber;

	private List<SaleLineDTO> lines;
	private List<PaymentDTO> payments;
	
	@Data
	public static class SaleLineDTO {
		private Long itemId;
		private Integer quantity;
		private Double unitPrice;
		private Double lineTotal;
		private Double discountPercentage;
		private Double discountAmount;
		private Double vatAmount;
		private Integer vatPercent;
		private Double unitPriceIncludingVat;
		private Double lineTotalIncludingVat;

		/** MANUAL | SALES_PRICE | SALES_DISCOUNT | PROMOTION — null when no discount. */
		private String discountSource;

		/** ID of the promotion applied on this line (set when discountSource = PROMOTION). */
		private Long promotionId;

		/** Number of free units granted by a FREE_QUANTITY promotion. When > 0, backend creates a second line. */
		private Integer freeQuantity;
	}
	
	@Data
	public static class PaymentDTO {
		private Long paymentMethodId;
		private Double amount;
		private String reference;
		private String notes;
		private String titleNumber;
		@JsonFormat(pattern = "yyyy-MM-dd")
		private LocalDate dueDate;
		private String drawerName;
		private String issuingBank;
	}
}

