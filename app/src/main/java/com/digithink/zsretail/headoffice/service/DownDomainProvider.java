package com.digithink.zsretail.headoffice.service;

import java.util.List;
import java.util.Map;

import com.digithink.zsretail.headoffice.model.Store;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The head office side of one domain on the copies down mechanism (step 3): one bean per domain (promotions in step 3;
 * loyalty, catalogue and shipments later). {@link CopiesDownFeed} answers the pulls with it.
 */
public interface DownDomainProvider {

	DataDomain getDomain();

	/**
	 * The copies of these records for this store, by code: one entry per code that exists and is addressed to the store.
	 * A code without an entry is answered as removed. Called inside a read-only transaction.
	 */
	Map<String, JsonNode> load(Store store, List<String> codes);

	/** Every record that exists now with its targets, by code: the startup backfill gives a change row to those without. */
	Map<String, StoreTargets> currentTargets();
}
