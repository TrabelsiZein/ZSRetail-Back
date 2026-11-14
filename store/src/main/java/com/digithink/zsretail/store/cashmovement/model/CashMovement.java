package com.digithink.zsretail.store.cashmovement.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.ForeignKey;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;
import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.core.model.enumeration.GrantedBy;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, POS machines step 5: cash taken out of the drawer or put into it during a session (docs/modules/
 * cash-sessions.md, "Cash movements"). Store only. Recorded on the logged cashier's own OPENED session only, never
 * edited and never deleted (no endpoint does it): a mistake is corrected by the opposite movement. The POS machine is
 * the session's (cashier_session_pos_machine), not stored again. The expected cash of the session adds the APPORT
 * movements and subtracts the others (CashierSessionService.calculateRealCash).
 */
@Entity
@Table(name = "cash_movement", uniqueConstraints = @UniqueConstraint(name = "uk_cash_movement_number",
		columnNames = "movement_number"), indexes = {
				@Index(name = "ix_cash_movement_session", columnList = "cashier_session_id"),
				@Index(name = "ix_cash_movement_moved_at", columnList = "moved_at") })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class CashMovement extends _BaseEntity {

	/** The session's number and the movement's rank in it, e.g. SESSION261114001-M01 (unique). */
	@Column(name = "movement_number", nullable = false, length = 40)
	private String number;

	@ManyToOne(optional = false)
	@JoinColumn(name = "cashier_session_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_cash_movement_session"))
	private CashierSession cashierSession;

	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, length = 20)
	private CashMovementType type;

	/** Above 0, to the millime; the type says in or out. */
	@Column(name = "amount", nullable = false)
	private Double amount;

	/** Optional, required for DEPENSE; at most 255 characters. */
	@Column(name = "note", length = 255)
	private String note;

	/** The logged user who recorded it (the session's cashier). */
	@ManyToOne(optional = false)
	@JoinColumn(name = "done_by_id", nullable = false, foreignKey = @ForeignKey(name = "fk_cash_movement_done_by"))
	private UserAccount doneBy;

	/** Who authorised it: the user himself (ROLE: his role holds the right), or the owner of the badge scanned (BADGE). */
	@ManyToOne(optional = false)
	@JoinColumn(name = "authorised_by_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_cash_movement_authorised_by"))
	private UserAccount authorisedBy;

	@Enumerated(EnumType.STRING)
	@Column(name = "granted_by", nullable = false, length = 10)
	private GrantedBy grantedBy;

	@Column(name = "moved_at", nullable = false)
	private LocalDateTime movedAt;
}
