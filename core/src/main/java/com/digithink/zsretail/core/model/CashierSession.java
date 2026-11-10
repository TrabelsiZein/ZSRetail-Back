package com.digithink.zsretail.core.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import com.digithink.zsretail.core.model.enumeration.SessionStatus;
import com.digithink.zsretail.core.model.enumeration.SynchronizationStatus;
import com.digithink.zsretail.core.utils.Quantities;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Cashier session entity - represents a cashier's shift/session When POS user
 * opens his shift, creates a session When closing, counts the money and closes
 * the session
 */
@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CashierSession extends _BaseEntity {

	@Column(nullable = false, unique = true)
	private String sessionNumber;

	@ManyToOne
	@JoinColumn(name = "cashier_id", nullable = false)
	private UserAccount cashier;

	@Column(nullable = false)
	private LocalDateTime openedAt;

	private LocalDateTime closedAt;

	@Enumerated(EnumType.STRING)
	private SessionStatus status = SessionStatus.OPENED;

	// Opening cash fund (font de caisse)
	@Column(nullable = false)
	private Double openingCash;

	// Real cash - what should be in cashier (openingCash + cash sales - change
	// given)
	private Double realCash;

	// POS user closure cash - cash counted by POS user when closing session
	private Double posUserClosureCash;

	// Responsible closure cash - cash counted by responsible user when verifying
	private Double responsibleClosureCash;

	// Responsible user who verified the count
	@ManyToOne
	@JoinColumn(name = "verified_by_user")
	private UserAccount verifiedBy;

	private LocalDateTime verifiedAt;

	private String verificationNotes;

	@Enumerated(EnumType.STRING)
	private SynchronizationStatus synchronizationStatus = SynchronizationStatus.NOT_SYNCHED;

	private String erpNo; // External reference from ERP (e.g., document number)

	/**
	 * 3.1.0 (test log I-07): the expected cash (realCash) is stored to the millime, half up ({@link
	 * Quantities#roundAmount}), as the tickets and returns it adds up.
	 */
	@PrePersist
	@PreUpdate
	public void roundAmounts() {
		realCash = Quantities.roundAmountOrNull(realCash);
	}
}
