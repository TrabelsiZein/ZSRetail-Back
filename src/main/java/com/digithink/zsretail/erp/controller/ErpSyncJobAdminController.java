package com.digithink.zsretail.erp.controller;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronSequenceGenerator;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.digithink.zsretail.erp.dto.ErpJobStatisticsDTO;
import com.digithink.zsretail.erp.dto.ErpSyncJobEnabledDTO;
import com.digithink.zsretail.erp.dto.ErpSyncJobUpdateDTO;
import com.digithink.zsretail.erp.dto.ErpSyncJobViewDTO;
import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.service.ErpSyncCheckpointService;
import com.digithink.zsretail.erp.service.ErpSyncJobRunner;
import com.digithink.zsretail.erp.service.ErpSyncJobService;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;
import com.digithink.zsretail.erp.util.CronExpressionParser;
import com.digithink.zsretail.model.UserAccount;
import com.digithink.zsretail.model.enumeration.Role;
import com.digithink.zsretail.security.CurrentUserProvider;
import com.digithink.zsretail.service.GeneralSetupService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("admin/erp/jobs")
@RequiredArgsConstructor
public class ErpSyncJobAdminController {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpSyncJobAdminController.class);

	private final ErpSyncJobService jobService;
	private final ErpSyncCheckpointService checkpointService;
	private final GeneralSetupService generalSetupService;
	private final CurrentUserProvider currentUserProvider;
	private final ErpSyncJobRunner jobRunner;
	private final com.digithink.zsretail.erp.service.ErpJobStatisticsService statisticsService;

	@GetMapping
	public ResponseEntity<List<ErpSyncJobViewDTO>> getJobs() {
		ensureAdminAccess();

		List<ErpSyncJob> jobs = new ArrayList<>(jobService.findAll());
		jobs.sort(Comparator.comparing(ErpSyncJob::getJobType));
		Map<ErpSyncJobType, String> checkpointCodes = ErpSyncCheckpointService.getCheckpointCodes();

		List<ErpSyncJobViewDTO> response = new ArrayList<>(jobs.size());
		for (ErpSyncJob job : jobs) {
			response.add(mapToDto(job, checkpointCodes));
		}

		return ResponseEntity.ok(response);
	}

	@PostMapping("{id}/run")
	public ResponseEntity<?> runJobNow(@PathVariable Long id) {
		ensureAdminAccess();

		ErpSyncJob job = jobService.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));

		LocalDateTime executionStart = LocalDateTime.now();
		try {
			jobRunner.run(job);
			LocalDateTime executionEnd = LocalDateTime.now();
			// Use execution end time to calculate next run, preserving exact seconds
			LocalDateTime nextRun = computeNextRun(job, executionEnd);
			jobService.markExecution(job, "SUCCESS", executionEnd, nextRun);
			return ResponseEntity.ok(mapToDto(job));
		} catch (ErpSyncWarningException warning) {
			LOGGER.warn("ERP job {} run triggered warning: {}", job.getJobType(), warning.getMessage());
			LocalDateTime executionEnd = LocalDateTime.now();
			LocalDateTime nextRun = computeNextRun(job, executionEnd);
			jobService.markExecution(job, "WARNING", executionEnd, nextRun);
			return ResponseEntity.status(HttpStatus.BAD_REQUEST)
					.body(java.util.Collections.singletonMap("error", warning.getMessage()));
		} catch (Exception ex) {
			LOGGER.error("Failed to execute ERP job {} immediately: {}", job.getJobType(), ex.getMessage(), ex);
			LocalDateTime executionEnd = LocalDateTime.now();
			LocalDateTime nextRun = computeNextRun(job, executionEnd);
			jobService.markExecution(job, "ERROR", executionEnd, nextRun);
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage(), ex);
		}
	}

	@PatchMapping("{id}")
	public ResponseEntity<ErpSyncJobViewDTO> updateEnabled(@PathVariable Long id,
			@RequestBody ErpSyncJobEnabledDTO enabledDto) {
		ensureAdminAccess();

		if (enabledDto.getEnabled() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enabled field is required");
		}

		ErpSyncJob job = jobService.updateEnabled(id, enabledDto.getEnabled());
		LocalDateTime nextRun = job.getEnabled() ? computeNextRun(job, LocalDateTime.now()) : null;
		job.setNextRunAt(nextRun);
		jobService.updateNextRun(job, nextRun);

		return ResponseEntity.ok(mapToDto(job));
	}

	@PutMapping("{id}")
	public ResponseEntity<ErpSyncJobViewDTO> updateJob(@PathVariable Long id,
			@RequestBody ErpSyncJobUpdateDTO updateDto) {
		ensureAdminAccess();

		ErpSyncJob job = jobService.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));

		if (updateDto.getCronExpression() != null) {
			// Validate CRON expression
			try {
				new CronSequenceGenerator(updateDto.getCronExpression());
			} catch (IllegalArgumentException ex) {
				throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
						"Invalid CRON expression: " + ex.getMessage());
			}
		}

		job = jobService.updateJob(id, updateDto.getDescription(), updateDto.getCronExpression(),
				updateDto.getEnabled());

		// Recalculate next run if CRON changed or enabled status changed
		// This calculates the next scheduled run time from NOW (when update happens),
		// not immediately executing the job
		if (updateDto.getCronExpression() != null || updateDto.getEnabled() != null) {
			LocalDateTime now = LocalDateTime.now();
			LocalDateTime nextRun = job.getEnabled() ? computeNextRun(job, now) : null;
			job.setNextRunAt(nextRun);
			jobService.updateNextRun(job, nextRun);
		}

		return ResponseEntity.ok(mapToDto(job));
	}

	@GetMapping("{id}/statistics")
	public ResponseEntity<ErpJobStatisticsDTO> getStatistics(@PathVariable Long id,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate) {
		ensureAdminAccess();

		ErpSyncJob job = jobService.findById(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Job not found"));

		ErpJobStatisticsDTO statistics = statisticsService.getStatistics(job.getJobType(), fromDate, toDate);
		return ResponseEntity.ok(statistics);
	}

	private ErpSyncJobViewDTO mapToDto(ErpSyncJob job) {
		return mapToDto(job, ErpSyncCheckpointService.getCheckpointCodes());
	}

	private ErpSyncJobViewDTO mapToDto(ErpSyncJob job, Map<ErpSyncJobType, String> checkpointCodes) {
		ErpSyncJobViewDTO dto = new ErpSyncJobViewDTO();
		dto.setId(job.getId());
		dto.setJobType(job.getJobType());
		dto.setDescription(job.getDescription());
		dto.setCronExpression(job.getCronExpression());
		dto.setEnabled(job.getEnabled());
		dto.setLastRunAt(job.getLastRunAt());
		dto.setNextRunAt(job.getNextRunAt());
		dto.setLastStatus(job.getLastStatus());

		String checkpointCode = checkpointCodes.get(job.getJobType());
		dto.setCheckpointCode(checkpointCode);
		if (checkpointCode != null) {
			dto.setCheckpointValue(generalSetupService.findValueByCode(checkpointCode));
			checkpointService.resolveLastSync(job.getJobType()).ifPresent(dto::setLastCheckpointAt);
		}

		// Set human-readable CRON description
		if (job.getCronExpression() != null) {
			dto.setCronDescription(CronExpressionParser.toHumanReadable(job.getCronExpression()));
		}

		return dto;
	}

	private LocalDateTime computeNextRun(ErpSyncJob job, LocalDateTime reference) {
		try {
			String cron = job.getCronExpression();
			// Check if it's an interval-based schedule that should preserve seconds
			LocalDateTime nextRun = computeNextRunWithSecondsPreserved(cron, reference);
			if (nextRun != null) {
				return nextRun;
			}
			
			// For time-based schedules, use standard CRON calculation
			CronSequenceGenerator generator = new CronSequenceGenerator(cron);
			Instant nextInstant = generator.next(toDate(reference)).toInstant();
			return LocalDateTime.ofInstant(nextInstant, ZoneId.systemDefault());
		} catch (IllegalArgumentException ex) {
			LOGGER.error("Invalid cron expression '{}' for job {}. {}", job.getCronExpression(), job.getJobType(),
					ex.getMessage());
			return reference.plusMinutes(10);
		}
	}

	/**
	 * For interval-based CRON expressions (every N minutes/hours), calculate next run
	 * by adding the interval to the reference time, preserving exact seconds.
	 * Returns null if not an interval-based schedule.
	 */
	private LocalDateTime computeNextRunWithSecondsPreserved(String cron, LocalDateTime reference) {
		if (cron == null) {
			return null;
		}
		
		String[] parts = cron.trim().split("\\s+");
		if (parts.length < 5) {
			return null;
		}
		
		// Handle 6-field (with seconds) or 5-field CRON
		boolean hasSeconds = parts.length == 6;
		int secondIndex = hasSeconds ? 0 : -1;
		int minuteIndex = hasSeconds ? 1 : 0;
		int hourIndex = hasSeconds ? 2 : 1;
		
		String second = hasSeconds ? parts[secondIndex] : "0";
		String minute = parts[minuteIndex];
		String hour = parts[hourIndex];
		
		// Every N minutes: "0 */N * * * *" or "*/N * * * *"
		if (minute.startsWith("*/") && hour.equals("*")) {
			try {
				int interval = Integer.parseInt(minute.substring(2));
				// Add interval minutes to reference time, preserving seconds
				return reference.plusMinutes(interval);
			} catch (NumberFormatException e) {
				// Not a valid interval, fall through to standard calculation
			}
		}
		
		// Every N hours: "0 M */N * * *" or "M */N * * *"
		if (hour.startsWith("*/")) {
			try {
				int interval = Integer.parseInt(hour.substring(2));
				// Add interval hours to reference time, preserving seconds
				return reference.plusHours(interval);
			} catch (NumberFormatException e) {
				// Not a valid interval, fall through to standard calculation
			}
		}
		
		// Every N seconds: "*/N * * * * *"
		if (hasSeconds && second.startsWith("*/") && minute.equals("*") && hour.equals("*")) {
			try {
				int interval = Integer.parseInt(second.substring(2));
				// Add interval seconds to reference time
				return reference.plusSeconds(interval);
			} catch (NumberFormatException e) {
				// Not a valid interval, fall through to standard calculation
			}
		}
		
		// Not an interval-based schedule, use standard CRON calculation
		return null;
	}

	private java.util.Date toDate(LocalDateTime dateTime) {
		return java.util.Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
	}

	private void ensureAdminAccess() {
		UserAccount currentUser = currentUserProvider.getCurrentUser();
		if (currentUser == null || currentUser.getRole() != Role.ADMIN) {
			throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin privileges required");
		}
	}
}
