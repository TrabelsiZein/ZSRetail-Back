package com.digithink.zsretail.store.rfid.model;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * RFID step 3: one RFID reader of the store (Hopeland HZ100, TCP/IP), read on its antenna 1. The table exists in every
 * store database, empty without rfid.enabled=true (docs/modules/rfid.md). active (of _BaseEntity): an inactive reader is
 * never read. The rules: RfidReaderService.
 */
@Entity
@Table(name = "rfid_reader", uniqueConstraints = {
		@UniqueConstraint(name = "uk_rfid_reader_name", columnNames = "name"),
		@UniqueConstraint(name = "uk_rfid_reader_address", columnNames = { "ip_address", "port" }) })
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class RfidReader extends _BaseEntity {

	public static final int MIN_POWER = 0;
	/** The highest power accepted until the vendor's limit for the HZ100 is known. */
	public static final int MAX_POWER = 30;
	public static final int DEFAULT_POWER = 30;
	public static final int MIN_READ_TIME_MS = 100;
	public static final int MAX_READ_TIME_MS = 10000;
	public static final int DEFAULT_READ_TIME_MS = 1500;

	/** Unique, any case (e.g. "Caisse 1"). */
	@Column(name = "name", nullable = false, length = 100)
	private String name;

	/** IPv4 address of the reader on the store's network (e.g. 192.168.100.200). */
	@Column(name = "ip_address", nullable = false, length = 45)
	private String ipAddress;

	/** TCP port of the reader, 1 to 65535 (9090 on the HZ100). Unique with the address. */
	@Column(name = "port", nullable = false)
	private Integer port;

	/** Reading power of antenna 1, MIN_POWER to MAX_POWER; set on the reader only when it differs. */
	@Column(name = "power", nullable = false)
	private Integer power = DEFAULT_POWER;

	/** How long a read lasts, in milliseconds, MIN_READ_TIME_MS to MAX_READ_TIME_MS. */
	@Column(name = "read_time_ms", nullable = false)
	private Integer readTimeMs = DEFAULT_READ_TIME_MS;
}
