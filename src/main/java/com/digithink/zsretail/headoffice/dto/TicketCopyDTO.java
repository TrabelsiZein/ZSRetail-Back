package com.digithink.zsretail.headoffice.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Copy of a store ticket (task 2.2), sent by the store to POST /ho/sales/tickets. References travel as business codes
 * plus a readable name, never a database id: the head office does not have the store's items, customers, users or
 * sessions. No store code: the head office takes the authenticated store.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TicketCopyDTO {

	private String salesNumber;

	private LocalDateTime salesDate;

	private LocalDateTime completedDate;

	/** TransactionStatus name: COMPLETED, or the status after a later change. */
	private String status;

	private Double subtotal;

	private Double taxAmount;

	private Double discountAmount;

	private Double discountPercentage;

	private Double totalAmount;

	private Double paidAmount;

	private Double changeAmount;

	/** Origin of the header discount: MANUAL or PROMOTION; null when none. */
	private String discountSource;

	/** Cart-level promotion of the header discount. */
	private String promotionCode;

	private String promotionName;

	private String customerCode;

	private String customerName;

	/** Username of the user who made the sale. */
	private String cashierLogin;

	private String cashierName;

	private String sessionNumber;

	private String loyaltyCardNumber;

	private String loyaltyMemberName;

	private Integer loyaltyPointsEarned;

	private Integer loyaltyPointsRedeemed;

	private Double loyaltyDeductionAmount;

	private Boolean invoiced;

	private String invoiceNumber;

	private Integer tableNumber;

	private String notes;

	/** In the store's line order. */
	private List<TicketLineCopyDTO> lines = new ArrayList<>();

	private List<PaymentCopyDTO> payments = new ArrayList<>();
}
