package com.digithink.zsretail.erp.spi;

/**
 * Code cleanup, step 2: what the ERP connector in effect can do, as the application asks it ({@link ErpAbilities}). Only
 * the abilities the code needs today.
 */
public enum ErpAbility {

	/**
	 * The catalogue read by points de stock, with its single job SYNC_CATALOGUE (ErpCatalogueSync): the head office keeps
	 * the points and their rows (ho_stock_point, ho_stock_point_item) and shows them on the points page.
	 */
	CATALOGUE_BY_STOCK_POINTS,

	/** Reading the ERP's supply invoices (ErpSupplyInvoiceImport), so a head office may set headoffice.supply.source=ERP. */
	SUPPLY_INVOICES
}
