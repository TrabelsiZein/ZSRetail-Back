package com.digithink.zsretail.erp.api;

/**
 * A run that did nothing wrong but did not do its work (it waited, was refused because a run is already running, or kept
 * something back): the job's status is WARNING and "Run now" shows the reason. Code cleanup step 3: moved from
 * erp/service to erp/spi (erp/api, project erp-api, since code cleanup step 6.2), the engine and the connectors both
 * throw it.
 */
public class ErpSyncWarningException extends RuntimeException {

	public ErpSyncWarningException(String message) {
		super(message);
	}
}
