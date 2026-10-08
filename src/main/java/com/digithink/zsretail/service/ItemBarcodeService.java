package com.digithink.zsretail.service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.digithink.zsretail.dto.ItemBarcodeRowDTO;
import com.digithink.zsretail.dto.ItemWithoutBarcodeRowDTO;
import com.digithink.zsretail.model.Item;
import com.digithink.zsretail.model.ItemBarcode;
import com.digithink.zsretail.model.enumeration.CatalogueKind;
import com.digithink.zsretail.model.enumeration.ItemType;
import com.digithink.zsretail.repository.ItemBarcodeRepository;
import com.digithink.zsretail.repository._BaseRepository;

@Service
public class ItemBarcodeService extends _BaseService<ItemBarcode, Long> {

	@Autowired
	private ItemBarcodeRepository itemBarcodeRepository;

//	@Autowired
//	private ItemRepository itemRepository;

	/** Step 6: a head office that sends its catalogue records each change; no bean on a store. */
	@Autowired(required = false)
	private ObjectProvider<CatalogueHeadOfficeHooks> catalogueHooks;

	@Override
	protected _BaseRepository<ItemBarcode, Long> getRepository() {
		return itemBarcodeRepository;
	}

	@Override
	@Transactional
	public ItemBarcode save(ItemBarcode barcode) throws Exception {
		return CatalogueHookCalls.save(CatalogueHookCalls.hooks(catalogueHooks), CatalogueKind.BARCODE, barcode,
				id -> itemBarcodeRepository.findById(id).map(ItemBarcode::getBarcode), super::save);
	}

	@Override
	@Transactional
	public void deleteById(Long id) {
		CatalogueHeadOfficeHooks hooks = CatalogueHookCalls.hooks(catalogueHooks);
		if (hooks != null) {
			itemBarcodeRepository.findById(id).ifPresent(barcode -> hooks.beforeDelete(CatalogueKind.BARCODE, barcode));
		}
		super.deleteById(id);
	}

	/**
	 * The barcodes page (GET item-barcode/list): one row per barcode, by barcode, one query with joins. search: the
	 * barcode equal to it or starting with it, or the item code or name containing it (any case); blank = none.
	 * TAX_STAMP and the items hidden from the till are never listed.
	 */
	public Page<ItemBarcodeRowDTO> findBarcodeRows(String search, Long familyId, Long subFamilyId, Pageable pageable) {
		return itemBarcodeRepository.findBarcodeRows(familyId, subFamilyId, prefixPattern(search), containsPattern(search),
				pageable);
	}

	/** The items without any active barcode, by item code; search on the item code or name (any case). */
	public Page<ItemWithoutBarcodeRowDTO> findItemsWithoutBarcode(String search, Long familyId, Long subFamilyId,
			Pageable pageable) {
		return itemBarcodeRepository.findItemsWithoutBarcode(familyId, subFamilyId, containsPattern(search), pageable);
	}

	/** :prefix of the barcode queries: the trimmed search escaped + '%'; null when blank. */
	public static String prefixPattern(String search) {
		String term = term(search);
		return term == null ? null : like(term) + "%";
	}

	/** :contains of the barcode queries: '%' + the trimmed search in lower case, escaped + '%'; null when blank. */
	public static String containsPattern(String search) {
		String term = term(search);
		return term == null ? null : "%" + like(term.toLowerCase(Locale.ROOT)) + "%";
	}

	/** The trimmed search, null when blank. */
	static String term(String search) {
		return search == null || search.trim().isEmpty() ? null : search.trim();
	}

	/** A LIKE pattern part with its wildcards escaped (escape character '\'; SQL Server also reads [ ). */
	static String like(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_").replace("[", "\\[");
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
