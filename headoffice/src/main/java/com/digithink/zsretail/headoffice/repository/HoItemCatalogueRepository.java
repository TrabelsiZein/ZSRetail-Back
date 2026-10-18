package com.digithink.zsretail.headoffice.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.digithink.zsretail.core.model.Item;

/**
 * Fiches items (3.1.0): the point de stock counts of the single-item lines of the head office page "Fiches items". The
 * list itself and its filter choices are built by {@link HoItemCatalogueQuery} (only the filters set). Nothing here is
 * used by the other item pages.
 */
public interface HoItemCatalogueRepository extends Repository<Item, Long> {

	/** [item id, active points where the item has an active row], for these items (one page of single-item lines). */
	String ITEM_POINT_COUNTS = "select r.itemId, count(distinct r.stockPointId) from HoStockPointItem r, HoStockPoint p"
			+ " where p.id = r.stockPointId and r.itemId in :itemIds and (r.active is null or r.active = true)"
			+ " and (p.active is null or p.active = true) group by r.itemId";

	@Query(ITEM_POINT_COUNTS)
	List<Object[]> countActivePointsOfItems(@Param("itemIds") Collection<Long> itemIds);
}
