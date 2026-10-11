package com.digithink.zsretail.service;

import java.util.Collection;

import com.digithink.zsretail.model._BaseEntity;
import com.digithink.zsretail.model.enumeration.CatalogueKind;

/**
 * Head office plan, step 6: what a head office that sends its catalogue adds to the item, family, sub-family, barcode and
 * pack services and to the data import. Implemented by a head office only bean (HoCatalogueService, on a head office
 * without an ERP); on a store there is none and the services behave as before. Called inside the transaction of the
 * save or the delete.
 */
public interface CatalogueHeadOfficeHooks {

	/**
	 * Before a record is saved. previousCode: its code in the database, null for a new record. Throws
	 * {@link CatalogueCodeChangeException} when the code of an item, a family or a sub-family changes, or IllegalArgument
	 * when a code is too long to travel.
	 */
	void beforeSave(CatalogueKind kind, String previousCode, _BaseEntity record);

	/** After a record is saved (created, edited, activated, deactivated): every store gets the change. */
	void afterSave(CatalogueKind kind, String previousCode, _BaseEntity saved);

	/** Before a record is deleted: every store gets a removal (an item's price list lines are deleted). */
	void beforeDelete(CatalogueKind kind, _BaseEntity record);

	/** After the pack (composition) of an item changed: the item is sent again. */
	void afterPackChanged(Long parentItemId);

	/** After a data import saved these codes (outside one transaction): each one is recorded for every store. */
	void afterImport(CatalogueKind kind, Collection<String> codes);
}
