package com.digithink.zsretail.store.posmachine.model;

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

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 3.2.0, POS machines step 2: the POS machine a cashier session was opened on, one row per session that has one (none
 * when the PC was not registered, none for the sessions opened before 3.2.0). Its own table of the store
 * (cashier_session_pos_machine, the session's id as its key) rather than a column of cashier_session: CashierSession is
 * core's and a head office has it too, PosMachine is the store's, and core never sees an application; a head office keeps
 * its tables and its copies as they are. The link to the machine, never a copy of its name: a machine renamed shows its
 * new name. The sales and returns get their machine through their session. docs/modules/pos-machines.md.
 */
@Entity
@Table(name = "cashier_session_pos_machine")
@Getter
@Setter
@NoArgsConstructor
public class SessionPosMachine {

	/** The session's id (cashier_session_id). */
	@Id
	private Long sessionId;

	@MapsId
	@OneToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "cashier_session_id", foreignKey = @ForeignKey(name = "fk_cashier_session_pos_machine_session"))
	private CashierSession session;

	@ManyToOne(optional = false)
	@JoinColumn(name = "pos_machine_id", nullable = false,
			foreignKey = @ForeignKey(name = "fk_cashier_session_pos_machine_machine"))
	private PosMachine posMachine;

	public SessionPosMachine(CashierSession session, PosMachine posMachine) {
		this.session = session;
		this.posMachine = posMachine;
	}
}
