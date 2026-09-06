package com.digithink.zsretail.erp.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.JoinTable;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;

import com.digithink.zsretail.model.Payment;
import com.digithink.zsretail.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Payment line entity for ERP synchronization tracking Represents individual
 * payment lines within a payment header
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false, exclude = "payments")
@ToString(exclude = "payments")
@NoArgsConstructor
public class PaymentLine extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "payment_header_id", nullable = false)
	private PaymentHeader paymentHeader;

	@ManyToOne
	@JoinColumn(name = "payment_id")
	private Payment payment; // Legacy single-payment reference (kept for backward compat)

	// All Payment records covered by this line — for CLIENT_ESPECES aggregates
	// this holds the full group; for other methods it holds the single payment.
	@OneToMany(fetch = FetchType.EAGER)
	@JoinTable(
		name = "payment_line_payments",
		joinColumns = @JoinColumn(name = "payment_line_id"),
		inverseJoinColumns = @JoinColumn(name = "payment_id")
	)
	private List<Payment> payments = new ArrayList<>();

	@Column(nullable = false)
	private String custNo; // Customer code

	@Column(nullable = false)
	private Double amount;

	@Column(nullable = false)
	private String fenceNo; // Session number

	@Column(nullable = false)
	private String ticketNo; // POS ticket number

	private String titleNumber;

	private LocalDate dueDate;

	private String drawerName;

	@Column(nullable = false)
	private Boolean synched = false;
}
