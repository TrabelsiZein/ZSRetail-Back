package com.digithink.zsretail.erp.dto;

import java.time.LocalDateTime;

import com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.erp.enumeration.ErpSyncOperation;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ErpCommunicationViewDTO {

	private Long id;
	private ErpSyncOperation operation;
	private ErpCommunicationStatus status;
	private String url;
	private String errorMessage;
	private LocalDateTime startedAt;
	private LocalDateTime completedAt;
	private Long durationMs;
	private String requestPayload;
	private String responsePayload;
}


