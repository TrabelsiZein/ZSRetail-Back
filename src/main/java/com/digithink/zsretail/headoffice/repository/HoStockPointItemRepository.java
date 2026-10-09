package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.headoffice.model.HoStockPointItem;
import com.digithink.zsretail.repository._BaseRepository;

public interface HoStockPointItemRepository extends _BaseRepository<HoStockPointItem, Long> {

	long countByStockPointId(Long stockPointId);

	/** Stock points, step 3a: the point's rows of these items (the copy sent to a store of the point). */
	List<HoStockPointItem> findByStockPointIdAndItemIdIn(Long stockPointId, Collection<Long> itemIds);

	String ROWS_WHERE = " where r.itemId = i.id and p.id = r.stockPointId"
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
