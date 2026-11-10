package com.digithink.zsretail.store.holink.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import com.digithink.zsretail.core.model._BaseEntity;
import com.digithink.zsretail.store.holink.enumeration.LinkJobResult;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * A job of the head office link as the store remembers it (task 2.6): the frequency saved from the page and the last
 * run. Created at the first run or the first change. Which jobs exist is decided by the settings (the job beans), not
 * by this table: a row whose job no longer exists is not listed.
 */
@Entity
@Table(name = "hol_job", uniqueConstraints = @UniqueConstraint(name = "uk_hol_job_code", columnNames = "code"))
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
public class LinkJobState extends _BaseEntity {

	public static final int MESSAGE_LENGTH = 1000;

	/** The job's code, e.g. HEARTBEAT, SALES_PUSH. */
	@Column(nullable = false, length = 40)
	private String code;

	/** Frequency saved from the page; null: the properties value. */
	@Column(name = "interval_seconds")
	private Long intervalSeconds;

	@Column(name = "last_run_at")
	private LocalDateTime lastRunAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "last_result", length = 10)
	private LinkJobResult lastResult;

	@Column(name = "last_message", length = MESSAGE_LENGTH)
	private String lastMessage;

	@Column(name = "last_duration_ms")
	private Long lastDurationMs;
}
