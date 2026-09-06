package com.digithink.zsretail.erp.scheduler;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronSequenceGenerator;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.service.ErpSyncJobRunner;
import com.digithink.zsretail.erp.service.ErpSyncJobService;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
	    name = "erp.sync.enabled",
	    havingValue = "true",
	    matchIfMissing = false
	)
public class ErpSyncScheduler {

	private static final Logger LOGGER = LoggerFactory.getLogger(ErpSyncScheduler.class);

	private final ErpSyncJobService jobService;
	private final ErpSyncJobRunner jobRunner;

	@Scheduled(fixedDelayString = "${erp.sync.scheduler.delay:60000}")
	public void pollJobs() {
		List<ErpSyncJob> jobs = jobService.findEnabledJobs();
		if (jobs.isEmpty()) {
			return;
		}

		LocalDateTime now = LocalDateTime.now();
		for (ErpSyncJob job : jobs) {
			LocalDateTime nextRun = job.getNextRunAt();
			if (nextRun == null) {
				nextRun = computeNextRun(job, now);
				jobService.updateNextRun(job, nextRun);
				continue;
			}

			if (!nextRun.isAfter(now)) {
				runJob(job, now);
			}
		}
	}

	private void runJob(ErpSyncJob job, LocalDateTime reference) {
		String statusMessage = "SUCCESS";
		LocalDateTime executionEnd = reference;
		try {
			jobRunner.run(job);
			executionEnd = LocalDateTime.now();
		} catch (ErpSyncWarningException warning) {
			statusMessage = "WARNING: " + warning.getMessage();
			executionEnd = LocalDateTime.now();
			LOGGER.warn("ERP sync job {} skipped: {}", job.getJobType(), warning.getMessage());
		} catch (Exception ex) {
			statusMessage = "ERROR: " + ex.getMessage();
			executionEnd = LocalDateTime.now();
			LOGGER.error("ERP sync job {} failed: {}", job.getJobType(), ex.getMessage(), ex);
		}

		// Use execution end time to calculate next run, preserving exact seconds
		LocalDateTime next = computeNextRun(job, executionEnd);
		jobService.markExecution(job, statusMessage, executionEnd, next);
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
	 * For interval-based CRON expressions (every N minutes/hours), calculate next
	 * run by adding the interval to the reference time, preserving exact seconds.
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
		int minuteIndex = hasSeconds ? 1 : 0;
		int hourIndex = hasSeconds ? 2 : 1;

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
		if (hasSeconds) {
			String second = parts[0];
			if (second.startsWith("*/") && minute.equals("*") && hour.equals("*")) {
				try {
					int interval = Integer.parseInt(second.substring(2));
					// Add interval seconds to reference time
					return reference.plusSeconds(interval);
				} catch (NumberFormatException e) {
					// Not a valid interval, fall through to standard calculation
				}
			}
		}

		// Not an interval-based schedule, use standard CRON calculation
		return null;
	}

	private java.util.Date toDate(LocalDateTime dateTime) {
		return java.util.Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
	}
}
