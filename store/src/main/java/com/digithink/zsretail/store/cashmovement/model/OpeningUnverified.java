package com.digithink.zsretail.store.cashmovement.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.MapsId;
import javax.persistence.OneToOne;
import javax.persistence.Table;

import com.digithink.zsretail.core.model.CashierSession;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 3.2.0, the differences, step A: a session opened while the previous session of its drawer (the most recent closed one,
 * by the rule of the opening proposal) was CLOSED and not yet verified. Kept at the opening, beside the session (as
 * OpeningFloat), so a later page can list these sessions over a period: computing it afterwards would depend on the
 * drawer owner setting of today, not of that day. One row per such session only.
 */
@Entity
@Table(name = "cashier_session_opening_unverified")
@Data
@NoArgsConstructor
public class OpeningUnverified {

	@Id
	@Column(name = "cashier_session_id")
	private Long sessionId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "cashier_session_id",
			foreignKey = @ForeignKey(name = "fk_cashier_session_opening_unverified_session"))
	private CashierSession session;

	/** The previous session of the drawer, closed and not verified at that opening. */
	@ManyToOne
	@JoinColumn(name = "previous_session_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_cashier_session_opening_unverified_previous"))
	private CashierSession previousSession;
}
