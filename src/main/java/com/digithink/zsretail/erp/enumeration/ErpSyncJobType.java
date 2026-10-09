package com.digithink.zsretail.erp.enumeration;

/**
 * Job types representing scheduled ERP synchronization routines.
 */
public enum ErpSyncJobType {
	IMPORT_ITEM_FAMILIES,
	IMPORT_ITEM_SUBFAMILIES,
	IMPORT_ITEMS,
	IMPORT_ITEM_BARCODES,
	IMPORT_LOCATIONS,
	IMPORT_CUSTOMERS,
	IMPORT_SALES_PRICES_AND_DISCOUNTS,
	EXPORT_CUSTOMERS,
	EXPORT_TICKETS,
	EXPORT_RETURNS,
	EXPORT_SESSIONS,
	SYNC_ERP_DELETIONS,
	/** Invoices from the ERP: the franchise invoices by number (a head office with headoffice.supply.source=ERP). */
	IMPORT_SUPPLY_INVOICES,
	/**
	 * Release 2.2: the whole catalogue in one run (families, sub-families, the items of every point de stock, barcodes), on a
	 * head office whose catalogue comes from the ERP through the navpospages connector only (ErpCatalogueSync).
	 */
	SYNC_CATALOGUE
}

