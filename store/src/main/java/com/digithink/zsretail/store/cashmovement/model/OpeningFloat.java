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
 * 3.2.0, the opening amount: the float proposed to a session at its opening (what the responsible left in the same
 * drawer at the verification of its most recent closed session) and the "écart d'ouverture" (opening cash typed -
 * proposed). One row only when a float was proposed; none otherwise (no écart). Beside the session, store only.
 */
@Entity
@Table(name = "cashier_session_opening_float")
@Data
@NoArgsConstructor
public class OpeningFloat {

	@Id
	@Column(name = "cashier_session_id")
	private Long sessionId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "cashier_session_id", foreignKey = @ForeignKey(name = "fk_cashier_session_opening_float_session"))
	private CashierSession session;

	@Column(name = "proposed_float", nullable = false)
	private Double proposedFloat;

	/** The session whose verification left the float. */
	@ManyToOne
	@JoinColumn(name = "source_session_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_cashier_session_opening_float_source"))
	private CashierSession sourceSession;

	/** The opening cash typed - the float proposed (0 when the cashier kept it). */
	@Column(name = "opening_gap", nullable = false)
	private Double openingGap;
}
