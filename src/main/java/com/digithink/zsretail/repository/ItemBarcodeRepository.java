package com.digithink.zsretail.repository;

import java.util.List;
import java.util.Optional;

import com.digithink.zsretail.model.ItemBarcode;

public interface ItemBarcodeRepository extends _BaseRepository<ItemBarcode, Long> {

	List<ItemBarcode> findByItemId(Long itemId);

	Optional<ItemBarcode> findByBarcode(String barcode);

	List<ItemBarcode> findByItemIdAndActiveTrue(Long itemId);

	Optional<ItemBarcode> findByItemIdAndIsPrimaryTrue(Long itemId);

	Optional<ItemBarcode> findByErpExternalId(String erpExternalId);

	List<ItemBarcode> findByItemIdInAndActiveTrue(List<Long> itemIds);

	List<ItemBarcode> findByItemIdIn(List<Long> itemIds);
}

