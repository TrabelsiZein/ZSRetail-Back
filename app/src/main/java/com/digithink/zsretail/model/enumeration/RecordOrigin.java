package com.digithink.zsretail.model.enumeration;

/**
 * Where a record of a store comes from (head office design 2.2, "Local additions"; step 3 for promotions). A record
 * that came from the head office is read-only at the store: only the pull job writes it. Null in the database means
 * LOCAL (rows written before the column existed).
 */
public enum RecordOrigin {

	/** Made at this store. */
	LOCAL,

	/** Received from the head office (copies down). */
	HEAD_OFFICE
}
