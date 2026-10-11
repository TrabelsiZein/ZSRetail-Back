package com.digithink.zsretail.holink.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

import com.digithink.zsretail.holink.enumeration.DownRecordStatus;
import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.DataDomain;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;

/**
 * Head office plan, task 3.5: one record received from the head office, per domain and code, with what the store did
 * with it (APPLIED, WAITING, ERROR) and the copy last received, so a record not applied is retried at every cycle
 * without a new change from the head office. Deleted when the head office removes the record for this store.
 */
@Entity
@Table(name = "hol_down_record", uniqueConstraints = @UniqueConstraint(name = "uk_hol_down_record",
		columnNames = { "domain", "record_code" }))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class DownRecord extends _BaseEntity {

	public static final int TEXT_LENGTH = 1000;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private DataDomain domain;

	@Column(name = "record_code", nullable = false, length = 100)
	private String recordCode;

	/** The record's name as received, for the list. */
	@Column(name = "record_name")
	private String recordName;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private DownRecordStatus status;

	/** Why it is WAITING or in ERROR; null when APPLIED. */
	@Column(length = TEXT_LENGTH)
	private String reason;

	/** Information on an APPLIED record, e.g. the group items this store does not have. */
	@Column(length = TEXT_LENGTH)
	private String info;

	/** The copy last received (JSON), applied again by the retries. */
	@Column(columnDefinition = "NVARCHAR(MAX)")
	@ToString.Exclude
	private String payload;

	/** Store clock when this copy was received (a copy received again unchanged does not move it). */
	@Column(name = "received_at")
	private LocalDateTime receivedAt;

	/** Store clock when the status became what it is. */
	@Column(name = "status_since")
	private LocalDateTime statusSince;
}
