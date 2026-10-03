package com.digithink.zsretail.headoffice.service;

import java.util.Collections;
import java.util.List;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.erp.dto.ErpSyncJobViewDTO;
import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.service.ErpSyncWarningException;

/**
 * Head office plan, task 3.4: keeps the ERP jobs of {@link HeadOfficeErpJobs#NOT_ON_HEAD_OFFICE} out of a head office
 * without changing erp/ (an aspect, head office only; on a store it does not exist):
 * <ul>
 * <li>the runner refuses them ({@code ErpSyncJobRunner.run}, used by the scheduler and by "run now"): a warning, the
 * job is not run;</li>
 * <li>{@code GET admin/erp/jobs} does not list them;</li>
 * <li>{@code POST admin/erp/jobs/{id}/run}, {@code PATCH} and {@code PUT admin/erp/jobs/{id}} and
 * {@code GET admin/erp/jobs/{id}/statistics} answer 404 {"error": "This ERP job does not run on a head office"} for
 * them, so they can be neither enabled nor run.</li>
 * </ul>
 * The store's ERP jobs page therefore works on a head office as it is, without those jobs.
 */
@Aspect
@Component
@ConditionalOnHeadOffice
public class HeadOfficeErpGuard {

	private final HeadOfficeErpJobs jobs;

	public HeadOfficeErpGuard(HeadOfficeErpJobs jobs) {
		this.jobs = jobs;
	}

	@Around("execution(* com.digithink.zsretail.erp.service.ErpSyncJobRunner.run(..)) && args(job)")
	public Object refuseRun(ProceedingJoinPoint call, ErpSyncJob job) throws Throwable {
		if (job != null && HeadOfficeErpJobs.isRefused(job.getJobType())) {
			throw new ErpSyncWarningException(HeadOfficeErpJobs.REFUSED + ": " + job.getJobType());
		}
		return call.proceed();
	}

	@SuppressWarnings("unchecked")
	@Around("execution(* com.digithink.zsretail.erp.controller.ErpSyncJobAdminController.getJobs())")
	public Object hideInList(ProceedingJoinPoint call) throws Throwable {
		Object answer = call.proceed();
		if (answer instanceof ResponseEntity && ((ResponseEntity<?>) answer).getBody() instanceof List) {
			ResponseEntity<?> entity = (ResponseEntity<?>) answer;
			List<ErpSyncJobViewDTO> views = (List<ErpSyncJobViewDTO>) entity.getBody();
			return ResponseEntity.status(entity.getStatusCode()).headers(entity.getHeaders())
					.body(jobs.withoutRefused(views, ErpSyncJobViewDTO::getJobType));
		}
		return answer;
	}

	@Around("(execution(* com.digithink.zsretail.erp.controller.ErpSyncJobAdminController.runJobNow(..))"
			+ " || execution(* com.digithink.zsretail.erp.controller.ErpSyncJobAdminController.updateEnabled(..))"
			+ " || execution(* com.digithink.zsretail.erp.controller.ErpSyncJobAdminController.updateJob(..))"
			+ " || execution(* com.digithink.zsretail.erp.controller.ErpSyncJobAdminController.getStatistics(..)))"
			+ " && args(id, ..)")
	public Object refuseById(ProceedingJoinPoint call, Long id) throws Throwable {
		if (jobs.isRefusedJob(id)) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND)
					.body(Collections.singletonMap("error", HeadOfficeErpJobs.REFUSED));
		}
		return call.proceed();
	}
}
