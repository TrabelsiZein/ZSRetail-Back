package com.digithink.zsretail.store.posmachine.model;

import javax.persistence.Column;
import javax.persistence.Entity;
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
}
