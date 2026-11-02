package com.digithink.zsretail.core.model.enumeration;

/**
 * 3.1.2, the cost history (item_cost_history): what changed an item's CMP (Item.costPrice). Where the installation keeps
 * its own stock only; a sale never changes it.
 */
public enum CostChangeSource {
	/** A purchase of the store or the head office (PurchaseHeaderService), its number as the document. */
	LOCAL_PURCHASE,
	/** An ERP invoice received by a store (DeliveryReceptionService), its number as the document. */
	INVOICE_RECEPTION,
	/** A BL of the head office whose invoice brought its costs (DeliveryReceptionService), the BL's number. */
	SUPPLY_INVOICE,
	/** "Ajuster le CMP" on the item card (ItemService.adjustCost); its reason is in the COST_ADJUSTMENT movement. */
	MANUAL,
	/** The cost price column of the Excel import, a new item only (DataImportService). */
	EXCEL_IMPORT,
	/** POST /item with a cost price (ItemService.create). */
	ITEM_CREATION
}
