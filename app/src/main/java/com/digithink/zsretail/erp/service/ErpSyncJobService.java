package com.digithink.zsretail.erp.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;

import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.repository._BaseRepository;
import com.digithink.zsretail.service._BaseService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ErpSyncJobService extends _BaseService<ErpSyncJob, Long> {

	private final ErpSyncJobRepository jobRepository;

	@Override
	protected _BaseRepository<ErpSyncJob, Long> getRepository() {
		return jobRepository;
	}

	public List<ErpSyncJob> findEnabledJobs() {
		return jobRepository.findByEnabledTrueOrderByJobTypeAsc();
	}

	public void markExecution(ErpSyncJob job, String status, LocalDateTime nextRun) {
		markExecution(job, status, LocalDateTime.now(), nextRun);
	}

	/** Length of erp_sync_job.last_status (ErpSyncJob.lastStatus). */
	static final int LAST_STATUS_LENGTH = 100;

	public void markExecution(ErpSyncJob job, String status, LocalDateTime executionTime, LocalDateTime nextRun) {
		job.setLastRunAt(executionTime);
		// Cut to the column: a longer status ("ERROR: " + a long message) failed the save (SQL Server 2628), so the
		// status was lost and the other due jobs of that cycle were skipped
		job.setLastStatus(status != null && status.length() > LAST_STATUS_LENGTH ? status.substring(0, LAST_STATUS_LENGTH)
				: status);
		job.setNextRunAt(nextRun);
		job.setUpdatedAt(LocalDateTime.now());
		job.setUpdatedBy("System");
		jobRepository.save(job);
	}

	public void updateNextRun(ErpSyncJob job, LocalDateTime nextRun) {
		job.setNextRunAt(nextRun);
		job.setUpdatedAt(LocalDateTime.now());
		job.setUpdatedBy("System");
		jobRepository.save(job);
	}

	public ErpSyncJob updateEnabled(Long jobId, Boolean enabled) {
		ErpSyncJob job = findById(jobId)
				.orElseThrow(() -> new RuntimeException("Job not found with id: " + jobId));
		job.setEnabled(enabled);
		job.setUpdatedAt(LocalDateTime.now());
		job.setUpdatedBy(currentUserProvider.getCurrentUserName());
		return jobRepository.save(job);
	}

	public ErpSyncJob updateJob(Long jobId, String description, String cronExpression, Boolean enabled) {
		ErpSyncJob job = findById(jobId)
				.orElseThrow(() -> new RuntimeException("Job not found with id: " + jobId));
		if (description != null) {
			job.setDescription(description);
		}
		if (cronExpression != null) {
			job.setCronExpression(cronExpression);
		}
		if (enabled != null) {
			job.setEnabled(enabled);
		}
		job.setUpdatedAt(LocalDateTime.now());
		job.setUpdatedBy(currentUserProvider.getCurrentUserName());
		return jobRepository.save(job);
	}
}
