package com.digithink.zsretail.holink.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Index;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.holink.enumeration.SalesCopyStatus;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.LoyaltyTransactionType;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: a loyalty movement of this store (a loyalty_transaction row of a member of the network
 * register) to be sent up (POST /ho/loyalty/movements). Found by a query, not by a hook in the selling services: a
 * transaction without a row gets one. loyalty_transaction rows are never changed, so a movement is sent until the head
 * office accepts it, then never again. The type, points and card are copied here for the link page.
 */
@Entity
@Table(name = "hol_loyalty_movement_copy",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_loyalty_movement_copy_local", columnNames = "local_id"),
		indexes = { @Index(name = "ix_hol_loyalty_movement_copy_queue", columnList = "status, local_id"),
				@Index(name = "ix_hol_loyalty_movement_copy_card", columnList = "card_number") })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class LoyaltyMovementCopy extends _BaseEntity {

	public static final int LAST_ERROR_LENGTH = 1000;

	/** loyalty_transaction id: the key sent to the head office. */
	@Column(name = "local_id", nullable = false)
	private Long localId;

	/** The member's card when the movement was made. */
	@Column(name = "card_number", nullable = false, length = 50)
	private String cardNumber;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private LoyaltyTransactionType type;

	@Column(nullable = false)
	private int points;

	/** Signed effect on the balance. */
	@Column(nullable = false)
	private int delta;

	/** Store clock of the movement. */
	@Column(name = "movement_date")
	private LocalDateTime movementDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private SalesCopyStatus status = SalesCopyStatus.PENDING;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_error", length = LAST_ERROR_LENGTH)
	private String lastError;

	@Column(name = "last_push_date")
	private LocalDateTime lastPushDate;
}
