package com.digithink.zsretail.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.model.enumeration.TransactionStatus;
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
}

