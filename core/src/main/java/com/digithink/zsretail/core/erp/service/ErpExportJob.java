package com.digithink.zsretail.core.erp.service;

import com.digithink.zsretail.core.erp.enumeration.ErpSyncJobType;

/**
 * An ERP job that sends the store's documents to the ERP (tickets, returns, sessions): the ERP job runner (core) calls
 * the one of the job's type. Code cleanup step 5 (knot d): the runner no longer names the store's export services.
 */
public interface ErpExportJob {

	/** The job type this export answers. */
	ErpSyncJobType jobType();

	/** One run of the export. */
	void export();
}
