package com.digithink.zsretail.store.cashmovement.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.MapsId;
import javax.persistence.OneToOne;
import javax.persistence.Table;

import com.digithink.zsretail.core.model.CashierSession;
import com.digithink.zsretail.core.model.UserAccount;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, the opening amount: what the responsible took out of the drawer at the verification of a closed session and
 * what he left in it (the float of the next session on the same drawer). One row per session verified since this step,
 * beside the session (as cashier_session_pos_machine): cashier_session (core's, also the head office's) is not changed.
 * Outside the session's expected cash: the closing and the verification differences do not move. Store only; nothing
 * to the head office or the ERP. docs/modules/cash-sessions.md, "The drawer from one session to the next".
 */
@Entity
@Table(name = "cashier_session_closing_withdrawal")
@Data
@NoArgsConstructor
public class ClosingWithdrawal {

	@Id
	@Column(name = "cashier_session_id")
	private Long sessionId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "cashier_session_id",
			foreignKey = @ForeignKey(name = "fk_cashier_session_closing_withdrawal_session"))
	private CashierSession session;

	@Enumerated(EnumType.STRING)
	@Column(name = "choice", nullable = false, length = 10)
	private ClosingChoice choice;

	/** The cash the responsible counted (the cash line of his count). */
	@Column(name = "cash_counted", nullable = false)
	private Double cashCounted;

	@Column(name = "amount_taken", nullable = false)
	private Double amountTaken;

	/** cash counted - taken: the float of the next session on the same drawer. */
	@Column(name = "amount_left", nullable = false)
	private Double amountLeft;

	@ManyToOne
	@JoinColumn(name = "done_by_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_cashier_session_closing_withdrawal_done_by"))
	private UserAccount doneBy;

	@Column(name = "done_at", nullable = false)
	private LocalDateTime doneAt;

	/** The slip's number when something was taken (<session>-PC); null for "Rien retirer". */
	@Column(name = "slip_number", length = 40)
	private String slipNumber;
}
