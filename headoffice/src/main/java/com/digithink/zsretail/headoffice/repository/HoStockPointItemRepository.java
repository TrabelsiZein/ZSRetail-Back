package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;

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

	/**
	 * Step 11a: [item id, point id, unit price, active, clearance, clearance price] of every row of these items; 3.1.1
	 * (the Item cards' Stock points tab), then [row name, row description, row family code, row sub-family code, item
	 * name, item description, item family code, item sub-family code].
	 */
	String ROWS_OF_ITEMS = "select r.itemId, r.stockPointId, r.unitPrice, r.active, r.clearance, r.clearancePrice,"
			+ " r.name, r.description, r.familyCode, r.subFamilyCode, i.name, i.description, f.code, sf.code"
			+ " from HoStockPointItem r, Item i left join i.itemFamily f left join i.itemSubFamily sf"
			+ " where i.id = r.itemId and r.itemId in :itemIds";

	@Query(NAV_ITEM_POINT_COUNTS)
	List<Object[]> countActivePointsOfNavItems(@Param("navItemNos") Collection<String> navItemNos);

	/**
	 * 3.1.1, Item cards list with a point chosen: [NAV item number, item id, item active, item VAT, row price, row active,
	 * clearance, clearance price] of the rows at the point of these NAV items' variants (POINT_ROWS_OF_ITEMS: of these
	 * items); the service applies the statuses and the clearance rule (HoItemCatalogueService.pointPrices).
	 */
	String POINT_ROWS_SELECT = "select i.navItemNo, i.id, i.active, i.defaultVAT, r.unitPrice, r.active, r.clearance,"
			+ " r.clearancePrice from HoStockPointItem r, Item i where i.id = r.itemId and r.stockPointId = :pointId";

	String POINT_ROWS_OF_NAV_ITEMS = POINT_ROWS_SELECT + " and i.navItemNo in :navItemNos";

	String POINT_ROWS_OF_ITEMS = POINT_ROWS_SELECT + " and i.id in :itemIds";

	@Query(POINT_ROWS_OF_NAV_ITEMS)
	List<Object[]> findPointRowsOfNavItems(@Param("pointId") Long pointId,
			@Param("navItemNos") Collection<String> navItemNos);

	@Query(POINT_ROWS_OF_ITEMS)
	List<Object[]> findPointRowsOfItems(@Param("pointId") Long pointId, @Param("itemIds") Collection<Long> itemIds);

	@Query(ROWS_OF_ITEMS)
	List<Object[]> findRowsOfItems(@Param("itemIds") Collection<Long> itemIds);

	// 3.1.1: the two queries of the page "Items by point de stock" (findRows, findRowsSearched) are gone with the page;
	// Item cards' point filter lists a point's rows (HoItemCatalogueQuery.stockPoint)
}
