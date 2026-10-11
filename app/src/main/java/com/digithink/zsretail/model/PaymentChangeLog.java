package com.digithink.zsretail.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Audit record for an admin changing a ticket's payment method. Amounts never
 * change; the amount is recorded as proof. Mirrors the GeneralSetupChangeLog
 * pattern. {@code createdAt} / {@code createdBy} on _BaseEntity capture when/who.
 */
@Entity
@Table(name = "payment_change_log")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class PaymentChangeLog extends _BaseEntity {

	@ManyToOne(optional = false)
	@JoinColumn(name = "sales_header_id", nullable = false)
	private SalesHeader salesHeader;

	@ManyToOne(optional = false)
	@JoinColumn(name = "payment_id", nullable = false)
	private Payment payment;

	@ManyToOne(optional = false)
	@JoinColumn(name = "old_payment_method_id", nullable = false)
	private PaymentMethod oldPaymentMethod;

	@ManyToOne(optional = false)
	@JoinColumn(name = "new_payment_method_id", nullable = false)
	private PaymentMethod newPaymentMethod;

	/** Payment amount — unchanged by the operation; recorded as proof. */
	@Column(nullable = false)
	private Double amount;

	private Double changeAmountOld;
	private Double changeAmountNew;

	/** Session expected cash before/after — set only when the session was CLOSED. */
	private Double realCashOld;
	private Double realCashNew;

	@ManyToOne
	@JoinColumn(name = "changed_by_user_id")
	private UserAccount changedByUser;

	/** Session status at the time of the change (OPENED / CLOSED). */
	@Column(length = 20)
	private String sessionStatus;

	@Column(columnDefinition = "NVARCHAR(512)")
	private String reason;
}
