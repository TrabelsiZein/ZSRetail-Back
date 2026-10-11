package com.digithink.zsretail.model;

import java.time.LocalDate;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;

import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Loyalty transaction entity - immutable audit log of every point movement.
 * Records are never updated or deleted; all adjustments create new rows.
 */
@Entity
@Table(name = "loyalty_transaction")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class LoyaltyTransaction extends _BaseEntity {

	@ManyToOne
	@JoinColumn(name = "loyalty_member_id", nullable = false)
	@JsonIgnore
	private LoyaltyMember loyaltyMember;

	/** The active loyalty program at the time of the transaction */
	@ManyToOne
	@JoinColumn(name = "loyalty_program_id")
	private LoyaltyProgram loyaltyProgram;

	/** The sale that triggered this transaction (null for manual adjustments) */
	@ManyToOne
	@JoinColumn(name = "sales_header_id")
	private SalesHeader salesHeader;

	/** The return that triggered this transaction (null unless type = REVERSED) */
	@ManyToOne
	@JoinColumn(name = "return_header_id")
	private ReturnHeader returnHeader;

	/** The cashier session during which this transaction was created */
	@ManyToOne
	@JoinColumn(name = "cashier_session_id")
	private CashierSession cashierSession;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private LoyaltyTransactionType type;

	/** Always a positive number; sign is derived from the type */
	@Column(nullable = false)
	private Integer points;

	@Column(name = "balance_before", nullable = false)
	private Integer balanceBefore;

	@Column(name = "balance_after", nullable = false)
	private Integer balanceAfter;

	@Column(length = 500)
	private String description;

	/** Date after which this earned batch of points expires (null = never expires) */
	@Column(name = "expiry_date")
	private LocalDate expiryDate;
}
