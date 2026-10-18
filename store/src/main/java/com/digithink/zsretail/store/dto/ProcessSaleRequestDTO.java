package com.digithink.zsretail.store.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

import com.digithink.zsretail.core.utils.WholeQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

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

	/**
	 * Step D (3.1.0): the grant token of a badge scan for the header's manual discount (APPLY_TOTAL_DISCOUNT), when the
	 * logged user's role does not hold the right; null otherwise (TillRightsGuard).
	 */
	private String tillGrant;

	private List<SaleLineDTO> lines;
	private List<PaymentDTO> payments;
	
	@Data
	public static class SaleLineDTO {
		private Long itemId;
		/** 2.2.1: up to 3 decimals when the store allows them (QuantityPolicy). */
		private BigDecimal quantity;
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
		@JsonDeserialize(using = WholeQuantityDeserializer.class) // 2.2.1: whole only, 1.5 refused (400)
		private Integer freeQuantity;

		/** RFID: the tags of the pieces of this line read at the POS (none: empty or null); the paid pieces only. */
		private List<String> epcs;

		/**
		 * Step D (3.1.0): the grant token of a badge scan for this line's manual discount (APPLY_LINE_DISCOUNT), when
		 * the logged user's role does not hold the right; null otherwise (TillRightsGuard).
		 */
		private String tillGrant;
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

