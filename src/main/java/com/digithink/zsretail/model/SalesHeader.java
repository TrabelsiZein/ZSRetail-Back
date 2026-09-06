package com.digithink.zsretail.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Transient;

import com.digithink.zsretail.model.LoyaltyMember;
import com.digithink.zsretail.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Sales header entity - represents sales orders/invoices/tickets
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class SalesHeader extends _BaseEntity {

	@Column(nullable = false, unique = true)
	private String salesNumber;

	@Column(nullable = false)
	private LocalDateTime salesDate = LocalDateTime.now();

	@ManyToOne
	@JoinColumn(name = "customer_id")
	private Customer customer;

	@ManyToOne
	@JoinColumn(name = "created_by_user")
	private UserAccount createdByUser;

	@ManyToOne
	@JoinColumn(name = "cashier_session_id")
	private CashierSession cashierSession;

	@ManyToOne
	@JoinColumn(name = "invoice_id")
	private InvoiceHeader invoice;

	/**
	 * Optional denormalized invoice number for reporting and quick access.
	 * Kept in sync with {@link InvoiceHeader#invoiceNumber}.
	 */
	private String invoiceNumber;

	@Enumerated(EnumType.STRING)
	private TransactionStatus status = TransactionStatus.PENDING;

	private Double subtotal;

	private Double taxAmount;

	private Double discountAmount;

	private Double discountPercentage;

	private Double totalAmount;

	private Double paidAmount;

	private Double changeAmount;

	private String paymentReference;

	private String notes;

	private LocalDateTime completedDate;

	// Transient field for display purposes
	@Transient
	private Long customerId;

	// Transient field for display purposes
	@Transient
	private Long createdByUserId;

	// Transient field for display purposes
	@Transient
	private Long cashierSessionId;

	@Enumerated(EnumType.STRING)
	private SynchronizationStatus synchronizationStatus = SynchronizationStatus.NOT_SYNCHED;

	private String erpNo; // Document_No from Dynamics NAV

	/** True when customer requested an invoice (Prepare Invoice) and fiscal registration was set */
	private Boolean invoiced = false;

	/** Fiscal registration number set when preparing invoice (mandatory for invoice) */
	private String fiscalRegistration;

	/** Optional customer name to use on the invoice (sent to NAV Bill_to_Name_2) */
	private String invoiceCustomerName;

	/** Loyalty cardholder attached to this sale (independent from Customer) */
	@ManyToOne
	@JoinColumn(name = "loyalty_member_id")
	private LoyaltyMember loyaltyMember;

	/**
	 * Origin of the header-level discount on this sale.
	 * Values: MANUAL | PROMOTION
	 * Null when no header discount was applied.
	 */
	@Column(name = "discount_source", length = 20)
	private String discountSource;

	/**
	 * Cart-level promotion that produced the header discount.
	 * Set only when discountSource = PROMOTION.
	 * groupItems/getItem are excluded from serialization here so ticket responses
	 * keep their pre-1.10 shape and never lazy-load the group member list.
	 */
	@ManyToOne
	@JoinColumn(name = "promotion_id")
	@JsonIgnoreProperties({"groupItems", "getItem"})
	private Promotion promotion;

	/**
	 * Table number assigned to this ticket (used when table management is enabled).
	 * Null when table management is disabled or not applicable.
	 */
	@Column(name = "table_number")
	private Integer tableNumber;

	/** Points earned by the loyalty member from this sale */
	@Column(name = "loyalty_points_earned")
	private Integer loyaltyPointsEarned;

	/** Points redeemed by the loyalty member on this sale */
	@Column(name = "loyalty_points_redeemed")
	private Integer loyaltyPointsRedeemed;

	/** TND value of the loyalty points redeemed (deducted from total) */
	@Column(name = "loyalty_deduction_amount")
	private Double loyaltyDeductionAmount;
}
