package com.digithink.zsretail.erp.api;

/**
 * Release 2.2: the job SYNC_CATALOGUE, the whole catalogue in one run (families, sub-families, the items of every point
 * de stock, barcodes), on a head office whose catalogue comes from the ERP (erp/engine/CatalogueJob, with a connector
 * that has the ability HEAD_OFFICE_STOCK_POINTS). Absent everywhere else: the runner answers a warning.
 */
public interface ErpCatalogueSync {

	/**
	 * One run. Throws ErpSyncWarningException with the reason when a part waited or was refused (the parts before it stay
	 * saved), any other exception when a part failed.
	 */
	void syncCatalogue();
}
