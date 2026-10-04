package com.digithink.zsretail.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.model.ItemBarcode;

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
}
