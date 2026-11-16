package com.digithink.zsretail.core.model;

import java.time.LocalDate;
import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Enumerated;
import javax.persistence.EnumType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.PrePersist;
import javax.persistence.PreUpdate;

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
// 3.2.0: the payments of a ticket (the Journal, the tickets X / Z, the session details), by an index (db/3.2.0/update.sql step 19; Hibernate creates it on a new database)
@Table(indexes = @Index(name = "ix_payment_sales_header", columnList = "sales_header_id"))
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

