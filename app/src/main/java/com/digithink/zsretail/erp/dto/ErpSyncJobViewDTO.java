package com.digithink.zsretail.erp.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErpSyncJobViewDTO {

	private Long id;
	private ErpSyncJobType jobType;
	private String description;
	private String cronExpression;
	private Boolean enabled;
	private LocalDateTime lastRunAt;
	private LocalDateTime nextRunAt;
	private String lastStatus;
	private String checkpointCode;
	private String checkpointValue;
	private OffsetDateTime lastCheckpointAt;
	private String cronDescription; // Human-readable CRON description
}

