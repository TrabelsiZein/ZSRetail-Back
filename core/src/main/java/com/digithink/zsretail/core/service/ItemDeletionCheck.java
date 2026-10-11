package com.digithink.zsretail.core.service;

/**
 * Something of an application that holds an item and refuses its deletion with a clear message, instead of the
 * database's foreign key error. Asked by DELETE /item/{id} (ItemAPI) before the deletion; none by default. RFID: a
 * store's item with RFID tags (store.rfid.service.RfidItemDeletionCheck).
 */
public interface ItemDeletionCheck {

	/** Why the item of this id cannot be deleted now; null when this check does not hold it. */
	String refusal(Long itemId);
}
