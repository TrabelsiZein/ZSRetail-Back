package com.digithink.zsretail.headoffice.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: a loyalty movement received from a store and applied to the register, once: unique by
 * store and the store's key. Keeps what the store sent (by codes), what was applied and the overspend (points a removal
 * could not take because the balance reached zero; reported in step 5). The ledger row the members' pages show is the
 * loyalty_transaction row {@link #transactionId}. Head office only data (prefix ho_).
 */
@Entity
@Table(name = "ho_loyalty_movement",
		uniqueConstraints = @UniqueConstraint(name = "uk_ho_loyalty_movement_store_key", columnNames = { "store_id",
				"store_key" }),
		indexes = @Index(name = "ix_ho_loyalty_movement_member", columnList = "member_id"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class HoLoyaltyMovement extends _BaseEntity {

	@Column(name = "store_id", nullable = false)
	private Long storeId;

	/** The store's key of the movement (its loyalty_transaction id). */
	@Column(name = "store_key", nullable = false, length = 50)
	private String storeKey;

	/** The card as sent; may be an alias. */
	@Column(name = "card_number", nullable = false, length = 50)
	private String cardNumber;

	/** loyalty_member id the movement was applied to. */
	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private LoyaltyTransactionType type;

	@Column(nullable = false)
	private int points;

	/** Signed effect sent by the store. */
	@Column(nullable = false)
	private int delta;

	/** Points a removal could not take (the balance never goes below zero); 0 otherwise. */
	@Column(name = "overspend_points", nullable = false)
	private int overspendPoints;

	@Column(name = "sales_number")
	private String salesNumber;

	@Column(name = "return_number")
	private String returnNumber;

	@Column(name = "program_code", length = 50)
	private String programCode;

	/** Store clock of the movement. */
	@Column(name = "store_date")
	private LocalDateTime storeDate;

	/** The head office loyalty_transaction row written for it. */
	@Column(name = "transaction_id")
	private Long transactionId;
}
