package com.digithink.zsretail.erp.api;

/**
 * 3.1.1 (store orders): what the job POST_STORE_ORDERS runs (ErpSyncJobRunner). Implemented on a head office with
 * headoffice.storeorders.post=ERP only (HoStoreOrderPosting): it posts the stores' orders waiting (SENT, ERROR) into the
 * ERP as sales orders. Absent everywhere else: the runner then answers with a warning.
 */
public interface ErpStoreOrderPosting {

	/** One run. Throws ErpSyncWarningException when an order could not be posted (it stays in ERROR, retried next run). */
	void postStoreOrders();
}
