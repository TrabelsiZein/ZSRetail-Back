package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Enumerated;
import javax.persistence.EnumType;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;

import com.digithink.zsretail.model.enumeration.CounterType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Session cash count entity - represents a line in the cash counting process
 * Each line records: denomination value, quantity, payment method, and who counted it
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class SessionCashCount extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "cashier_session_id", nullable = false)
	private CashierSession cashierSession;

	// Denomination value (e.g., 0.5, 1, 5, 10, 20, 50, 100 for TND)
	@Column(nullable = false)
	private Double denominationValue;

	// Quantity (number of pieces)
	@Column(nullable = false)
	private Integer quantity;

	// Payment method (null for cash, or reference to payment method for checks, cards, etc.)
	@ManyToOne
	@JoinColumn(name = "payment_method_id")
	private PaymentMethod paymentMethod;

	// Who counted this line (POS_USER or RESPONSIBLE)
	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private CounterType counterType;

	// Total amount for this line (denominationValue * quantity)
	@Column(nullable = false)
	private Double lineTotal;

	// Optional reference number (e.g., check number, card last 4 digits)
	private String referenceNumber;

	// Optional notes for this line
	private String notes;
}

