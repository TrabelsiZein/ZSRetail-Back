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

import com.digithink.zsretail.config.ApplicationModeService;
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
 * <p>
 * ERP catalogue, step 3: on a head office whose catalogue only comes from the ERP
 * ({@link ApplicationModeService#isErpCatalogueOnly()}) only the four catalogue imports are offered
 * ({@link #OFFERED_WHEN_CATALOGUE_ONLY}); every other job is treated like the exports ({@link #refusedTypes()}). Any
 * other head office keeps {@link #NOT_ON_HEAD_OFFICE}. Invoices from the ERP: IMPORT_SUPPLY_INVOICES
 * ({@link #OFFERED_WITH_ERP_SUPPLY}) is offered only on that head office with headoffice.supply.source=ERP, refused on
 * every other one.
 */
@Service
@ConditionalOnHeadOffice
@Log4j2
public class HeadOfficeErpJobs {

	/** Never run, never offered on a head office. */
	public static final Set<ErpSyncJobType> NOT_ON_HEAD_OFFICE = Collections.unmodifiableSet(EnumSet.of(
			ErpSyncJobType.EXPORT_CUSTOMERS, ErpSyncJobType.EXPORT_TICKETS, ErpSyncJobType.EXPORT_RETURNS,
			ErpSyncJobType.EXPORT_SESSIONS, ErpSyncJobType.IMPORT_SALES_PRICES_AND_DISCOUNTS));

	/**
	 * ERP catalogue, step 3: the only jobs offered on a head office whose catalogue only comes from the ERP; every other
	 * job type is refused there. Release 2.2: one job, SYNC_CATALOGUE ("Sync catalogue": families, sub-families, the items
	 * of every point de stock, barcodes, in one run); the four separate catalogue jobs are refused there (switched off at
	 * each start, hidden, not run).
	 */
	public static final Set<ErpSyncJobType> OFFERED_WHEN_CATALOGUE_ONLY = Collections
			.unmodifiableSet(EnumSet.of(ErpSyncJobType.SYNC_CATALOGUE));

	/**
	 * Invoices from the ERP: offered only on a head office whose catalogue only comes from the ERP with
	 * headoffice.supply.source=ERP; refused on every other head office (and never seeded on a store).
	 */
	public static final Set<ErpSyncJobType> OFFERED_WITH_ERP_SUPPLY = Collections
			.unmodifiableSet(EnumSet.of(ErpSyncJobType.IMPORT_SUPPLY_INVOICES));

	static final String REFUSED = "This ERP job does not run on a head office";

	private final ErpSyncJobRepository jobs;

	/** The job types refused on this head office, chosen once by mode. */
	private final Set<ErpSyncJobType> refused;

	/**
	 * The data initializer is a parameter only so that it runs first: it seeds the ERP jobs (two exports enabled) in
	 * its own {@code @PostConstruct}, and this one switches them off before any scheduled task starts. The mode chooses
	 * the refused jobs (ERP catalogue, step 3).
	 */
	@Autowired
	public HeadOfficeErpJobs(ErpSyncJobRepository jobs, ZZDataInitializer seededFirst, ApplicationModeService mode) {
		this(jobs, mode.isErpCatalogueOnly(), mode.isSupplyFromErpSource());
	}

	/** Without the mode: the refused jobs of a head office whose ERP owns all three, or without an ERP. */
	public HeadOfficeErpJobs(ErpSyncJobRepository jobs, ZZDataInitializer seededFirst) {
		this(jobs, false);
	}

	/** With the repository only: used by the tests. */
	public HeadOfficeErpJobs(ErpSyncJobRepository jobs) {
		this(jobs, false);
	}

	/** catalogueOnly: a head office whose catalogue only comes from the ERP. Used by the tests too. */
	public HeadOfficeErpJobs(ErpSyncJobRepository jobs, boolean catalogueOnly) {
		this(jobs, catalogueOnly, false);
	}

	/**
	 * catalogueOnly as above; supplyFromErp: headoffice.supply.source=ERP (accepted only with catalogueOnly), which adds
	 * {@link #OFFERED_WITH_ERP_SUPPLY}. Used by the tests too.
	 */
	public HeadOfficeErpJobs(ErpSyncJobRepository jobs, boolean catalogueOnly, boolean supplyFromErp) {
		this.jobs = jobs;
		if (catalogueOnly) {
			Set<ErpSyncJobType> offered = EnumSet.copyOf(OFFERED_WHEN_CATALOGUE_ONLY);
			if (supplyFromErp) {
				offered.addAll(OFFERED_WITH_ERP_SUPPLY);
			}
			this.refused = Collections.unmodifiableSet(EnumSet.complementOf(EnumSet.copyOf(offered)));
		} else {
			Set<ErpSyncJobType> refusedHere = EnumSet.copyOf(NOT_ON_HEAD_OFFICE);
			refusedHere.addAll(OFFERED_WITH_ERP_SUPPLY);
			refusedHere.add(ErpSyncJobType.SYNC_CATALOGUE); // release 2.2: only with the catalogue from the ERP
			this.refused = Collections.unmodifiableSet(refusedHere);
		}
	}

	/** The jobs refused on any head office whatever its mode ({@link #NOT_ON_HEAD_OFFICE}). See {@link #refuses}. */
	public static boolean isRefused(ErpSyncJobType type) {
		return type != null && NOT_ON_HEAD_OFFICE.contains(type);
	}

	/**
	 * The job types refused on this head office: {@link #NOT_ON_HEAD_OFFICE}, or on a head office whose catalogue only
	 * comes from the ERP every type but {@link #OFFERED_WHEN_CATALOGUE_ONLY}.
	 */
	public Set<ErpSyncJobType> refusedTypes() {
		return refused;
	}

	/** True when this head office never runs this job type. */
	public boolean refuses(ErpSyncJobType type) {
		return type != null && refused.contains(type);
	}

	/** At each start: the refused jobs ({@link #refusedTypes()}) disabled, no next run. Returns how many were switched off. */
	@PostConstruct
	public int switchOff() {
		int switched = 0;
		try {
			for (ErpSyncJobType type : refused) {
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
			log.warn("Head office: ERP jobs not switched off at start ({})", e.toString());
			return switched;
		}
		if (switched > 0) {
			log.info("Head office: {} ERP jobs switched off (never run on this head office: {})", switched, refused);
		}
		return switched;
	}

	/** True when the job with this id exists and is one this head office never runs. */
	public boolean isRefusedJob(Long id) {
		return id != null && jobs.findById(id).map(job -> refuses(job.getJobType())).orElse(false);
	}

	/** The list without the jobs this head office never runs. */
	public <T> List<T> withoutRefused(List<T> views, java.util.function.Function<T, ErpSyncJobType> typeOf) {
		return views.stream().filter(view -> !refuses(typeOf.apply(view))).collect(Collectors.toList());
	}
}
