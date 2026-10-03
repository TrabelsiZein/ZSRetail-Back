package com.digithink.zsretail.holink.service;

import java.util.List;

import com.digithink.zsretail.holink.dto.DownApplyResult;
import com.digithink.zsretail.model.enumeration.DataDomain;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * The store side of one domain on the copies down mechanism (step 3): one bean per domain owned by the head office
 * (promotions in step 3; loyalty, catalogue and shipments later), carrying
 * {@code @ConditionalOnHeadOfficeOwned(<domain>)}. {@link CopiesDownPuller} gives it each page pulled. A later domain
 * adds one class.
 */
public interface DownHandler {

	DataDomain getDomain();

	/**
	 * Applies one page: saves each record by its business code and handles each removed code (a code the store does not
	 * have is ignored). Each record on its own: one that cannot be applied is counted as waiting or in error and the
	 * others go on. Applying the same page twice changes nothing. Throws only when the store cannot work at all (e.g.
	 * the database is down): the page is then pulled again.
	 */
	DownApplyResult apply(List<JsonNode> records, List<String> removed);

	/**
	 * Sets inactive the active local records of the domain (made at the store before the domain was owned by the head
	 * office; they are kept, not deleted), at every cycle before the pull; returns how many. None by default.
	 */
	default int deactivateLocal() {
		return 0;
	}

	/** Applies again the records received earlier and not applied yet, at every cycle; none by default. */
	default DownApplyResult retry() {
		return DownApplyResult.none();
	}
}
