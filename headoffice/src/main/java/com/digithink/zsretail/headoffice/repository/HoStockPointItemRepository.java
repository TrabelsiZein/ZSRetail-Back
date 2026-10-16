package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.repository._BaseRepository;
import com.digithink.zsretail.headoffice.model.HoStockPointItem;

public interface HoStockPointItemRepository extends _BaseRepository<HoStockPointItem, Long> {

	long countByStockPointId(Long stockPointId);

	/** Stock points, step 3a: the point's rows of these items (the copy sent to a store of the point). */
	List<HoStockPointItem> findByStockPointIdAndItemIdIn(Long stockPointId, Collection<Long> itemIds);

	/** [store id, item id] of the stores whose point has a row for these items, active or not (copies down). */
	String STORE_AND_ITEM_IDS = "select s.id, r.itemId from Store s, HoStockPointItem r"
			+ " where s.stockPointId = r.stockPointId and r.itemId in :itemIds";

	/** The codes of the items with a row in these points, active or not, except one item (TAX_STAMP), by code. */
	String ITEM_CODES_OF_POINTS = "select distinct i.itemCode from HoStockPointItem r, Item i where r.itemId = i.id"
			+ " and r.stockPointId in :pointIds and i.itemCode <> :excludedItemCode order by i.itemCode";

	/** The barcodes of the items with a row in these points, active or not, except one item's (TAX_STAMP), by value. */
	String BARCODES_OF_POINTS = "select distinct b.barcode from HoStockPointItem r, ItemBarcode b join b.item i"
			+ " where i.id = r.itemId and r.stockPointId in :pointIds and i.itemCode <> :excludedItemCode"
			+ " order by b.barcode";

	/** [store id, item id] of every store and every row of its point, active or not (the startup backfill). */
	String ALL_STORE_AND_ITEM_IDS = "select s.id, r.itemId from Store s, HoStockPointItem r"
			+ " where s.stockPointId = r.stockPointId";

	/** [id, code, ERP id] of every item (the startup backfill: an ERP item goes to the stores of its points). */
	String ITEM_KEYS = "select i.id, i.itemCode, i.erpExternalId from Item i";

	/** [barcode, item id] of every barcode with an item (the startup backfill). */
	String BARCODE_ITEM_IDS = "select b.barcode, i.id from ItemBarcode b join b.item i";

	/** [id, barcode, item id] of the barcodes of these ERP ids (before an ERP import of barcodes). */
	String BARCODES_BY_ERP_ID = "select b.id, b.barcode, i.id from ItemBarcode b left join b.item i"
			+ " where b.erpExternalId in :externalIds";

	/** [id, barcode, item id] of the barcodes of these values (before an ERP import of barcodes). */
	String BARCODES_BY_VALUE = "select b.id, b.barcode, i.id from ItemBarcode b left join b.item i"
			+ " where b.barcode in :values";

	/** Copies down: the stores a change of these items is recorded for. At most 1000 ids per call. */
	@Query(STORE_AND_ITEM_IDS)
	List<Object[]> findStoreAndItemIds(@Param("itemIds") Collection<Long> itemIds);

	/** Copies down, the startup backfill: every store with the items of its point. */
	@Query(ALL_STORE_AND_ITEM_IDS)
	List<Object[]> findAllStoreAndItemIds();

	/** Copies down, the startup backfill: every item, with its ERP id. */
	@Query(ITEM_KEYS)
	List<Object[]> findItemKeys();

	/** Copies down, the startup backfill: every barcode with its item. */
	@Query(BARCODE_ITEM_IDS)
	List<Object[]> findBarcodeItemIds();

	/**
	 * Copies down, before an ERP import of barcodes: the value and the item of the barcodes it may change (the import
	 * finds a barcode by its ERP id, then by its value). At most 1000 values per call.
	 */
	@Query(BARCODES_BY_ERP_ID)
	List<Object[]> findBarcodesByErpIdIn(@Param("externalIds") Collection<String> externalIds);

	/** Copies down, before an ERP import of barcodes: see {@link #findBarcodesByErpIdIn}. */
	@Query(BARCODES_BY_VALUE)
	List<Object[]> findBarcodesByValueIn(@Param("values") Collection<String> values);

	/** Copies down: the items sent again to a store whose point changed (the previous and the new point). */
	@Query(ITEM_CODES_OF_POINTS)
	List<String> findItemCodesOfPoints(@Param("pointIds") Collection<Long> pointIds,
			@Param("excludedItemCode") String excludedItemCode);

	/** Copies down: the barcodes sent again to a store whose point changed (the previous and the new point). */
	@Query(BARCODES_OF_POINTS)
	List<String> findBarcodesOfPoints(@Param("pointIds") Collection<Long> pointIds,
			@Param("excludedItemCode") String excludedItemCode);

	/**
	 * Item variants (ZEN), step 11a, page "NAV items": [NAV item number, number of active points where at least one of
	 * its variants has an active row], for these numbers (at most one page of them).
	 */
	String NAV_ITEM_POINT_COUNTS = "select i.navItemNo, count(distinct r.stockPointId)"
			+ " from HoStockPointItem r, Item i, HoStockPoint p where r.itemId = i.id and p.id = r.stockPointId"
			+ " and i.navItemNo in :navItemNos and (r.active is null or r.active = true)"
			+ " and (p.active is null or p.active = true) group by i.navItemNo";

	/** Step 11a: [item id, point id, unit price, active, clearance, clearance price] of every row of these items. */
	String ROWS_OF_ITEMS = "select r.itemId, r.stockPointId, r.unitPrice, r.active, r.clearance, r.clearancePrice"
			+ " from HoStockPointItem r where r.itemId in :itemIds";

	@Query(NAV_ITEM_POINT_COUNTS)
	List<Object[]> countActivePointsOfNavItems(@Param("navItemNos") Collection<String> navItemNos);

	@Query(ROWS_OF_ITEMS)
	List<Object[]> findRowsOfItems(@Param("itemIds") Collection<Long> itemIds);

	String ROWS_WHERE =" where r.itemId = i.id and p.id = r.stockPointId"
			+ " and (:pointId is null or r.stockPointId = :pointId)"
			+ " and (:search is null or lower(i.itemCode) like :search or lower(r.name) like :search"
			+ " or exists (select b.id from ItemBarcode b where b.item.id = i.id and lower(b.barcode) like :search))"
			+ " and (:familyCode is null or r.familyCode = :familyCode)"
			+ " and (:subFamilyCode is null or r.subFamilyCode = :subFamilyCode)"
			+ " and (:priceMin is null or r.unitPrice >= :priceMin) and (:priceMax is null or r.unitPrice <= :priceMax)"
			+ " and (:active is null or r.active = :active)";

	/**
	 * Step 4, page "Items by point de stock": [row, item, point] by point (list order) then item code. search: lower case
	 * with % already around it (item code, the row's name, a barcode); every filter null = not filtered.
	 */
	@Query(value = "select r, i, p from HoStockPointItem r, Item i, HoStockPoint p" + ROWS_WHERE
			+ " order by p.sortOrder, i.itemCode",
			countQuery = "select count(r) from HoStockPointItem r, Item i, HoStockPoint p" + ROWS_WHERE)
	Page<Object[]> findRows(@Param("pointId") Long pointId, @Param("search") String search,
			@Param("familyCode") String familyCode, @Param("subFamilyCode") String subFamilyCode,
			@Param("priceMin") Double priceMin, @Param("priceMax") Double priceMax, @Param("active") Boolean active,
			Pageable page);
}
