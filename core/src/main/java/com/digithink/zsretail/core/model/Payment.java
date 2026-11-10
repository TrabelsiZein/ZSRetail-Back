package com.digithink.zsretail.core.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;
import jakarta.persistence.EnumType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import com.digithink.zsretail.core.model.enumeration.TransactionStatus;
import com.digithink.zsretail.core.utils.Quantities;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Payment entity - represents payment transactions
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class Payment extends _BaseEntity {

	@Column(nullable = false)
	private LocalDateTime paymentDate = LocalDateTime.now();

	@ManyToOne
	@JoinColumn(name = "sales_header_id", nullable = false)
	@JsonIgnore
	private SalesHeader salesHeader;

	@ManyToOne
	@JoinColumn(name = "payment_method_id", nullable = false)
	private PaymentMethod paymentMethod;

	@ManyToOne
	@JoinColumn(name = "created_by_user")
	private UserAccount createdByUser;

	@Enumerated(EnumType.STRING)
	private TransactionStatus status = TransactionStatus.PENDING;

	private Double totalAmount;

	private String paymentReference;

	private String notes;

	private String titleNumber;

	private LocalDate dueDate;

	private String drawerName;

	private String issuingBank;

	@Column(nullable = true)
	private Boolean synched = false;

	/** 3.1.0 (test log I-07): the amount is stored to the millime, half up ({@link Quantities#roundAmount}). */
	@PrePersist
	@PreUpdate
	public void roundAmounts() {
		totalAmount = Quantities.roundAmountOrNull(totalAmount);
	}
}

