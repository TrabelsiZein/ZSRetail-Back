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

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * Head office plan, step 4: a member enrolled at this store while its loyalty is owned by the head office, to be sent
 * up (POST /ho/loyalty/members). Written in the transaction that creates the member. After a merge answer the row keeps
 * the surviving card: this card's member is deactivated here and its movements count for the surviving member.
 */
@Entity
@Table(name = "hol_loyalty_member_copy",
		uniqueConstraints = @UniqueConstraint(name = "uk_hol_loyalty_member_copy_card", columnNames = "card_number"),
		indexes = { @Index(name = "ix_hol_loyalty_member_copy_queue", columnList = "status, attempts"),
				@Index(name = "ix_hol_loyalty_member_copy_surviving", columnList = "surviving_card_number") })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class LoyaltyMemberCopy extends _BaseEntity {

	public static final int LAST_ERROR_LENGTH = 1000;

	/** loyalty_member id of the member in this store. */
	@Column(name = "member_id", nullable = false)
	private Long memberId;

	@Column(name = "card_number", nullable = false, length = 50)
	private String cardNumber;

	/** PENDING (to send), SENT (accepted by the head office), ERROR (rejected; retried). */
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private SalesCopyStatus status = SalesCopyStatus.PENDING;

	/** Sends that got an answer for this member; an unreachable head office is not counted. */
	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_error", length = LAST_ERROR_LENGTH)
	private String lastError;

	@Column(name = "last_push_date")
	private LocalDateTime lastPushDate;

	/** CREATED, EXISTS or MERGED, as the head office answered. */
	@Column(length = 10)
	private String outcome;

	/** After a merge: the card that holds the phone in the network; this card's movements go to it. */
	@Column(name = "surviving_card_number", length = 50)
	private String survivingCardNumber;
}
