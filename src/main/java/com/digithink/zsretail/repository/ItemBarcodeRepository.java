package com.digithink.zsretail.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.dto.ItemBarcodeRowDTO;
import com.digithink.zsretail.dto.ItemWithoutBarcodeRowDTO;
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

	/** The items listed on the barcodes page: shown at the till (showInPos true or empty), never TAX_STAMP. */
	String LISTED_ITEM = "(i.showInPos = true or i.showInPos is null) and i.itemCode <> 'TAX_STAMP'";

	/** Family and sub-family filters (null = any). */
	String FAMILY_FILTERS = " and (:familyId is null or f.id = :familyId) and (:subFamilyId is null or sf.id = :subFamilyId)";

	/** Item code or name containing the search (:contains = '%text%' in lower case, escaped; null = no search). */
	String ITEM_MATCH = "lower(i.itemCode) like :contains escape '\\' or lower(i.name) like :contains escape '\\'";

	String BARCODE_ROWS_FROM = " from ItemBarcode b join b.item i left join i.itemFamily f left join i.itemSubFamily sf"
			+ " where " + LISTED_ITEM + FAMILY_FILTERS
			+ " and (:prefix is null or b.barcode like :prefix escape '\\' or " + ITEM_MATCH + ")";

	/**
	 * The barcodes page: one row per barcode, by barcode. :prefix = the search escaped + '%' (the barcode equal to it or
	 * starting with it: a seek on the unique index of item_barcode.barcode), or null.
	 */
	String BARCODE_ROWS = "select new com.digithink.zsretail.dto.ItemBarcodeRowDTO(b.id, b.barcode, b.isPrimary,"
			+ " b.description, b.active, i.id, i.itemCode, i.name, i.active, i.origin, f.name, sf.name)"
			+ BARCODE_ROWS_FROM + " order by b.barcode";

	String BARCODE_ROWS_COUNT = "select count(b)" + BARCODE_ROWS_FROM;

	@Query(value = BARCODE_ROWS, countQuery = BARCODE_ROWS_COUNT)
	Page<ItemBarcodeRowDTO> findBarcodeRows(@Param("familyId") Long familyId, @Param("subFamilyId") Long subFamilyId,
			@Param("prefix") String prefix, @Param("contains") String contains, Pageable pageable);

	String WITHOUT_BARCODE_FROM = " from Item i left join i.itemFamily f left join i.itemSubFamily sf where "
			+ LISTED_ITEM + FAMILY_FILTERS
			+ " and not exists (select b.id from ItemBarcode b where b.item = i and (b.active = true or b.active is null))"
			+ " and (i.barcode is null or i.barcode = '')" + " and (:contains is null or " + ITEM_MATCH + ")";

	/** The items without any active barcode (and nothing in the old item.barcode field), by item code. */
	String WITHOUT_BARCODE = "select new com.digithink.zsretail.dto.ItemWithoutBarcodeRowDTO(i.id, i.itemCode, i.name,"
			+ " i.active, i.origin, f.name, sf.name)" + WITHOUT_BARCODE_FROM + " order by i.itemCode";

	String WITHOUT_BARCODE_COUNT = "select count(i)" + WITHOUT_BARCODE_FROM;

	@Query(value = WITHOUT_BARCODE, countQuery = WITHOUT_BARCODE_COUNT)
	Page<ItemWithoutBarcodeRowDTO> findItemsWithoutBarcode(@Param("familyId") Long familyId,
			@Param("subFamilyId") Long subFamilyId, @Param("contains") String contains, Pageable pageable);

	/**
	 * Head office plan, step 6: the origin of one row (the column is not updatable through a save). Pending changes are
	 * flushed first. Returns the number of rows updated.
	 */
	@Modifying(flushAutomatically = true)
	@Query("update ItemBarcode x set x.origin = :origin where x.id = :id")
	int setOrigin(@Param("id") Long id, @Param("origin") RecordOrigin origin);
}
