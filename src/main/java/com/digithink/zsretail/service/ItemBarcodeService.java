package com.digithink.zsretail.service;

import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class ItemBarcodeService extends _BaseService<ItemBarcode, Long> {

	@Autowired
	private ItemBarcodeRepository itemBarcodeRepository;

//	@Autowired
//	private ItemRepository itemRepository;

	@Override
	protected _BaseRepository<ItemBarcode, Long> getRepository() {
		return itemBarcodeRepository;
	}

	/**
	 * Get all barcodes for an item
	 */
	public List<ItemBarcode> getBarcodesByItemId(Long itemId) {
		return itemBarcodeRepository.findByItemIdAndActiveTrue(itemId);
	}

	public List<ItemBarcode> getActiveBarcodesForItems(List<Long> itemIds) {
		if (itemIds == null || itemIds.isEmpty()) {
			return List.of();
		}
		return itemBarcodeRepository.findByItemIdIn(itemIds).stream()
				.filter(bc -> bc.getActive() == null || Boolean.TRUE.equals(bc.getActive())).toList();
	}

	/**
	 * Get item by barcode
	 */
	public Optional<Item> getItemByBarcode(String barcode) {
		Optional<ItemBarcode> itemBarcode = itemBarcodeRepository.findByBarcode(barcode);
		return itemBarcode.filter(bc -> bc.getActive() == null || Boolean.TRUE.equals(bc.getActive()))
				.map(ItemBarcode::getItem)
				// PACKAGE (kit) items have no price of their own — they explode into components
				.filter(item -> item.getType() == ItemType.PACKAGE
						|| (item.getUnitPrice() != null && item.getUnitPrice() > 0));
	}

	/**
	 * Get primary barcode for an item
	 */
	public Optional<ItemBarcode> getPrimaryBarcode(Long itemId) {
		return itemBarcodeRepository.findByItemIdAndIsPrimaryTrue(itemId);
	}
}
