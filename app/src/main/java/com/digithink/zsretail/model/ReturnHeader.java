package com.digithink.zsretail.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.model.enumeration.ReturnType;
import com.digithink.zsretail.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.model.enumeration.TransactionStatus;

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
}

