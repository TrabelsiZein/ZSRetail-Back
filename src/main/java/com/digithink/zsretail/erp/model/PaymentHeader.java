package com.digithink.zsretail.erp.model;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.model.CashierSession;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.SynchronizationStatus;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Payment header entity for ERP synchronization tracking Groups payments by
 * payment class for ERP export at session level
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class PaymentHeader extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "cashier_session_id", nullable = false)
	private CashierSession cashierSession;

	@Column(nullable = false)
	private String paymentClass; // paymentMethod.code

	@Column(nullable = false)
	private LocalDate postDate;

	private String erpNo; // Document number from ERP

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SynchronizationStatus synchronizationStatus = SynchronizationStatus.NOT_SYNCHED;
}
