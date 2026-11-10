package com.digithink.zsretail.core.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import com.digithink.zsretail.core.model.enumeration.ReturnType;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.utils.Quantities;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Return header entity - represents product returns
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ReturnHeader extends _BaseEntity {

	@Column(nullable = false, unique = true)
	private String returnNumber;

	@Column(nullable = false)
	private LocalDateTime returnDate = LocalDateTime.now();

	@ManyToOne
	@JoinColumn(name = "original_sales_header_id", nullable = false)
	private SalesHeader originalSalesHeader;

	@ManyToOne
	@JoinColumn(name = "created_by_user")
	private UserAccount createdByUser;

	@ManyToOne
	@JoinColumn(name = "cashier_session_id")
	private CashierSession cashierSession;

	@Enumerated(EnumType.STRING)
	private ReturnType returnType;

	@Enumerated(EnumType.STRING)
	private TransactionStatus status = TransactionStatus.COMPLETED;

	private Double totalReturnAmount;

	private String notes;

	// Reference to return voucher if return type is RETURN_VOUCHER
	@ManyToOne
	@JoinColumn(name = "return_voucher_id")
	private ReturnVoucher returnVoucher;

	// ERP synchronization fields
	@Enumerated(EnumType.STRING)
	private SynchronizationStatus synchronizationStatus = SynchronizationStatus.NOT_SYNCHED;

	private String erpNo; // Document_No from Dynamics NAV

	private Double discountPercentage;

	/**
	 * 3.1.0 (test log I-07): the amount given back is stored to the millime, half up ({@link Quantities#roundAmount}),
	 * as the voucher made from it; the discount percentage stays as computed.
	 */
	@PrePersist
	@PreUpdate
	public void roundAmounts() {
		totalReturnAmount = Quantities.roundAmountOrNull(totalReturnAmount);
	}
}

