package com.digithink.zsretail.headoffice.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.PostConstruct;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.config.ConditionalOnHeadOffice;
import com.digithink.zsretail.erp.enumeration.ErpSyncJobType;
import com.digithink.zsretail.erp.model.ErpSyncJob;
import com.digithink.zsretail.erp.repository.ErpSyncJobRepository;
import com.digithink.zsretail.service.ZZDataInitializer;

import lombok.extern.log4j.Log4j2;

/**
 * Head office plan, task 3.4: the ERP jobs a head office never runs. A head office imports from its ERP (items,
 * families, sub-families, barcodes, locations, deletions; customers if wanted) and exports nothing: it has no ticket,
 * return or session, and a customer created at the head office must not reach the ERP. The price and discount import
 * is left out too: it is filtered on the responsibility center of one location, so it would give one store's prices,
 * and a head office does not sell. Nothing in erp/ is changed: these jobs are switched off here at each start,
 * hidden from the ERP jobs API and refused by the runner ({@link HeadOfficeErpGuard}).
 */
@Service
@ConditionalOnHeadOffice
@Log4j2
public class HeadOfficeErpJobs {

	/** Never run, never offered on a head office. */
	public static final Set<ErpSyncJobType> NOT_ON_HEAD_OFFICE = Collections.unmodifiableSet(EnumSet.of(
			ErpSyncJobType.EXPORT_CUSTOMERS, ErpSyncJobType.EXPORT_TICKETS, ErpSyncJobType.EXPORT_RETURNS,
			ErpSyncJobType.EXPORT_SESSIONS, ErpSyncJobType.IMPORT_SALES_PRICES_AND_DISCOUNTS));

	static final String REFUSED = "This ERP job does not run on a head office";

	private final ErpSyncJobRepository jobs;

	/**
	 * The data initializer is a parameter only so that it runs first: it seeds the ERP jobs (two exports enabled) in
	 * its own {@code @PostConstruct}, and this one switches them off before any scheduled task starts.
	 */
	@Autowired
	public HeadOfficeErpJobs(ErpSyncJobRepository jobs, ZZDataInitializer seededFirst) {
		this.jobs = jobs;
	}

	/** With the repository only: used by the tests. */
	public HeadOfficeErpJobs(ErpSyncJobRepository jobs) {
		this.jobs = jobs;
	}

	public static boolean isRefused(ErpSyncJobType type) {
		return type != null && NOT_ON_HEAD_OFFICE.contains(type);
	}

	/** At each start: the jobs of {@link #NOT_ON_HEAD_OFFICE} disabled, no next run. Returns how many were switched off. */
	@PostConstruct
	public int switchOff() {
		int switched = 0;
		try {
			for (ErpSyncJobType type : NOT_ON_HEAD_OFFICE) {
				Optional<ErpSyncJob> job = jobs.findByJobType(type);
				if (job.isPresent() && (!Boolean.FALSE.equals(job.get().getEnabled()) || job.get().getNextRunAt() != null)) {
					job.get().setEnabled(false);
					job.get().setNextRunAt(null);
					jobs.save(job.get());
					switched++;
				}
			}
		} catch (RuntimeException e) {
			// The runner refuses them anyway (HeadOfficeErpGuard)
			log.warn("Head office: ERP export jobs not switched off at start ({})", e.toString());
			return switched;
		}
		if (switched > 0) {
			log.info("Head office: {} ERP jobs switched off (a head office exports nothing to the ERP: {})", switched,
					NOT_ON_HEAD_OFFICE);
		}
		return switched;
	}

	/** True when the job with this id exists and is one a head office never runs. */
	public boolean isRefusedJob(Long id) {
		return id != null && jobs.findById(id).map(job -> isRefused(job.getJobType())).orElse(false);
	}

	/** The list without the jobs a head office never runs. */
	public <T> List<T> withoutRefused(List<T> views, java.util.function.Function<T, ErpSyncJobType> typeOf) {
		return views.stream().filter(view -> !isRefused(typeOf.apply(view))).collect(Collectors.toList());
	}
}
