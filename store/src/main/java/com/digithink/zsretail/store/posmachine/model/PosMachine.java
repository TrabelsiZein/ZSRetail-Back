package com.digithink.zsretail.store.posmachine.model;

import java.util.Set;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.ForeignKey;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.store.rfid.model.RfidReader;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * One POS machine of the store (the PC a cashier works on), known by the address its requests come from (RFID step 3;
 * every store since 3.2.0, POS machines step 1). The back end finds the machine of a request by its address
 * (PosMachineAddresses.normalise of the request's remote address), so a POS machine needs a fixed address (a DHCP
 * reservation): docs/modules/pos-machines.md. active (of _BaseEntity): an inactive machine cannot read RFID. Its RFID
 * reader is RFID's: set only with rfid.enabled=true (the rfid_reader table exists on every store, empty without RFID).
 * The rules: PosMachineService.
 */
@Entity
@Table(name = "pos_machine", uniqueConstraints = {
		@UniqueConstraint(name = "uk_pos_machine_name", columnNames = "name"),
		@UniqueConstraint(name = "uk_pos_machine_ip", columnNames = "ip_address") })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class PosMachine extends _BaseEntity {

	/** Unique, any case (e.g. "Caisse 1"). */
	@Column(name = "name", nullable = false, length = 100)
	private String name;

	/** The machine's address as the back end sees it, normalised (PosMachineAddresses.normalise); unique. */
	@Column(name = "ip_address", nullable = false, length = 45)
	private String ipAddress;

	/** Its RFID reader; none: the machine cannot read yet (always none without RFID). */
	@ManyToOne
	@JoinColumn(name = "reader_id", foreignKey = @ForeignKey(name = "fk_pos_machine_reader"))
	private RfidReader reader;

	/**
	 * 3.2.0, POS machines step 4: "Seulement" (true): only the users of {@link #allowedUserIds} open a session on it,
	 * when the drawer belongs to the POS machine; "Tous les utilisateurs" (false, the default; null on a row Hibernate
	 * added before db/3.2.0 step 15 reads as false). Sent null (an older screen): the machine keeps what it has
	 * (PosMachineService.check): no save turns "Seulement" into "Tous" by itself.
	 */
	@Column(name = "users_restricted")
	private Boolean usersRestricted;

	/**
	 * 3.2.0, POS machines step 4: the users allowed with "Seulement", by their id (pos_machine_user). No foreign key to
	 * user_account: a user is core's and is deleted by core, which knows no store table; a user deleted or deactivated
	 * stops counting and leaves the list at the machine's next save. Sent null: the machine keeps its list. Read with the
	 * machine (a few rows).
	 */
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "pos_machine_user", joinColumns = @JoinColumn(name = "pos_machine_id"),
			foreignKey = @ForeignKey(name = "fk_pos_machine_user_machine"))
	@Column(name = "user_account_id", nullable = false)
	private Set<Long> allowedUserIds;

	/** "Seulement": the list applies (null reads as "Tous"). */
	public boolean restrictsUsers() {
		return Boolean.TRUE.equals(usersRestricted);
	}
}
