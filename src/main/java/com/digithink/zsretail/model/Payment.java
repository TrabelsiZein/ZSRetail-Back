package com.digithink.zsretail.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Enumerated;
import javax.persistence.EnumType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.model.enumeration.TransactionStatus;
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
}

