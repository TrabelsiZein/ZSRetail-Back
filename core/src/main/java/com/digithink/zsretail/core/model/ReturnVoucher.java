package com.digithink.zsretail.core.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Return voucher entity - vouchers issued for returns
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class ReturnVoucher extends _BaseEntity {

	@Column(nullable = false, unique = true)
	private String voucherNumber;

	@Column(nullable = false)
	private LocalDateTime voucherDate = LocalDateTime.now();

	@ManyToOne
	@JoinColumn(name = "return_header_id", nullable = false)
	@JsonIgnore
	private ReturnHeader returnHeader;

	@ManyToOne
	@JoinColumn(name = "customer_id")
	private Customer customer;

	@Column(nullable = false)
	private Double voucherAmount;

	@Column(nullable = false)
	private LocalDate expiryDate;

	@Enumerated(EnumType.STRING)
	private TransactionStatus status = TransactionStatus.PENDING;

	private Double usedAmount = 0.0;

	private String notes;

	/**
	 * 3.1.0 (test log I-07): the voucher's amount and the amount used are stored to the millime, half up ({@link
	 * Quantities#roundAmount}), as the payments that use it.
	 */
	@PrePersist
	@PreUpdate
	public void roundAmounts() {
		voucherAmount = Quantities.roundAmountOrNull(voucherAmount);
		usedAmount = Quantities.roundAmountOrNull(usedAmount);
	}
}

