package com.digithink.zsretail.erp.api;

/**
 * Code cleanup, step 2: what the ERP connector in effect can do, as the application asks it ({@link ErpAbilities}). Only
 * the abilities the code needs today.
 */
public enum ErpAbility {

	/**
	 * The head office keeps several points de stock in its own tables (ho_stock_point, ho_stock_point_item, the points
	 * page), filled by the single catalogue job SYNC_CATALOGUE (ErpCatalogueSync). Not "reads items by location": every
	 * connector may do that; this ability is about the head office tables and the single job. Was
	 * CATALOGUE_BY_STOCK_POINTS (code cleanup step 3).
	 */
	HEAD_OFFICE_STOCK_POINTS,

	/** Reading the ERP's supply invoices (ErpSupplyInvoiceImport), so a head office may set headoffice.supply.source=ERP. */
	SUPPLY_INVOICES,

	/**
	 * 3.1.1: posting the stores' orders as sales orders (ErpConnector.postSalesOrder, looked up first by their number)
	 * and reading the variants' stock of a location (fetchVariantStock), so a head office may set
	 * headoffice.storeorders.post=ERP.
	 */
	STORE_ORDERS
}
