package com.digithink.zsretail.erp.spi;

/**
 * Code cleanup step 3: the settings of the catalogue engine, given by the connector from its own keys (navpospages:
 * packet-size, dry-run, default-vat, unchanged). Read at each run: a value changed in the settings object is seen at the
 * next call.
 */
public interface CatalogueSettings {

	/** Rows applied per transaction (and families or sub-families handed per run). */
	int packetSize();

	/** Read, compare and summarise only: nothing handed, no row of a point written, the state table not written. */
	boolean dryRun();

	/** The VAT of the items read (the stored rows of a point carry none). */
	Integer defaultVat();
}
