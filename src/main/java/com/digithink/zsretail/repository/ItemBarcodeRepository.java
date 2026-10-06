package com.digithink.zsretail.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.enumeration.RecordOrigin;

public interface ItemBarcodeRepository extends _BaseRepository<ItemBarcode, Long> {

	List<ItemBarcode> findByItemId(Long itemId);

	Optional<ItemBarcode> findByBarcode(String barcode);

	List<ItemBarcode> findByItemIdAndActiveTrue(Long itemId);

	Optional<ItemBarcode> findByItemIdAndIsPrimaryTrue(Long itemId);

	Optional<ItemBarcode> findByErpExternalId(String erpExternalId);

	List<ItemBarcode> findByItemIdInAndActiveTrue(List<Long> itemIds);

	List<ItemBarcode> findByItemIdIn(List<Long> itemIds);

	/** Head office plan, step 6: the barcodes of these values. */
	List<ItemBarcode> findByBarcodeIn(Collection<String> barcodes);

	/** Step 6: every barcode value except those of one item code (the startup backfill of the catalogue copies). */
	@Query("select b.barcode from ItemBarcode b where b.item.itemCode <> :excludedItemCode")
	List<String> findAllBarcodesExceptItem(@Param("excludedItemCode") String excludedItemCode);

	/** Inventory count, at the import: [barcode, item id] of every active barcode (active null counts as active). */
	@Query("select b.barcode, b.item.id from ItemBarcode b where b.active is null or b.active = true")
	List<Object[]> findActiveBarcodeItemIds();

	/**
	 * Head office plan, step 6: the origin of one row (the column is not updatable through a save). Pending changes are
	 * flushed first. Returns the number of rows updated.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update ItemBarcode x set x.origin = :origin where x.id = :id")
	int setOrigin(@Param("id") Long id, @Param("origin") RecordOrigin origin);
}
