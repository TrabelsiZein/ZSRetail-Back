package com.digithink.zsretail.core.service;

import java.util.Collection;

import com.digithink.zsretail.core.model.Item;
import com.digithink.zsretail.core.model.ItemBarcode;

/**
 * The refusals of the catalogue, purchase and import screens on a store whose catalogue is the head office's (the bean
 * exists only there: holink.service.StoreCatalogueGuard). Each check answers the refusal message, or null when the
 * change is allowed. Without the bean the screens allow everything, as before. Code cleanup step 5 (knot b): the
 * screens are core, the guard is the store's.
 */
public interface CatalogueEditGuard {

	String itemWrite(Long itemId);

	String familyWrite(Long familyId);

	String subFamilyWrite(Long subFamilyId);

	String barcodeWrite(Long barcodeId);

	String create();

	String itemCodeTaken(String itemCode);

	String familyCodeTaken(String code);

	String subFamilyCodeTaken(String code);

	String barcodeCreate(ItemBarcode barcode);

	String purchase();

	String purchaseLines(Collection<Long> itemIds);

	String dataImport(String entityType);

	String salesPriceWrite();

	/** The store's own selling price of a head office item (with the price right). */
	Item setOwnPrice(Long itemId, Double price);

	/** The head office price back on an item that had the store's own. */
	Item giveBackPrice(Long itemId);
}
