package com.digithink.zsretail.store.rfid.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * RFID step 3: one POS machine of the store (the PC a cashier works on), known by the address its requests come from,
 * and the RFID reader it reads with. The back end finds the machine of a request by its address
 * (RfidAddresses.normalise of the request's remote address), so a POS machine needs a fixed address (a DHCP
 * reservation): docs/modules/rfid.md. active (of _BaseEntity): an inactive machine cannot read. The rules:
 * PosMachineService.
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

	/** The machine's address as the back end sees it, normalised (RfidAddresses.normalise); unique. */
	@Column(name = "ip_address", nullable = false, length = 45)
	private String ipAddress;

	/** Its reader; none: the machine cannot read yet. */
	@ManyToOne
	@JoinColumn(name = "reader_id", foreignKey = @ForeignKey(name = "fk_pos_machine_reader"))
	private RfidReader reader;
}
