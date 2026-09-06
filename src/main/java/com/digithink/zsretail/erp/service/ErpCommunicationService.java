package com.digithink.zsretail.erp.service;

import java.time.Duration;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.erp.enumeration.ErpCommunicationStatus;
import com.digithink.zsretail.erp.enumeration.ErpSyncOperation;
import com.digithink.zsretail.erp.enumeration.ErpTrackingLevel;
import com.digithink.zsretail.erp.model.ErpCommunication;
import com.digithink.zsretail.erp.repository.ErpCommunicationRepository;
import com.digithink.zsretail.service.GeneralSetupService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ErpCommunicationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpCommunicationService.class);
	public static final String GENERAL_SETUP_TRACKING_KEY = "ERP_SYNC_TRACKING_LEVEL";

	private final ErpCommunicationRepository communicationRepository;
	private final GeneralSetupService generalSetupService;
	private final ObjectMapper objectMapper;

	public void logOperation(ErpSyncOperation operation, Object request, Object response, ErpCommunicationStatus status,
			String url, String errorMessage, LocalDateTime startedAt, LocalDateTime completedAt) {

		ErpTrackingLevel level = resolveTrackingLevel();
		if (level == ErpTrackingLevel.ERRORS_ONLY && status != ErpCommunicationStatus.ERROR) {
			return;
		}
		if (level == ErpTrackingLevel.ERRORS_AND_WARNINGS && status == ErpCommunicationStatus.SUCCESS) {
			return;
		}

		ErpCommunication entry = new ErpCommunication();
		entry.setOperation(operation);
		entry.setStatus(status);
		entry.setRequestPayload(toJsonSafely(request));
		entry.setResponsePayload(toJsonSafely(response));
		entry.setUrl(url);
		entry.setErrorMessage(errorMessage);
		entry.setStartedAt(startedAt != null ? startedAt : LocalDateTime.now());
		entry.setCompletedAt(completedAt != null ? completedAt : LocalDateTime.now());
		entry.setDurationMs(calculateDuration(entry.getStartedAt(), entry.getCompletedAt()));
		entry.setCreatedBy("System");
		entry.setUpdatedBy("System");

		communicationRepository.save(entry);
	}

	private ErpTrackingLevel resolveTrackingLevel() {
		String value = generalSetupService.findValueByCode(GENERAL_SETUP_TRACKING_KEY);
		if (value == null) {
			return ErpTrackingLevel.ERRORS_ONLY;
		}
		try {
			return ErpTrackingLevel.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException ex) {
			LOGGER.warn("Unknown ERP tracking level '{}', defaulting to ERRORS_ONLY", value);
			return ErpTrackingLevel.ERRORS_ONLY;
		}
	}

	private String toJsonSafely(Object value) {
		if (value == null) {
			return null;
		}
		// For simple String values (like URLs for GET requests), store as-is without JSON quotes
		if (value instanceof String) {
			return (String) value;
		}
		try {
			return objectMapper.writeValueAsString(value);
		} catch (JsonProcessingException ex) {
			LOGGER.warn("Unable to serialize ERP payload: {}", ex.getMessage());
			return value.toString();
		}
	}

	private Long calculateDuration(LocalDateTime start, LocalDateTime end) {
		if (start == null || end == null) {
			return null;
		}
		return Duration.between(start, end).toMillis();
	}
}
