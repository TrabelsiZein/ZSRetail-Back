package com.digithink.zsretail.erp.model;

import java.time.LocalDateTime;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.EnumType;
import javax.persistence.Enumerated;
import javax.persistence.Table;
import javax.validation.constraints.NotBlank;

import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.model._BaseEntity;

/**
 * Defines a scheduled ERP synchronization job and its execution metadata.
 */
@Entity
@Table(name = "ERP_SYNC_JOB")
public class ErpSyncJob extends _BaseEntity {

	@Enumerated(EnumType.STRING)
	@Column(name = "job_type", nullable = false, length = 64)
	private ErpSyncJobType jobType;

	@NotBlank
	@Column(name = "cron_expression", nullable = false, length = 64)
	private String cronExpression;

	@Column(name = "description", length = 256)
	private String description;

	@Column(name = "enabled", nullable = false)
	private Boolean enabled = Boolean.TRUE;

	@Column(name = "last_run_at")
	private LocalDateTime lastRunAt;

	@Column(name = "next_run_at")
	private LocalDateTime nextRunAt;

	@Column(name = "last_status", length = 100)
	private String lastStatus;

	public ErpSyncJobType getJobType() {
		return jobType;
	}

	public void setJobType(ErpSyncJobType jobType) {
		this.jobType = jobType;
	}

	public String getCronExpression() {
		return cronExpression;
	}

	public void setCronExpression(String cronExpression) {
		this.cronExpression = cronExpression;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public Boolean getEnabled() {
		return enabled;
	}

	public void setEnabled(Boolean enabled) {
		this.enabled = enabled;
	}

	public LocalDateTime getLastRunAt() {
		return lastRunAt;
	}

	public void setLastRunAt(LocalDateTime lastRunAt) {
		this.lastRunAt = lastRunAt;
	}

	public LocalDateTime getNextRunAt() {
		return nextRunAt;
	}

	public void setNextRunAt(LocalDateTime nextRunAt) {
		this.nextRunAt = nextRunAt;
	}

	public String getLastStatus() {
		return lastStatus;
	}

	public void setLastStatus(String lastStatus) {
		this.lastStatus = lastStatus;
	}
}
